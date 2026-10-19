package com.fridgelist.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgelist.app.feature.onboarding.StepResolver
import com.fridgelist.core.data.auth.AuthRepository
import com.fridgelist.core.data.device.DeviceRegistry
import com.fridgelist.core.data.firebase.AppEnvironment
import com.fridgelist.core.data.items.ListRepository
import com.fridgelist.core.data.session.OnboardingStep
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.model.Links
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/** App-level state: the start screen, and invite links that arrive while the app runs. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val environment: AppEnvironment,
    private val steps: StepResolver,
    auth: AuthRepository,
    devices: DeviceRegistry,
    private val session: SessionStore,
    private val lists: ListRepository,
) : ViewModel() {

    private val _startStep = MutableStateFlow<OnboardingStep?>(null)

    /** Null until the stored session is read (a few ms); then where the app should open. */
    val startStep: StateFlow<OnboardingStep?> = _startStep.asStateFlow()

    private val _pendingShopTrip = MutableStateFlow<String?>(null)

    /** A `fridgelist://shop?tripId=` tap (Live Update / notification) waiting to be opened. */
    val pendingShopTrip: StateFlow<String?> = _pendingShopTrip.asStateFlow()

    fun consumeShopTrip(): String? = _pendingShopTrip.value.also { _pendingShopTrip.value = null }

    private val _pendingJoinCode = MutableStateFlow<String?>(null)
    val pendingJoinCode: StateFlow<String?> = _pendingJoinCode.asStateFlow()

    init {
        viewModelScope.launch { _startStep.value = steps.next() }
        // Refresh the device's push token on every start (CONTRACT §3.2).
        auth.currentUser()?.let { user -> viewModelScope.launch { devices.register(user.uid) } }
    }

    suspend fun currentStep(): OnboardingStep = steps.next()

    /** Handles `/join/{code}` and `{URL_SCHEME}://shop?tripId=` (CONTRACT §6). Other links arrive in A8/A9. */
    fun onLink(url: String?) {
        if (url == null) return
        Links.shopTripId(url, BuildConfig.URL_SCHEME)?.let {
            _pendingShopTrip.value = it
            return
        }
        Links.shareToken(url, environment.linkDomain)?.let {
            _pendingShareToken.value = it
            return
        }
        Links.listLink(url, environment.linkDomain)?.let {
            _pendingListLink.value = it
            return
        }
        Links.joinCode(url, environment.linkDomain)?.let { _pendingJoinCode.value = it }
    }

    fun consumeJoinCode(): String? = _pendingJoinCode.value.also { _pendingJoinCode.value = null }

    private val _pendingShareToken = MutableStateFlow<String?>(null)

    /** A fridge tag or QR (`/k/{token}`), from a tap, a scan or an NFC tag. */
    val pendingShareToken: StateFlow<String?> = _pendingShareToken.asStateFlow()

    fun consumeShareToken(): String? = _pendingShareToken.value.also { _pendingShareToken.value = null }

    private val _pendingListLink = MutableStateFlow<Links.ListLink?>(null)

    /** `/l/{householdId}/{listId}`: open that list when it is this household's. */
    val pendingListLink: StateFlow<Links.ListLink?> = _pendingListLink.asStateFlow()

    fun consumeListLink(): Links.ListLink? = _pendingListLink.value.also { _pendingListLink.value = null }

    suspend fun currentHouseholdId(): String? = session.current().householdId

    private val _pendingGoing = MutableStateFlow(false)

    /** The "I'm going" app shortcut: open the first list with its "I'm going" sheet. */
    val pendingGoing: StateFlow<Boolean> = _pendingGoing.asStateFlow()

    fun consumeGoing(): Boolean = _pendingGoing.value.also { _pendingGoing.value = false }

    fun onIntent(intent: android.content.Intent?) {
        if (intent?.action == ACTION_GOING) _pendingGoing.value = true else onLink(intent?.dataString)
    }

    /** The household's first list (from the offline cache, so this works offline too). */
    suspend fun firstListId(): String? {
        val householdId = session.current().householdId ?: return null
        return withTimeoutOrNull(3_000) { lists.lists(householdId).first().firstOrNull()?.id }
    }

    companion object {
        /** Static shortcut in xml/shortcuts.xml. */
        const val ACTION_GOING = "com.fridgelist.app.action.GOING"
    }
}
