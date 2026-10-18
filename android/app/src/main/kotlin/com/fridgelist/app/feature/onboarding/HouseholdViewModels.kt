package com.fridgelist.app.feature.onboarding

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgelist.app.R
import com.fridgelist.app.ui.common.ErrorMessages
import com.fridgelist.core.data.firebase.AppEnvironment
import com.fridgelist.core.data.household.HouseholdRepository
import com.fridgelist.core.data.session.OnboardingStep
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.model.CreateHouseholdRequest
import com.fridgelist.core.model.InviteCode
import com.fridgelist.core.model.JoinHouseholdRequest
import com.fridgelist.core.model.Limits
import com.fridgelist.core.model.Links
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreateHouseholdUiState(
    val name: String = "",
    val defaultLanguage: String = "",
    val nameInvalid: Boolean = false,
    val busy: Boolean = false,
    @param:StringRes val error: Int? = null,
    val next: OnboardingStep? = null,
)

/** `createHousehold` (CONTRACT §4). Needs the network; shows a clear message when offline. */
@HiltViewModel
class CreateHouseholdViewModel @Inject constructor(
    private val households: HouseholdRepository,
    private val session: SessionStore,
    private val steps: StepResolver,
    private val uiLanguage: UiLanguageProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(CreateHouseholdUiState(defaultLanguage = uiLanguage.current()))
    val state: StateFlow<CreateHouseholdUiState> = _state.asStateFlow()

    fun onNameChange(value: String) = _state.update { it.copy(name = value, nameInvalid = false, error = null) }

    fun onLanguageChange(tag: String) = _state.update { it.copy(defaultLanguage = tag) }

    fun create() {
        val s = _state.value
        val name = s.name.trim()
        if (!isValidName(name, Limits.HOUSEHOLD_NAME_MAX)) {
            _state.update { it.copy(nameInvalid = true) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val profile = session.current()
                val response = households.createHousehold(
                    CreateHouseholdRequest(
                        name = name,
                        defaultLanguage = s.defaultLanguage,
                        displayName = profile.profileName.orEmpty(),
                        language = profile.itemLanguage ?: s.defaultLanguage,
                        uiLanguage = uiLanguage.current(),
                    ),
                )
                session.setHousehold(response.householdId)
                _state.update { it.copy(busy = false, next = steps.next()) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = ErrorMessages.general(e)) }
            }
        }
    }

    fun onNavigated() = _state.update { it.copy(next = null) }
}

data class JoinHouseholdUiState(
    val code: String = "",
    val codeInvalid: Boolean = false,
    val busy: Boolean = false,
    @param:StringRes val error: Int? = null,
    val next: OnboardingStep? = null,
)

/** `joinHousehold` (CONTRACT §4) by typed code, scanned QR, or `/join/{code}` link. */
@HiltViewModel
class JoinHouseholdViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val environment: AppEnvironment,
    private val households: HouseholdRepository,
    private val session: SessionStore,
    private val steps: StepResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(JoinHouseholdUiState(code = savedState.get<String>("code").orEmpty()))
    val state: StateFlow<JoinHouseholdUiState> = _state.asStateFlow()

    fun onCodeChange(value: String) {
        val code = InviteCode.normalize(value).take(InviteCode.LENGTH)
        _state.update { it.copy(code = code, codeInvalid = false, error = null) }
    }

    fun onScanned(text: String) {
        val code = Links.joinCode(text, environment.linkDomain)
        if (code == null) {
            _state.update { it.copy(error = R.string.join_scan_not_invite) }
        } else {
            _state.update { it.copy(code = code, codeInvalid = false, error = null) }
            join()
        }
    }

    fun join() {
        val code = _state.value.code
        if (!InviteCode.isValid(code)) {
            _state.update { it.copy(codeInvalid = true) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val profile = session.current()
                val response = households.joinHousehold(
                    JoinHouseholdRequest(code, profile.profileName.orEmpty(), profile.itemLanguage ?: "en"),
                )
                session.setHousehold(response.householdId)
                _state.update { it.copy(busy = false, next = steps.next()) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = ErrorMessages.forJoin(e)) }
            }
        }
    }

    fun onNavigated() = _state.update { it.copy(next = null) }
}

/** The notification step only records that it was shown; the permission dialog is in the UI. */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val session: SessionStore,
    private val steps: StepResolver,
) : ViewModel() {
    private val _next = MutableStateFlow<OnboardingStep?>(null)
    val next: StateFlow<OnboardingStep?> = _next.asStateFlow()

    fun finish() {
        viewModelScope.launch {
            session.markNotificationsAsked()
            _next.value = steps.next()
        }
    }

    fun onNavigated() {
        _next.value = null
    }
}
