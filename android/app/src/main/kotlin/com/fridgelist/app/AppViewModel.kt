package com.fridgelist.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgelist.app.feature.onboarding.StepResolver
import com.fridgelist.core.data.auth.AuthRepository
import com.fridgelist.core.data.device.DeviceRegistry
import com.fridgelist.core.data.firebase.AppEnvironment
import com.fridgelist.core.data.session.OnboardingStep
import com.fridgelist.core.model.Links
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** App-level state: the start screen, and invite links that arrive while the app runs. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val environment: AppEnvironment,
    private val steps: StepResolver,
    auth: AuthRepository,
    devices: DeviceRegistry,
) : ViewModel() {

    private val _startStep = MutableStateFlow<OnboardingStep?>(null)

    /** Null until the stored session is read (a few ms); then where the app should open. */
    val startStep: StateFlow<OnboardingStep?> = _startStep.asStateFlow()

    private val _pendingJoinCode = MutableStateFlow<String?>(null)
    val pendingJoinCode: StateFlow<String?> = _pendingJoinCode.asStateFlow()

    init {
        viewModelScope.launch { _startStep.value = steps.next() }
        // Refresh the device's push token on every start (CONTRACT §3.2).
        auth.currentUser()?.let { user -> viewModelScope.launch { devices.register(user.uid) } }
    }

    suspend fun currentStep(): OnboardingStep = steps.next()

    /** Handles `https://{LINK_DOMAIN}/join/{code}` (CONTRACT §6). Other links arrive in A9. */
    fun onLink(url: String?) {
        if (url == null) return
        Links.joinCode(url, environment.linkDomain)?.let { _pendingJoinCode.value = it }
    }

    fun consumeJoinCode(): String? = _pendingJoinCode.value.also { _pendingJoinCode.value = null }
}
