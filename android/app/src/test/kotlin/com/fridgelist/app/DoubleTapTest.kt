package com.fridgelist.app

import com.fridgelist.app.fakes.FakeAuth
import com.fridgelist.app.fakes.FakeHouseholds
import com.fridgelist.app.fakes.FakeSession
import com.fridgelist.app.fakes.MainDispatcherRule
import com.fridgelist.app.fakes.steps
import com.fridgelist.app.fakes.uiLanguage
import com.fridgelist.app.feature.onboarding.CreateHouseholdViewModel
import com.fridgelist.app.feature.onboarding.JoinHouseholdViewModel
import com.fridgelist.core.data.auth.AuthUser
import com.fridgelist.core.data.firebase.AppEnvironment
import com.fridgelist.core.data.session.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** QA 2026-10-19: two taps before the button disables must not send two requests. */
@OptIn(ExperimentalCoroutinesApi::class)
class DoubleTapTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun createHouseholdTwiceCreatesOne() = runTest(main.dispatcher) {
        val auth = FakeAuth(AuthUser("u1", "Yasser"))
        val session = FakeSession(Session("Yasser", "ar"))
        val households = FakeHouseholds()
        val vm = CreateHouseholdViewModel(households, session, steps(auth, session), uiLanguage("ar"))
        vm.onNameChange("بيتنا")
        vm.create()
        vm.create()
        advanceUntilIdle()
        assertEquals(1, households.created.size)
    }

    @Test
    fun joinTwiceJoinsOnce() = runTest(main.dispatcher) {
        val auth = FakeAuth(AuthUser("u1", "Yasser"))
        val session = FakeSession(Session("Yasser", "ar"))
        val households = FakeHouseholds()
        val env = AppEnvironment(useEmulators = true, linkDomain = "links.example.com")
        val vm = JoinHouseholdViewModel(androidx.lifecycle.SavedStateHandle(mapOf("code" to "ABCD2345")), env, households, session, steps(auth, session))
        vm.join()
        vm.join()
        advanceUntilIdle()
        assertEquals(1, households.joined.size)
    }
}
