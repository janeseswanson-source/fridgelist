package com.fridgelist.app.feature.settings

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fridgelist.app.BuildConfig
import com.fridgelist.app.R
import com.fridgelist.app.feature.lists.HouseholdContextSource
import com.fridgelist.app.ui.common.ErrorMessages
import com.fridgelist.core.data.account.AccountRepository
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.household.HouseholdRepository
import com.fridgelist.core.model.DeleteMyAccountRequest
import com.fridgelist.core.model.MemberDoc
import com.fridgelist.core.model.Role as MemberRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

enum class DeleteStep { NONE, CONFIRM }

data class AccountUiState(
    val busy: Boolean = false,
    /** Signed URL to open once (CONTRACT §4 `exportMyData`). */
    val exportUrl: String? = null,
    val step: DeleteStep = DeleteStep.NONE,
    /** Owner with other members: who may take over (not kids). */
    val successors: List<MemberDoc> = emptyList(),
    val chosen: String? = null,
    /** The only member: the household and its lists go too. */
    val lastMember: Boolean = false,
    val deleted: Boolean = false,
    @param:StringRes val message: Int? = null,
)

/** Export my data and delete my account (PRODUCT_SPEC F12; required by both stores). */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val contextSource: HouseholdContextSource,
    private val households: HouseholdRepository,
    private val signOut: SignOut,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    fun export() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val url = accounts.exportMyData().url
                _state.update { it.copy(busy = false, exportUrl = url) }
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, message = errorFor(e)) }
            }
        }
    }

    fun onExportOpened() = _state.update { it.copy(exportUrl = null) }

    /** Works out what deleting means here before asking. */
    fun requestDelete() {
        viewModelScope.launch {
            val ctx = withTimeoutOrNull(5_000) { contextSource.context.first() }
            val others = ctx?.let { c -> withTimeoutOrNull(5_000) { households.members(c.householdId).first() }?.filter { it.uid != c.uid } }.orEmpty()
            val successors = if (ctx?.role == MemberRole.OWNER) others.filter { it.role != MemberRole.KID } else emptyList()
            _state.update {
                it.copy(
                    step = DeleteStep.CONFIRM,
                    successors = successors,
                    chosen = successors.singleOrNull()?.uid,
                    lastMember = ctx != null && others.isEmpty(),
                    message = if (ctx?.role == MemberRole.OWNER && others.isNotEmpty() && successors.isEmpty()) R.string.account_delete_no_successor else null,
                )
            }
        }
    }

    fun choose(uid: String) = _state.update { it.copy(chosen = uid) }

    fun cancelDelete() = _state.update { it.copy(step = DeleteStep.NONE, message = null) }

    fun confirmDelete() {
        val s = _state.value
        if (s.busy || (s.successors.isNotEmpty() && s.chosen == null) || s.message == R.string.account_delete_no_successor) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                accounts.deleteMyAccount(DeleteMyAccountRequest(s.chosen.takeIf { s.successors.isNotEmpty() }))
                // The server removed the user; clear this phone (device doc, session, notifications, widgets).
                runCatching { signOut() }
                _state.update { it.copy(busy = false, step = DeleteStep.NONE, deleted = true) }
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, message = errorFor(e)) }
            }
        }
    }

    private fun errorFor(e: ApiException): Int = when (e.error) {
        ApiError.NETWORK, ApiError.UNAVAILABLE -> R.string.account_offline
        ApiError.RESOURCE_EXHAUSTED -> R.string.account_export_limit
        ApiError.FAILED_PRECONDITION -> R.string.account_delete_no_successor
        else -> ErrorMessages.general(e)
    }
}

@Composable
fun AccountRoute(onDeleted: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.exportUrl) {
        state.exportUrl?.let { url ->
            vm.onExportOpened()
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    AccountSection(
        state = state,
        privacyUrl = BuildConfig.PRIVACY_POLICY_URL,
        onPrivacy = { context.startActivity(Intent(Intent.ACTION_VIEW, BuildConfig.PRIVACY_POLICY_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        onExport = vm::export,
        onDelete = vm::requestDelete,
        onChoose = vm::choose,
        onConfirm = vm::confirmDelete,
        onCancel = vm::cancelDelete,
    )
}

/** Stateless for screenshot tests. */
@Composable
fun AccountSection(
    state: AccountUiState,
    privacyUrl: String,
    onPrivacy: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onChoose: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column {
        if (privacyUrl.isNotBlank()) Action(R.string.account_privacy, onPrivacy)
        Action(R.string.account_export, onExport, enabled = !state.busy)
        Action(R.string.account_delete, onDelete, color = MaterialTheme.colorScheme.error)
        if (state.step == DeleteStep.NONE) {
            state.message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        }
    }
    if (state.step == DeleteStep.CONFIRM) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.account_delete_title)) },
            text = {
                Column {
                    Text(stringResource(if (state.lastMember) R.string.account_delete_last else R.string.account_delete_text))
                    if (state.successors.isNotEmpty()) {
                        Text(stringResource(R.string.account_delete_choose), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                        Column(Modifier.selectableGroup()) {
                            state.successors.forEach { member ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .selectable(selected = member.uid == state.chosen, onClick = { onChoose(member.uid) }, role = Role.RadioButton),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = member.uid == state.chosen, onClick = null)
                                    Text(member.displayName, modifier = Modifier.padding(start = 12.dp))
                                }
                            }
                        }
                    }
                    state.message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirm,
                    enabled = !state.busy && state.message != R.string.account_delete_no_successor && (state.successors.isEmpty() || state.chosen != null),
                ) { Text(stringResource(R.string.account_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.share_cancel)) } },
        )
    }
}

@Composable
private fun Action(@StringRes label: Int, onClick: () -> Unit, enabled: Boolean = true, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
        Text(stringResource(label), color = color)
    }
}
