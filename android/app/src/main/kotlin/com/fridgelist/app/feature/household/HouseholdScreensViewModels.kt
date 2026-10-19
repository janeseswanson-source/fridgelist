package com.fridgelist.app.feature.household

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgelist.app.ui.common.ErrorMessages
import com.fridgelist.core.data.auth.AuthRepository
import com.fridgelist.core.data.household.HouseholdRepository
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.model.CreateInviteResponse
import com.fridgelist.core.model.MemberDoc
import com.fridgelist.core.model.Role
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private val WHILE_SUBSCRIBED = SharingStarted.WhileSubscribed(5_000)

data class MembersUiState(
    val members: List<MemberDoc> = emptyList(),
    val myUid: String? = null,
) {
    val myRole: Role? get() = members.firstOrNull { it.uid == myUid }?.role

    /** CONTRACT §4 `createInvite`: owner or adult. */
    val canInvite: Boolean get() = myRole == Role.OWNER || myRole == Role.ADULT
}

data class InviteUiState(
    val role: Role = Role.ADULT,
    val busy: Boolean = false,
    val invite: CreateInviteResponse? = null,
    @param:StringRes val error: Int? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MembersViewModel @Inject constructor(
    private val session: SessionStore,
    private val households: HouseholdRepository,
    auth: AuthRepository,
) : ViewModel() {

    val state: StateFlow<MembersUiState> = combine(
        session.session.map { it.householdId }.distinctUntilChanged().flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else households.members(id).catch { emit(emptyList()) }
        },
        auth.user,
    ) { members, user -> MembersUiState(members, user?.uid) }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, MembersUiState())

    private val _invite = MutableStateFlow<InviteUiState?>(null)

    /** Non-null while the invite sheet is open. */
    val invite: StateFlow<InviteUiState?> = _invite.asStateFlow()

    fun openInvite() {
        _invite.value = InviteUiState()
    }

    fun closeInvite() {
        _invite.value = null
    }

    fun onRoleChange(role: Role) = _invite.update { it?.copy(role = role, invite = null, error = null) }

    fun createInvite() {
        val current = _invite.value ?: return
        if (current.busy) return
        _invite.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            val householdId = session.current().householdId ?: return@launch
            try {
                val response = households.createInvite(householdId, current.role)
                _invite.update { it?.copy(busy = false, invite = response) }
            } catch (e: Exception) {
                _invite.update { it?.copy(busy = false, error = ErrorMessages.general(e)) }
            }
        }
    }
}
