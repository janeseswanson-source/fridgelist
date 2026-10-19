package com.fridgelist.app

import androidx.lifecycle.SavedStateHandle
import com.fridgelist.app.fakes.FakeItems
import com.fridgelist.app.fakes.FakeSession
import com.fridgelist.app.fakes.FakeTripEnder
import com.fridgelist.app.fakes.FakeTripNotifier
import com.fridgelist.app.fakes.FakeTrips
import com.fridgelist.app.fakes.MainDispatcherRule
import com.fridgelist.app.fakes.householdContext
import com.fridgelist.app.feature.shopping.ShoppingViewModel
import com.fridgelist.app.work.EndTripWorker
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.firestore.FieldPatch
import com.fridgelist.core.data.session.ActiveShopping
import com.fridgelist.core.data.session.Session
import com.fridgelist.core.model.ItemDoc
import com.fridgelist.core.model.ItemStatus
import com.fridgelist.core.model.ListDoc
import com.fridgelist.core.model.ListType
import com.fridgelist.core.model.Role
import androidx.work.ListenableWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    private fun items() = FakeItems().apply {
        lists.value = listOf(ListDoc("l1", "Market", ListType.GROCERY))
        setItems(
            ItemDoc(id = "milk", name = "süt", addedBy = "mom", createdAt = 1),
            ItemDoc(id = "bread", name = "ekmek", addedBy = "mom", createdAt = 2),
            ItemDoc(id = "eggs", name = "yumurta", addedBy = "mom", status = ItemStatus.CLAIMED, claimedBy = "mom", createdAt = 3),
        )
    }

    private class Setup(val vm: ShoppingViewModel, val items: FakeItems, val trips: FakeTrips, val ender: FakeTripEnder, val session: FakeSession, val notifier: FakeTripNotifier)

    private fun TestScope.setup(
        trips: FakeTrips = FakeTrips(),
        args: Map<String, Any?> = mapOf("listId" to "l1"),
        session: FakeSession = FakeSession(Session("Ayşe", "tr", "h1", true)),
    ): Setup {
        val items = items()
        val ender = FakeTripEnder()
        val notifier = FakeTripNotifier()
        val vm = ShoppingViewModel(SavedStateHandle(args), householdContext(Role.HELPER, session), items, items, trips, session, ender, notifier)
        vm.state.launchIn(backgroundScope)
        advanceUntilIdle()
        return Setup(vm, items, trips, ender, session, notifier)
    }

    private fun Setup.row(id: String) =
        (vm.state.value.toBuy.flatMap { it.second } + vm.state.value.claimedByOthers + vm.state.value.inCart + vm.state.value.missing)
            .first { it.item.id == id }

    @Test
    fun startsATripAndTicksCarryTheTripId() = runTest(main.dispatcher) {
        val s = setup()
        val request = s.trips.started.single()
        assertEquals("shopping", request.status)
        assertEquals(listOf("l1"), request.listIds)
        assertEquals("android", request.platform)
        assertFalse(request.auto)
        assertFalse(s.vm.state.value.localMode)
        assertEquals(ActiveShopping("l1", "t1"), s.session.state.value.shopping)

        s.vm.tick(s.row("milk"))
        runCurrent()
        val patch = s.items.updates.last().second
        assertEquals(FieldPatch.Set("done"), patch["status"])
        assertEquals(FieldPatch.Set("t1"), patch["doneTripId"])
        assertEquals(listOf("milk"), s.vm.state.value.inCart.map { it.item.id })
    }

    @Test
    fun ongoingNotificationFollowsTheCart() = runTest(main.dispatcher) {
        val s = setup()
        val first = s.notifier.shown.last()
        assertEquals("t1", first.tripId)
        assertEquals(3, first.total)
        assertEquals(0, first.done)
        assertEquals(listOf("ekmek", "süt"), first.nextItems, "next items in aisle order, others' claims left out")
        s.vm.tick(s.row("milk"))
        runCurrent()
        assertEquals(1, s.notifier.shown.last().done)
        s.vm.finish()
        advanceUntilIdle()
        assertTrue(s.notifier.cancelled > 0)
    }

    @Test
    fun arrivingOnAHeadingTripSwitchesItToShopping() = runTest(main.dispatcher) {
        val s = setup(session = FakeSession(Session("Ayşe", "tr", "h1", true, ActiveShopping("l1", "t-going", heading = true, storeName = "Migros"))))
        assertEquals(listOf("t-going" to "shopping"), s.trips.updates.map { it.tripId to it.status })
        assertEquals(ActiveShopping("l1", "t-going", heading = false, storeName = "Migros"), s.session.state.value.shopping)
        assertEquals("Migros", s.notifier.shown.last().storeName)
    }

    @Test
    fun undoTakesTheItemOutOfTheCart() = runTest(main.dispatcher) {
        val s = setup()
        s.vm.tick(s.row("milk"))
        runCurrent()
        s.vm.settle(undone = true)
        runCurrent()
        assertTrue(s.vm.state.value.inCart.isEmpty())
        assertEquals(ItemStatus.OPEN, s.row("milk").item.status)
    }

    @Test
    fun othersClaimsAreSeparate() = runTest(main.dispatcher) {
        val s = setup()
        assertEquals(listOf("eggs"), s.vm.state.value.claimedByOthers.map { it.item.id })
        // Aisle order (CONTRACT §2): bakery (ekmek) before dairy (süt).
        assertEquals(listOf("bread", "milk"), s.vm.state.value.toBuy.flatMap { it.second }.map { it.item.id })
    }

    @Test
    fun finishWithTripEndsItOnTheServerAndLeavesMissingToIt() = runTest(main.dispatcher) {
        val s = setup()
        s.vm.tick(s.row("milk"))
        s.vm.markMissing(s.row("bread"))
        runCurrent()
        val writesBefore = s.items.updates.size
        s.vm.finish()
        advanceUntilIdle()
        val summary = checkNotNull(s.vm.state.value.summary)
        assertEquals(listOf("milk"), summary.bought.map { it.item.id })
        assertEquals(listOf("bread"), summary.missing.map { it.item.id })
        assertEquals(setOf("bread", "eggs"), summary.rest.map { it.item.id }.toSet())
        assertEquals(listOf("h1" to "t1"), s.ender.ended)
        assertEquals(writesBefore, s.items.updates.size, "the server reopens missing items, not the app")
        assertNull(s.session.state.value.shopping)
    }

    @Test
    fun offlineShoppingWorksLocallyAndReopensMissingItself() = runTest(main.dispatcher) {
        val s = setup(trips = FakeTrips(failWith = ApiException(ApiError.NETWORK)))
        assertTrue(s.vm.state.value.localMode)
        assertEquals(ActiveShopping("l1", null), s.session.state.value.shopping)

        s.vm.tick(s.row("milk"))
        runCurrent()
        assertFalse("doneTripId" in s.items.updates.last().second)
        assertEquals(listOf("milk"), s.vm.state.value.inCart.map { it.item.id }, "in the cart without a trip")

        s.vm.markMissing(s.row("bread"))
        runCurrent()
        s.vm.finish()
        advanceUntilIdle()
        assertTrue(s.ender.ended.isEmpty())
        assertEquals("bread" to FieldPatch.Set("open"), s.items.updates.last().let { it.first to it.second["status"] })
        assertEquals(1, s.vm.state.value.summary?.bought?.size)
    }

    @Test
    fun resumesTheSameTripInsteadOfStartingAnother() = runTest(main.dispatcher) {
        val s = setup(session = FakeSession(Session("Ayşe", "tr", "h1", true, ActiveShopping("l1", "t-old"))))
        assertTrue(s.trips.started.isEmpty())
        s.vm.tick(s.row("milk"))
        runCurrent()
        assertEquals(FieldPatch.Set("t-old"), s.items.updates.last().second["doneTripId"])
    }

    @Test
    fun liveUpdateLinkFindsTheTripsList() = runTest(main.dispatcher) {
        val s = setup(trips = FakeTrips(tripLists = mapOf("t9" to listOf("l1"))), args = mapOf("tripId" to "t9"))
        assertTrue(s.trips.started.isEmpty())
        assertEquals("Market", s.vm.state.value.listName)
        assertEquals(ActiveShopping("l1", "t9"), s.session.state.value.shopping)
    }

    @Test
    fun unknownTripSaysItEnded() = runTest(main.dispatcher) {
        val s = setup(args = mapOf("tripId" to "t-gone"))
        assertTrue(s.vm.tripGone.value)
        assertTrue(s.trips.started.isEmpty(), "does not start a new trip from a stale link")
    }

    @Test
    fun endTripRetriesOnlyWhenItCanHelp() {
        assertTrue(EndTripWorker.decide(ApiError.NETWORK, 0) is ListenableWorker.Result.Retry)
        assertTrue(EndTripWorker.decide(ApiError.FAILED_PRECONDITION, 0) is ListenableWorker.Result.Success, "already ended")
        assertTrue(EndTripWorker.decide(ApiError.NOT_FOUND, 0) is ListenableWorker.Result.Success)
        assertTrue(EndTripWorker.decide(ApiError.NETWORK, 8) is ListenableWorker.Result.Failure, "gives up eventually")
    }

    // --- QA edge cases (2026-10-19): process death

    @Test
    fun restoredAfterFinishingNeverStartsANewTrip() = runTest(main.dispatcher) {
        val s = setup(args = mapOf("listId" to "l1", "finished" to true))
        assertTrue(s.trips.started.isEmpty(), "no second trip")
        assertTrue(s.notifier.shown.isEmpty(), "no ongoing notification")
        assertTrue(s.vm.tripGone.value)
    }

    @Test
    fun localModeCartSurvivesProcessDeath() = runTest(main.dispatcher) {
        val handle = SavedStateHandle(mapOf("listId" to "l1"))
        val items = items()
        val session = FakeSession(Session("Ayşe", "tr", "h1", true))
        val offline = FakeTrips(failWith = ApiException(ApiError.NETWORK))
        fun vm() = ShoppingViewModel(handle, householdContext(Role.HELPER, session), items, items, offline, session, FakeTripEnder(), FakeTripNotifier())
            .also { it.state.launchIn(backgroundScope); advanceUntilIdle() }
        val first = vm()
        first.tick(first.state.value.toBuy.flatMap { it.second }.first { it.item.id == "milk" })
        advanceUntilIdle()
        assertEquals(listOf("milk"), first.state.value.inCart.map { it.item.id })

        // The system restores the same SavedStateHandle into a new ViewModel.
        val restored = vm()
        assertTrue(restored.state.value.localMode)
        assertEquals(listOf("milk"), restored.state.value.inCart.map { it.item.id })
    }

    @Test
    fun doneFromTheNotificationStopsTheOpenScreenFromRepostingIt() = runTest(main.dispatcher) {
        val s = setup()
        val shownBefore = s.notifier.shown.size
        // TripActionReceiver: Done tapped on the lock screen while Shopping Mode is open.
        s.session.endShopping()
        s.items.setItems(ItemDoc(id = "jam", name = "reçel", addedBy = "mom", createdAt = 9))
        advanceUntilIdle()
        assertEquals(shownBefore, s.notifier.shown.size, "not posted again")
        assertTrue(s.vm.tripGone.value)
    }

    @Test
    fun kidsNeverStartATrip() = runTest(main.dispatcher) {
        val items = items()
        // A trip saved before the role guard existed: resuming it must clear it, not start one.
        val session = FakeSession(Session("Ali", "ar", "h1", true, ActiveShopping("l1", "t-old")))
        val trips = FakeTrips()
        val vm = ShoppingViewModel(SavedStateHandle(mapOf("listId" to "l1")), householdContext(Role.KID, session), items, items, trips, session, FakeTripEnder(), FakeTripNotifier())
        vm.state.launchIn(backgroundScope)
        advanceUntilIdle()
        assertTrue(trips.started.isEmpty())
        assertNull(session.state.value.shopping)
        assertTrue(vm.tripGone.value)
    }
}
