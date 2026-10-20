package com.fridgelist.app.feature.lists

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgelist.core.data.firestore.ItemDraft
import com.fridgelist.core.data.firestore.ItemPatches
import com.fridgelist.core.data.items.AliasRepository
import com.fridgelist.core.data.items.ItemRepository
import com.fridgelist.core.data.items.ParseRepository
import com.fridgelist.core.data.voice.TranscribeRepository
import com.fridgelist.app.voice.AudioRecorder
import com.fridgelist.app.voice.SpeechEngines
import com.fridgelist.app.voice.VoiceInput
import com.fridgelist.app.voice.VoiceLocales
import com.fridgelist.app.voice.VoiceState
import com.fridgelist.core.i18n.AliasId
import com.fridgelist.core.i18n.Catalog
import com.fridgelist.core.i18n.LocalSplitter
import com.fridgelist.core.i18n.Normalize
import com.fridgelist.core.model.AddedVia
import com.fridgelist.core.model.ItemUnit
import com.fridgelist.core.model.Limits
import com.fridgelist.core.model.ParseItemsRequest
import com.fridgelist.core.model.ParsedItem
import com.fridgelist.core.model.Role
import com.fridgelist.core.model.StatusRules
import com.fridgelist.core.model.clip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** One parsed item as a chip. [choices] is non-empty while a dialect word needs the user's pick. */
data class Chip(
    val key: Int,
    val name: String,
    val qty: Double?,
    val unit: ItemUnit?,
    val canonicalId: String?,
    val choices: List<Choice> = emptyList(),
    /** Normalized dialect word the pick is saved for (CONTRACT §3.16). */
    val aliasText: String? = null,
)

data class Choice(val canonicalId: String, val label: String)

data class AddItemsUiState(
    val text: String = "",
    val chips: List<Chip> = emptyList(),
    val refining: Boolean = false,
    /** Kids' items become requests (CONTRACT §3.6); the sheet says so. */
    val asRequest: Boolean = false,
    val added: Boolean = false,
)

/**
 * The add sheet (prompt A3): local split at once → `parseItems` refines when online → the user
 * confirms. Never blocks on the network (CLAUDE.md rule 2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AddItemsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    contextSource: HouseholdContextSource,
    private val items: ItemRepository,
    private val aliasRepo: AliasRepository,
    private val parser: ParseRepository,
    speechEngines: SpeechEngines,
    recorder: AudioRecorder,
    transcriber: TranscribeRepository,
) : ViewModel() {

    private val listId: String = checkNotNull(savedState.get<String>("listId"))

    /** Quick Add from a widget or the tile (`widget`, `qs_tile`); null in the app. */
    private val launchedVia: AddedVia? = savedState.get<String>("via")?.let(AddedVia::fromWire)
    private val splitter = LocalSplitter()
    private val catalog = Catalog.bundled

    private val context = contextSource.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val aliases: StateFlow<Map<String, String>> = contextSource.context
        .flatMapLatest { aliasRepo.aliases(it.householdId).onStart { emit(emptyMap()) }.catch { emit(emptyMap()) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _state = MutableStateFlow(AddItemsUiState())

    /**
     * The field's text as Compose snapshot state: updated synchronously on every key. Read through
     * the StateFlow it lagged a frame, and fast typing (or a keyboard's batch commit) lost letters.
     */
    var text by mutableStateOf("")
        private set
    val state: StateFlow<AddItemsUiState> = _state.asStateFlow()

    private var refineJob: Job? = null

    /** Voice add (prompt A4). The transcript goes through the same chips as typing. */
    private val voiceInput = VoiceInput(speechEngines, recorder, transcriber, viewModelScope)
    val voice: StateFlow<VoiceState> = voiceInput.state

    /** True once this sheet's text came (partly) from voice: items are written with `app_voice`. */
    private var usedVoice = false

    init {
        viewModelScope.launch {
            context.collect { ctx -> if (ctx != null) _state.update { it.copy(asRequest = ctx.role == Role.KID) } }
        }
    }

    fun onTextChange(text: String) {
        val limited = text.clip(Limits.PARSE_TEXT_MAX)
        val ctx = context.value
        val chips = if (ctx == null) emptyList() else localChips(limited, ctx)
        this.text = limited
        _state.update { it.copy(text = limited, chips = chips, refining = false) }
        refineJob?.cancel()
        if (ctx != null && limited.isNotBlank()) {
            refineJob = viewModelScope.launch {
                delay(REFINE_DELAY_MS)
                refine(limited, ctx)
            }
        }
    }

    private fun localChips(text: String, ctx: HouseholdContext): List<Chip> =
        splitter.split(text, ctx.localeHints, aliases.value).mapIndexed { i, item ->
            Chip(
                key = i,
                name = item.name,
                qty = ItemPatches.validQty(item.qty),
                unit = item.unit,
                canonicalId = item.canonicalId,
                choices = item.ambiguity?.candidates.orEmpty().map { Choice(it, label(it, ctx)) },
                aliasText = item.ambiguity?.wordKey,
            )
        }

    /** Server refinement. Any failure (offline, rate limit, LLM down) keeps the local chips. */
    private suspend fun refine(text: String, ctx: HouseholdContext) {
        _state.update { it.copy(refining = true) }
        val response = runCatching { parser.parse(ParseItemsRequest(ctx.householdId, text, ctx.localeHints)) }.getOrNull()
        if (_state.value.text != text) return // the user kept typing
        val refined = response?.items?.takeIf { it.isNotEmpty() }?.mapIndexed { i, parsed -> serverChip(i, parsed, ctx) }
        _state.update { it.copy(refining = false, chips = refined ?: it.chips) }
    }

    private fun serverChip(key: Int, parsed: ParsedItem, ctx: HouseholdContext): Chip {
        val ambiguity = if (parsed.confidence < Limits.AMBIGUOUS_CONFIDENCE) {
            catalog.ambiguity(Normalize.matchKey(parsed.name).split(' '), ctx.localeHints, aliases.value)
        } else {
            null
        }
        return Chip(
            key = key,
            name = parsed.name,
            qty = ItemPatches.validQty(parsed.qty),
            unit = parsed.unitEnum,
            canonicalId = parsed.canonicalId,
            choices = ambiguity?.candidates.orEmpty().map { Choice(it, label(it, ctx)) },
            aliasText = ambiguity?.wordKey,
        )
    }

    /** The user's pick for an ambiguous word: saved as a household alias so it is asked only once. */
    fun choose(chip: Chip, choice: Choice) {
        val ctx = context.value ?: return
        chip.aliasText?.let { text -> aliasRepo.save(ctx.householdId, AliasId.of(text), text, choice.canonicalId, ctx.uid) }
        _state.update { s ->
            s.copy(chips = s.chips.map { if (it.key == chip.key) it.copy(canonicalId = choice.canonicalId, choices = emptyList()) else it })
        }
    }

    fun remove(chip: Chip) = _state.update { s -> s.copy(chips = s.chips.filterNot { it.key == chip.key }) }

    /** Writes the chips as items (offline-first, `addedVia: app_text`). */
    fun confirm() {
        val ctx = context.value ?: return
        val chips = _state.value.chips
        if (chips.isEmpty()) return
        refineJob?.cancel()
        val status = StatusRules.createStatus(ctx.role)
        val via = launchedVia ?: if (usedVoice) AddedVia.APP_VOICE else AddedVia.APP_TEXT
        val patches = chips.map { ItemPatches.create(ItemDraft(it.name, it.qty, it.unit), status, ctx.uid, via) }
        items.add(ctx.householdId, listId, patches)
        voiceInput.cancel()
        usedVoice = false
        text = ""
        _state.value = AddItemsUiState(asRequest = _state.value.asRequest, added = true)
    }

    fun onAddedHandled() = _state.update { it.copy(added = false) }

    /** Call after RECORD_AUDIO is granted. */
    fun startVoice() {
        val ctx = context.value ?: return
        val language = VoiceLocales.tagFor(ctx.viewerLang, Locale.getDefault())
        voiceInput.start(language, ctx.localeHints) { transcript -> onTranscript(transcript) }
    }

    fun stopVoice() = voiceInput.stop()

    fun dismissVoiceError() = voiceInput.dismissError()

    fun onMicDenied() = voiceInput.permissionDenied()

    /** The sheet closed: the mic must not keep listening (this ViewModel outlives the sheet). */
    fun cancelVoice() = voiceInput.cancel()

    /** A finished transcript is appended to what is already typed, then split like typing. */
    internal fun onTranscript(transcript: String) {
        usedVoice = true
        val existing = text.trim()
        val separator = if (isArabicScript(transcript)) "، " else ", "
        onTextChange(if (existing.isEmpty()) transcript else existing + separator + transcript)
    }

    /** Arabic block U+0600–U+06FF: join with the Arabic comma "،". */
    private fun isArabicScript(text: String) = text.any { it in '\u0600'..'ۿ' }

    override fun onCleared() {
        voiceInput.cancel()
    }

    private fun label(canonicalId: String, ctx: HouseholdContext): String =
        catalog.displayName(canonicalId, ctx.viewerLang) ?: canonicalId

    private companion object {
        const val REFINE_DELAY_MS = 700L
    }
}
