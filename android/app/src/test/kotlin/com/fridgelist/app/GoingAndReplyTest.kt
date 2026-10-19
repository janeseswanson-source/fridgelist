package com.fridgelist.app

import androidx.lifecycle.SavedStateHandle
import com.fridgelist.app.fakes.FakeItems
import com.fridgelist.app.fakes.FakeSession
import com.fridgelist.app.fakes.FakeTripNotifier
import com.fridgelist.app.fakes.FakeTrips
import com.fridgelist.app.fakes.MainDispatcherRule
import com.fridgelist.app.fakes.householdContext
import com.fridgelist.app.feature.going.GoingViewModel
import com.fridgelist.app.notifications.NotificationReplyHandler
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.firestore.FieldPatch
import com.fridgelist.core.data.session.ActiveShopping
import com.fridgelist.core.data.session.Session
import com.fridgelist.core.data.trips.StoreRepository
import com.fridgelist.core.model.NearbyStore
import com.fridgelist.core.model.NearbyStoresRequest
import com.fridgelist.core.model.NearbyStoresResponse
import com.fridgelist.core.model.Role
import com.fridgelist.core.model.TripStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GoingAndReplyTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    // --- Inline reply (CONTRACT §5.1)

    @Test
    fun replyIsSplitAndWrittenAsNotificationReply() = runTest(main.dispatcher) {
        val items = FakeItems()
        val handler = NotificationReplyHandler(householdContext(Role.ADULT), items, items)
        assertEquals(2, handler.add("h1", "l1", "2 süt ve labne"))
        assertTrue(items.added.all { it["addedVia"] == FieldPatch.Set("notification_reply") })
        assertEquals(FieldPatch.Set("open"), items.added.first()["status"])
        assertEquals(FieldPatch.Set(2.0), items.added.first()["qty"])
    }

    @Test
    fun kidsRepliesBecomeRequests() = runTest(main.dispatcher) {
        val items = FakeItems()
        NotificationReplyHandler(householdContext(Role.KID), items, items).add("h1", "l1", "chips")
        assertEquals(FieldPatch.Set("pending"), items.added.single()["status"])
    }

    @Test
    fun replyForAnotherHouseholdOrEmptyIsIgnored() = runTest(main.dispatcher) {
        val items = FakeItems()
        val handler = NotificationReplyHandler(householdContext(Role.ADULT), items, items)
        assertEquals(0, handler.add("h-other", "l1", "milk"))
        assertEquals(0, handler.add("h1", "l1", "  "))
        assertTrue(items.added.isEmpty())
    }

    // --- "I'm going"

    private fun TestScope.going(trips: FakeTrips, notifier: FakeTripNotifier, session: FakeSession, here: Pair<Double, Double>? = 41.0 to 29.0): GoingViewModel {
        val stores = (1..7).map { NearbyStore("s$it", "Migros $it", "migros", 41.0, 29.0, it * 300.0) }
        return GoingViewModel(SavedStateHandle(mapOf("listId" to "l1")), householdContext(Role.ADULT, session), com.fridgelist.app.fakes.FakeStoreRepository(stores), trips, session, notifier) { here }
            .also { advanceUntilIdle() }
    }

    @Test
    fun nearestFiveStoresThenHeadingTripWithLastCall() = runTest(main.dispatcher) {
        val trips = FakeTrips(tripId = "t-go")
        val notifier = FakeTripNotifier()
        val session = FakeSession(Session("Ayşe", "tr", "h1", true))
        val vm = going(trips, notifier, session)
        vm.loadNearby()
        advanceUntilIdle()
        assertEquals(5, vm.state.value.nearby.size, "nearest five (prompt A6)")

        vm.pickStore(vm.state.value.nearby.first())
        vm.onLastCall(15)
        vm.onLastCall(7) // not an allowed value: ignored
        vm.send()
        advanceUntilIdle()

        val request = trips.started.single()
        assertEquals("heading", request.status)
        assertEquals("s1", request.storeId)
        assertEquals("Migros 1", request.storeName)
        assertEquals(15, request.lastCallMinutes)
        assertEquals(listOf("l1"), request.listIds)
        assertEquals(ActiveShopping("l1", "t-go", heading = true, storeName = "Migros 1"), session.state.value.shopping)
        assertEquals(TripStatus.HEADING, notifier.shown.single().status)
        assertTrue(notifier.shown.single().lastCallUntil != null)
        assertTrue(vm.state.value.sent)
    }

    @Test
    fun withoutLocationChainChipsAndFreeText() = runTest(main.dispatcher) {
        assertEquals(listOf("Migros", "A101", "BİM", "ŞOK", "CarrefourSA"), GoingViewModel.chainsFor("tr"))
        assertEquals("Panda", GoingViewModel.chainsFor("SA").first())
        val trips = FakeTrips()
        val vm = going(trips, FakeTripNotifier(), FakeSession(Session("Ayşe", "tr", "h1", true)), here = null)
        vm.loadNearby()
        advanceUntilIdle()
        assertEquals(R.string.going_no_stores, vm.state.value.message)
        vm.onStoreText("Bakkal Ahmet")
        vm.onLastCall(0)
        vm.send()
        advanceUntilIdle()
        assertNull(trips.started.single().storeId)
        assertEquals("Bakkal Ahmet", trips.started.single().storeName)
        assertEquals(0, trips.started.single().lastCallMinutes)
    }

    @Test
    fun offlineSaysWhyAndStartsNothing() = runTest(main.dispatcher) {
        val notifier = FakeTripNotifier()
        val session = FakeSession(Session("Ayşe", "tr", "h1", true))
        val vm = going(FakeTrips(failWith = ApiException(ApiError.NETWORK)), notifier, session)
        vm.send()
        advanceUntilIdle()
        assertEquals(R.string.going_offline, vm.state.value.message)
        assertTrue(notifier.shown.isEmpty())
        assertNull(session.state.value.shopping)
    }

    @Test
    fun doubleTapOnSendStartsOneTrip() = runTest(main.dispatcher) {
        val trips = FakeTrips(tripId = "t-go")
        val vm = going(trips, FakeTripNotifier(), FakeSession(Session("Ayşe", "tr", "h1", true)))
        vm.onStoreText("Migros")
        vm.send()
        vm.send()
        advanceUntilIdle()
        vm.send() // and never again once sent
        advanceUntilIdle()
        assertEquals(1, trips.started.size)
    }
}
