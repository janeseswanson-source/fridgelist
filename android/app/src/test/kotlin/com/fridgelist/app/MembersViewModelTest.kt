package com.fridgelist.app

import app.cash.turbine.test
import com.fridgelist.app.fakes.FakeAuth
import com.fridgelist.app.fakes.FakeHouseholds
import com.fridgelist.app.fakes.FakeSession
import com.fridgelist.app.fakes.MainDispatcherRule
import com.fridgelist.app.feature.household.MembersViewModel
import com.fridgelist.core.data.auth.AuthUser
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.session.Session
import com.fridgelist.core.model.MemberDoc
import com.fridgelist.core.model.Role
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MembersViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun member(uid: String, role: Role) = MemberDoc(uid = uid, role = role, displayName = uid, language = "ar")

    private fun vm(myUid: String, households: FakeHouseholds = FakeHouseholds()): Pair<MembersViewModel, FakeHouseholds> {
        households.members.value = mapOf(
            "h1" to listOf(member("owner", Role.OWNER), member("adult", Role.ADULT), member("helper", Role.HELPER), member("kid", Role.KID)),
        )
        return MembersViewModel(FakeSession(Session("x", "ar", "h1")), households, FakeAuth(AuthUser(myUid, null))) to households
    }

    @Test
    fun ownerAndAdultCanInviteOthersCannot() = runTest {
        for ((uid, expected) in listOf("owner" to true, "adult" to true, "helper" to false, "kid" to false)) {
            vm(uid).first.state.test {
                var state = awaitItem()
                while (state.members.isEmpty()) state = awaitItem()
                assertEquals(expected, state.canInvite, uid)
                assertEquals(4, state.members.size)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun inviteCreatesCodeForChosenRole() {
        val (vm, households) = vm("owner")
        vm.openInvite()
        assertEquals(Role.ADULT, vm.invite.value?.role, "default role")
        vm.onRoleChange(Role.HELPER)
        vm.createInvite()
        assertEquals(listOf("h1" to Role.HELPER), households.invites)
        assertEquals("ABCD2345", vm.invite.value?.invite?.code)
        assertFalse(vm.invite.value!!.busy)

        vm.closeInvite()
        assertNull(vm.invite.value)
    }

    @Test
    fun inviteFailureShowsMessage() {
        val (vm, _) = vm("adult", FakeHouseholds().apply { failWith = ApiException(ApiError.PERMISSION_DENIED) })
        vm.openInvite()
        vm.createInvite()
        assertEquals(R.string.error_permission, vm.invite.value?.error)
        assertTrue(vm.invite.value?.invite == null)
    }
}
