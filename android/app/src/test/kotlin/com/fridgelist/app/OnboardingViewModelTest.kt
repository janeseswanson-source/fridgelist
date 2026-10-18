package com.fridgelist.app

import androidx.lifecycle.SavedStateHandle
import com.fridgelist.app.fakes.FakeAuth
import com.fridgelist.app.fakes.FakeDevices
import com.fridgelist.app.fakes.FakeHouseholds
import com.fridgelist.app.fakes.FakeSession
import com.fridgelist.app.fakes.FakeUsers
import com.fridgelist.app.fakes.MainDispatcherRule
import com.fridgelist.app.fakes.steps
import com.fridgelist.app.fakes.uiLanguage
import com.fridgelist.app.feature.onboarding.CreateHouseholdViewModel
import com.fridgelist.app.feature.onboarding.JoinHouseholdViewModel
import com.fridgelist.app.feature.onboarding.NotificationsViewModel
import com.fridgelist.app.feature.onboarding.ProfileViewModel
import com.fridgelist.app.feature.onboarding.SignInViewModel
import com.fridgelist.app.platform.GoogleIdTokens
import com.fridgelist.core.data.auth.AuthUser
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.firebase.AppEnvironment
import com.fridgelist.core.data.session.OnboardingStep
import com.fridgelist.core.data.session.Session
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val env = AppEnvironment(useEmulators = true, linkDomain = "links.example.com")
    private val signedIn = AuthUser("u1", "Yasser")

    @Test
    fun testUserSignInRestoresHouseholdFromServerAndRegistersDevice() {
        val auth = FakeAuth()
        val session = FakeSession()
        val devices = FakeDevices()
        val vm = SignInViewModel(env, auth, FakeUsers(mapOf("test-Mama" to listOf("h9"))), session, devices, steps(auth, session))
        assertTrue(vm.state.value.testUserAvailable)
        assertFalse(vm.state.value.googleAvailable, "no Web client ID configured")

        vm.onTestUserNameChange("Mama")
        vm.signInTestUser()

        assertEquals("h9", session.state.value.householdId)
        assertEquals(listOf("test-Mama"), devices.registered)
        assertEquals(OnboardingStep.PROFILE, vm.state.value.next, "profile is still asked on a new phone")
    }

    @Test
    fun googleCancelShowsMessageAndKeepsUserSignedOut() {
        val auth = FakeAuth()
        val session = FakeSession()
        val vm = SignInViewModel(env, auth, FakeUsers(), session, FakeDevices(), steps(auth, session))
        vm.onGoogleStarted()
        vm.onGoogleResult(GoogleIdTokens.Result.Cancelled)
        assertEquals(R.string.error_signin_cancelled, vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertNull(auth.currentUser())
    }

    @Test
    fun profileValidatesNameAndSavesOffline() {
        val auth = FakeAuth(signedIn)
        val session = FakeSession()
        val users = FakeUsers()
        val vm = ProfileViewModel(auth, users, session, steps(auth, session), uiLanguage("tr"))
        assertEquals("Yasser", vm.state.value.name, "prefilled from the account")
        assertEquals("tr", vm.state.value.itemLanguage, "defaults to the UI language")

        vm.onNameChange("   ")
        vm.save()
        assertTrue(vm.state.value.nameInvalid)

        vm.onNameChange(" Ayşe ")
        vm.onLanguageChange("fil")
        vm.save()
        assertEquals(Session(profileName = "Ayşe", itemLanguage = "fil"), session.state.value)
        assertEquals(listOf(listOf("u1", "Ayşe", "fil", "tr")), users.profiles)
        assertEquals(OnboardingStep.HOUSEHOLD, vm.state.value.next)
    }

    @Test
    fun nameLongerThan40IsRejected() {
        val auth = FakeAuth(signedIn)
        val session = FakeSession()
        val vm = ProfileViewModel(auth, FakeUsers(), session, steps(auth, session), uiLanguage("ar"))
        vm.onNameChange("x".repeat(41))
        vm.save()
        assertTrue(vm.state.value.nameInvalid)
    }

    @Test
    fun createHouseholdSendsContractRequest() {
        val auth = FakeAuth(signedIn)
        val session = FakeSession(Session("Yasser", "ar"))
        val households = FakeHouseholds()
        val vm = CreateHouseholdViewModel(households, session, steps(auth, session), uiLanguage("ar"))
        vm.onNameChange("بيتنا")
        vm.onLanguageChange("tr")
        vm.create()

        val request = households.created.single()
        assertEquals("بيتنا", request.name)
        assertEquals("tr", request.defaultLanguage)
        assertEquals("Yasser", request.displayName)
        assertEquals("ar", request.language)
        assertEquals("ar", request.uiLanguage)
        assertEquals("h-new", session.state.value.householdId)
        assertEquals(OnboardingStep.NOTIFICATIONS, vm.state.value.next)
    }

    @Test
    fun createHouseholdOfflineShowsClearMessage() {
        val auth = FakeAuth(signedIn)
        val session = FakeSession(Session("Yasser", "ar"))
        val households = FakeHouseholds().apply { failWith = ApiException(ApiError.NETWORK) }
        val vm = CreateHouseholdViewModel(households, session, steps(auth, session), uiLanguage("ar"))
        vm.onNameChange("Home")
        vm.create()
        assertEquals(R.string.error_network, vm.state.value.error)
        assertNull(session.state.value.householdId)
        assertNull(vm.state.value.next)
    }

    private fun joinVm(households: FakeHouseholds, session: FakeSession, code: String? = null): JoinHouseholdViewModel {
        val auth = FakeAuth(signedIn)
        return JoinHouseholdViewModel(SavedStateHandle(mapOf("code" to code)), env, households, session, steps(auth, session))
    }

    @Test
    fun joinNormalizesTypedCodeAndJoins() {
        val session = FakeSession(Session("Ayşe", "tr"))
        val households = FakeHouseholds()
        val vm = joinVm(households, session)
        vm.onCodeChange("abcd-2345")
        assertEquals("ABCD2345", vm.state.value.code)
        vm.join()
        assertEquals("ABCD2345", households.joined.single().code)
        assertEquals("tr", households.joined.single().language)
        assertEquals("h-joined", session.state.value.householdId)
        assertEquals(OnboardingStep.NOTIFICATIONS, vm.state.value.next)
    }

    @Test
    fun joinRejectsShortCodeWithoutCallingServer() {
        val households = FakeHouseholds()
        val vm = joinVm(households, FakeSession(Session("A", "ar")))
        vm.onCodeChange("ABC")
        vm.join()
        assertTrue(vm.state.value.codeInvalid)
        assertTrue(households.joined.isEmpty())
    }

    @Test
    fun joinErrorsMapToInviteMessages() {
        val households = FakeHouseholds().apply { failWith = ApiException(ApiError.NOT_FOUND) }
        val vm = joinVm(households, FakeSession(Session("A", "ar")), code = "ABCD2345")
        vm.join()
        assertEquals(R.string.error_invite_not_found, vm.state.value.error)

        households.failWith = ApiException(ApiError.FAILED_PRECONDITION)
        vm.join()
        assertEquals(R.string.error_invite_expired, vm.state.value.error)
    }

    @Test
    fun scannedInviteLinkJoinsAndOtherQrIsRejected() {
        val households = FakeHouseholds()
        val vm = joinVm(households, FakeSession(Session("A", "ar")))
        vm.onScanned("https://example.org/menu")
        assertEquals(R.string.join_scan_not_invite, vm.state.value.error)
        assertTrue(households.joined.isEmpty())

        vm.onScanned("https://links.example.com/join/WXYZ6789")
        assertEquals("WXYZ6789", households.joined.single().code)
    }

    @Test
    fun linkCodeArrivesPrefilled() {
        val vm = joinVm(FakeHouseholds(), FakeSession(), code = "ABCD2345")
        assertEquals("ABCD2345", vm.state.value.code)
    }

    @Test
    fun notificationStepFinishesOnboarding() {
        val auth = FakeAuth(signedIn)
        val session = FakeSession(Session("A", "ar", "h1"))
        val vm = NotificationsViewModel(session, steps(auth, session))
        vm.finish()
        assertTrue(session.state.value.notificationsAsked)
        assertEquals(OnboardingStep.DONE, vm.next.value)
    }
}
