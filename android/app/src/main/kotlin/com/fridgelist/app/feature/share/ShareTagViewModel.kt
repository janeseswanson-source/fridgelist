package com.fridgelist.app.feature.share

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgelist.app.R
import com.fridgelist.app.feature.lists.HouseholdContextSource
import com.fridgelist.core.data.items.ListRepository
import com.fridgelist.core.data.share.ShareLinkRepository
import com.fridgelist.core.model.CreateShareLinkRequest
import com.fridgelist.core.model.ShareKind
import com.fridgelist.core.model.ShareScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

enum class TagStep { IDLE, HOLD, DONE }

data class ShareTagUiState(
    val listName: String = "",
    val guestsCanAdd: Boolean = true,
    val qrUrl: String? = null,
    val loadingQr: Boolean = false,
    val tagStep: TagStep = TagStep.IDLE,
    @param:StringRes val message: Int? = null,
)

/**
 * "Fridge tag and QR code" for one list (A9): one share link per kind (CONTRACT §3.11 `kind`), made
 * when first needed. Owners and adults only (`createShareLink`).
 */
@HiltViewModel
class ShareTagViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val contextSource: HouseholdContextSource,
    private val lists: ListRepository,
    private val links: ShareLinkRepository,
) : ViewModel() {

    val listId: String = checkNotNull(savedState.get<String>("listId"))

    private val _state = MutableStateFlow(ShareTagUiState())
    val state: StateFlow<ShareTagUiState> = _state.asStateFlow()

    /** Kind and scope → URL, so changing the scope makes a new link and switching back reuses one. */
    private val made = mutableMapOf<Pair<ShareKind, ShareScope>, String>()

    init {
        viewModelScope.launch {
            val ctx = contextSource.context.first()
            val name = withTimeoutOrNull(3_000) { lists.lists(ctx.householdId).first().firstOrNull { it.id == listId }?.name }
            _state.update { it.copy(listName = name.orEmpty()) }
            loadQr()
        }
    }

    fun onGuestsCanAdd(value: Boolean) {
        _state.update { it.copy(guestsCanAdd = value, qrUrl = null, tagStep = TagStep.IDLE, message = null) }
        loadQr()
    }

    private fun scope() = if (_state.value.guestsCanAdd) ShareScope.ADD else ShareScope.VIEW

    fun loadQr() {
        _state.update { it.copy(loadingQr = true, message = null) }
        viewModelScope.launch {
            val url = url(ShareKind.QR)
            _state.update { it.copy(loadingQr = false, qrUrl = url, message = if (url == null) R.string.share_offline else null) }
        }
    }

    /** Makes (or reuses) the NFC link; the screen then turns on reader mode. */
    fun startTag() {
        _state.update { it.copy(message = null) }
        viewModelScope.launch {
            val url = url(ShareKind.NFC)
            _state.update { if (url == null) it.copy(message = R.string.share_offline) else it.copy(tagStep = TagStep.HOLD) }
        }
    }

    fun tagUrl(): String? = made[ShareKind.NFC to scope()]

    fun onTagWritten(result: NfcWriteResult) {
        _state.update {
            when (result) {
                NfcWriteResult.OK -> it.copy(tagStep = TagStep.DONE, message = null)
                // Stay on "hold the sticker": the user can try another one right away.
                NfcWriteResult.TOO_SMALL -> it.copy(message = R.string.share_nfc_too_small)
                NfcWriteResult.READ_ONLY -> it.copy(message = R.string.share_nfc_read_only)
                NfcWriteResult.UNSUPPORTED -> it.copy(message = R.string.share_nfc_unsupported)
                NfcWriteResult.FAILED -> it.copy(message = R.string.share_nfc_failed)
            }
        }
    }

    fun cancelTag() = _state.update { it.copy(tagStep = TagStep.IDLE, message = null) }

    private suspend fun url(kind: ShareKind): String? {
        val key = kind to scope()
        made[key]?.let { return it }
        val ctx = contextSource.context.first()
        return runCatching {
            links.create(CreateShareLinkRequest(ctx.householdId, listId, key.second.wire, kind.wire)).url
        }.getOrNull()?.also { made[key] = it }
    }
}
