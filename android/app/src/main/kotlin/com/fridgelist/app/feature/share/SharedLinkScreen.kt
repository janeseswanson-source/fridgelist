package com.fridgelist.app.feature.share

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fridgelist.app.R
import com.fridgelist.app.feature.onboarding.UiLanguageProvider
import com.fridgelist.app.quickadd.QuickAdd
import com.fridgelist.app.ui.common.ErrorMessages
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.data.share.ShareLinkRepository
import com.fridgelist.core.model.GuestAddItemRequest
import com.fridgelist.core.model.Limits
import com.fridgelist.core.model.ResolveShareLinkRequest
import com.fridgelist.core.model.ResolveShareLinkResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SharedLinkUiState(
    val loading: Boolean = true,
    val link: ResolveShareLinkResponse? = null,
    @param:StringRes val error: Int? = null,
    val text: String = "",
    val guestName: String = "",
    val adding: Boolean = false,
    /** Items the last guest add created ("Added 2 items"). */
    val added: Int? = null,
    @param:StringRes val addError: Int? = null,
    /** A member's own tag: open Quick Add for this list instead (CONTRACT v5 `member`). */
    val openListId: String? = null,
)

/**
 * A fridge tag or QR opened in the app (CONTRACT §6 `/k/{token}`): a member of that household goes
 * to Quick Add for the list, voice ready; anyone else gets the guest view, with add when allowed.
 */
@HiltViewModel
class SharedLinkViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val links: ShareLinkRepository,
    private val session: SessionStore,
    private val uiLanguage: UiLanguageProvider,
) : ViewModel() {

    private val token: String = checkNotNull(savedState.get<String>("token"))

    private val _state = MutableStateFlow(SharedLinkUiState())
    val state: StateFlow<SharedLinkUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val link = links.resolve(ResolveShareLinkRequest(token, uiLanguage.current()))
                val mine = link.member?.takeIf { it.householdId == session.current().householdId }
                _state.update { it.copy(loading = false, link = link, openListId = mine?.listId) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = errorFor(e)) }
            }
        }
    }

    fun onText(value: String) = _state.update { it.copy(text = value.take(Limits.GUEST_TEXT_MAX), added = null, addError = null) }

    fun onGuestName(value: String) = _state.update { it.copy(guestName = value.take(Limits.GUEST_NAME_MAX)) }

    fun add() {
        val s = _state.value
        if (s.adding || s.text.isBlank() || s.link?.canAdd != true) return
        _state.update { it.copy(adding = true, added = null, addError = null) }
        viewModelScope.launch {
            try {
                val added = links.guestAdd(GuestAddItemRequest(token, s.text.trim(), s.guestName.trim().ifEmpty { null })).added
                _state.update { it.copy(adding = false, added = added, text = "") }
                load() // show what was just added
            } catch (e: ApiException) {
                _state.update { it.copy(adding = false, addError = errorFor(e)) }
            }
        }
    }

    fun onOpened() = _state.update { it.copy(openListId = null) }

    private fun errorFor(e: ApiException): Int = when (e.error) {
        ApiError.NOT_FOUND -> R.string.guest_not_found
        ApiError.NETWORK, ApiError.UNAVAILABLE -> R.string.guest_offline
        ApiError.RESOURCE_EXHAUSTED -> R.string.guest_too_many
        else -> ErrorMessages.general(e)
    }
}

@Composable
fun SharedLinkRoute(onBack: () -> Unit, vm: SharedLinkViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.openListId) {
        state.openListId?.let { listId ->
            vm.onOpened()
            context.startActivity(QuickAdd.intent(context, listId, voice = true, via = null))
            onBack()
        }
    }
    SharedLinkScreen(state, onBack, vm::load, vm::onText, vm::onGuestName, vm::add)
}

/** Stateless for screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedLinkScreen(
    state: SharedLinkUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onText: (String) -> Unit,
    onGuestName: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val link = state.link
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(link?.listName.orEmpty(), maxLines = 1)
                        if (link != null) Text(link.householdName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading && link == null -> Column(Modifier.padding(padding).padding(24.dp)) { CircularProgressIndicator() }
            link == null -> Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(state.error ?: R.string.guest_offline), style = MaterialTheme.typography.bodyLarge)
                if (state.error != R.string.guest_not_found) TextButton(onClick = onRetry) { Text(stringResource(R.string.share_retry)) }
            }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                if (link.canAdd) {
                    item {
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.text,
                                onValueChange = onText,
                                label = { Text(stringResource(R.string.guest_add_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { onAdd() }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = state.guestName,
                                onValueChange = onGuestName,
                                label = { Text(stringResource(R.string.guest_your_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(onClick = onAdd, enabled = state.text.isNotBlank() && !state.adding, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.guest_add))
                            }
                            state.added?.let {
                                Text(pluralStringResource(R.plurals.guest_added, it, it), color = MaterialTheme.colorScheme.primary)
                            }
                            state.addError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                if (link.items.isEmpty()) {
                    item { Text(stringResource(R.string.guest_empty), modifier = Modifier.padding(24.dp)) }
                }
                items(link.items, key = { it.id }) { item ->
                    // Server text in any script ("2 L" in an Arabic list): isolate it so it never reorders.
                    ListItem(
                        headlineContent = { Text(isolate(item.title)) },
                        supportingContent = item.subtitle?.let { { Text(isolate(it)) } },
                    )
                }
            }
        }
    }
}

/** First-strong isolate (FSI…PDI): the text keeps its own direction inside any layout. */
internal fun isolate(text: String) = "\u2068$text\u2069"
