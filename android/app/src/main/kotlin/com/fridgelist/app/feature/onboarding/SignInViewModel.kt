package com.fridgelist.app.feature.onboarding

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgelist.app.R
import com.fridgelist.app.platform.GoogleIdTokens
import com.fridgelist.app.ui.common.ErrorMessages
import com.fridgelist.core.data.auth.AuthRepository
import com.fridgelist.core.data.auth.AuthUser
import com.fridgelist.core.data.device.DeviceRegistry
import com.fridgelist.core.data.firebase.AppEnvironment
import com.fridgelist.core.data.session.OnboardingStep
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.data.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignInUiState(
    val googleAvailable: Boolean,
    val testUserAvailable: Boolean,
    val testUserName: String = "",
    val busy: Boolean = false,
    @param:StringRes val error: Int? = null,
    val next: OnboardingStep? = null,
)

@HiltViewModel
class SignInViewModel @Inject constructor(
    environment: AppEnvironment,
    private val auth: AuthRepository,
    private val users: UserRepository,
    private val session: SessionStore,
    private val devices: DeviceRegistry,
    private val steps: StepResolver,
) : ViewModel() {

    val webClientId: String = environment.googleWebClientId

    private val _state = MutableStateFlow(
        SignInUiState(
            googleAvailable = environment.googleWebClientId.isNotBlank(),
            testUserAvailable = environment.useEmulators,
        ),
    )
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    fun onTestUserNameChange(value: String) = _state.update { it.copy(testUserName = value, error = null) }

    fun onGoogleStarted() = _state.update { it.copy(busy = true, error = null) }

    fun onGoogleResult(result: GoogleIdTokens.Result) {
        when (result) {
            is GoogleIdTokens.Result.Token -> signIn { auth.signInWithGoogleIdToken(result.idToken) }
            GoogleIdTokens.Result.Cancelled -> fail(R.string.error_signin_cancelled)
            GoogleIdTokens.Result.NoAccount -> fail(R.string.error_signin_no_account)
            is GoogleIdTokens.Result.Failed -> fail(ErrorMessages.general(result.error))
        }
    }

    fun signInTestUser() {
        val name = _state.value.testUserName.trim()
        if (name.isEmpty()) return
        signIn { auth.signInEmulatorTestUser(name) }
    }

    private var signingIn = false

    fun onNavigated() = _state.update { it.copy(next = null) }

    private fun signIn(block: suspend () -> AuthUser) {
        if (signingIn) return // the Google flow sets busy first, so guard on the call itself
        signingIn = true
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val user = block()
                restoreHousehold(user)
                launch { devices.register(user.uid) }
                _state.update { it.copy(busy = false, next = steps.next()) }
            } catch (e: Exception) {
                fail(ErrorMessages.general(e))
            } finally {
                signingIn = false
            }
        }
    }

    /** Signing in on a new phone: pick up the household the account already belongs to. */
    private suspend fun restoreHousehold(user: AuthUser) {
        if (session.current().householdId != null) return
        users.householdIdsFromServer(user.uid)?.firstOrNull()?.let { session.setHousehold(it) }
    }

    private fun fail(@StringRes message: Int) = _state.update { it.copy(busy = false, error = message) }
}
