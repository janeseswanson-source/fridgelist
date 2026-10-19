package com.fridgelist.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fridgelist.app.fakes.FakeAuth
import com.fridgelist.app.fakes.FakeDetectionStore
import com.fridgelist.app.fakes.FakeDevices
import com.fridgelist.app.fakes.FakeFences
import com.fridgelist.app.fakes.FakeHouseholds
import com.fridgelist.app.fakes.FakeSession
import com.fridgelist.app.fakes.MainDispatcherRule
import com.fridgelist.app.feature.lists.HouseholdContextSource
import com.fridgelist.app.feature.settings.AccountViewModel
import com.fridgelist.app.feature.settings.DeleteStep
import com.fridgelist.app.feature.settings.SignOut
import com.fridgelist.core.data.account.AccountRepository
import com.fridgelist.core.data.auth.AuthUser
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.session.Session
import com.fridgelist.core.model.DeleteMyAccountRequest
import com.fridgelist.core.model.ExportMyDataResponse
import com.fridgelist.core.model.HouseholdDoc
import com.fridgelist.core.model.MemberDoc
import com.fridgelist.core.model.Role
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AccountTest {

    @get:Rule val main = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeAccounts(var fail: ApiError? = null) : AccountRepository {
        val deletes = mutableListOf<DeleteMyAccountRequest>()
        var exports = 0
        override suspend fun exportMyData(): ExportMyDataResponse {
            fail?.let { throw ApiException(it, "x") }
            exports++
            return ExportMyDataResponse("https://signed.example/export.json")
        }
        override suspend fun deleteMyAccount(request: DeleteMyAccountRequest) {
            fail?.let { throw ApiException(it, "x") }
            deletes += request
        }
    }

    private class Setup(myRole: Role, others: List<MemberDoc>, val accounts: FakeAccounts = FakeAccounts()) {
        val session = FakeSession(Session("Ayşe", "tr", "h1", true))
        val households = FakeHouseholds().apply {
            members.value = mapOf("h1" to listOf(MemberDoc("me", myRole, "Ayşe", "tr")) + others)
            this.households.value = mapOf("h1" to HouseholdDoc("h1", "Ev", "me", "tr"))
        }
        val auth = FakeAuth(AuthUser("me", "Ayşe"))
        var widgetsCleared = 0
        fun vm(context: Context): AccountViewModel {
            val contextSource = HouseholdContextSource(session, auth, households)
            val signOut = SignOut(auth, session, FakeDevices(), FakeDetectionStore(), FakeFences(), { widgetsCleared++ }, context)
            return AccountViewModel(accounts, contextSource, households, signOut)
        }
    }

    @Test
    fun exportOpensTheSignedLinkOnce() = runTest(main.dispatcher) {
        val s = Setup(Role.ADULT, emptyList())
        val vm = s.vm(context)
        vm.export()
        assertEquals("https://signed.example/export.json", vm.state.value.exportUrl)
        vm.onExportOpened()
        assertNull(vm.state.value.exportUrl)
        s.accounts.fail = ApiError.RESOURCE_EXHAUSTED
        vm.export()
        assertEquals(R.string.account_export_limit, vm.state.value.message)
    }

    @Test
    fun anOwnerWithOthersHandsOverToAnAdult() = runTest(main.dispatcher) {
        val s = Setup(Role.OWNER, listOf(MemberDoc("kid", Role.KID, "Ali", "ar"), MemberDoc("mom", Role.ADULT, "Mama", "ar")))
        val vm = s.vm(context)
        vm.requestDelete()
        assertEquals(DeleteStep.CONFIRM, vm.state.value.step)
        assertEquals(listOf("mom"), vm.state.value.successors.map { it.uid }, "kids can't take over")
        assertEquals("mom", vm.state.value.chosen, "the only choice is preselected")
        vm.confirmDelete()
        assertEquals(DeleteMyAccountRequest("mom"), s.accounts.deletes.single())
        assertTrue(vm.state.value.deleted)
        assertNull(s.session.state.value.householdId, "this phone is signed out")
        assertEquals(1, s.widgetsCleared)
    }

    @Test
    fun anOwnerWithOnlyKidsCantDeleteYet() = runTest(main.dispatcher) {
        val s = Setup(Role.OWNER, listOf(MemberDoc("kid", Role.KID, "Ali", "ar")))
        val vm = s.vm(context)
        vm.requestDelete()
        assertEquals(R.string.account_delete_no_successor, vm.state.value.message)
        vm.confirmDelete()
        assertTrue(s.accounts.deletes.isEmpty())
    }

    @Test
    fun theLastMemberDeletesTheHouseholdToo() = runTest(main.dispatcher) {
        val s = Setup(Role.OWNER, emptyList())
        val vm = s.vm(context)
        vm.requestDelete()
        assertTrue(vm.state.value.lastMember)
        vm.confirmDelete()
        assertEquals(DeleteMyAccountRequest(null), s.accounts.deletes.single())
    }

    @Test
    fun anAdultJustLeavesAndOfflineSaysSo() = runTest(main.dispatcher) {
        val s = Setup(Role.ADULT, listOf(MemberDoc("mom", Role.OWNER, "Mama", "ar")), FakeAccounts(ApiError.NETWORK))
        val vm = s.vm(context)
        vm.requestDelete()
        assertTrue(vm.state.value.successors.isEmpty())
        vm.confirmDelete()
        assertEquals(R.string.account_offline, vm.state.value.message)
        assertTrue(!vm.state.value.deleted)
        assertEquals("h1", s.session.state.value.householdId, "nothing cleared when the server said no")
    }
}
