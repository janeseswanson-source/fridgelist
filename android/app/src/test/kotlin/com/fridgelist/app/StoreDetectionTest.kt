package com.fridgelist.app

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fridgelist.app.fakes.FakeDetectionNotifier
import com.fridgelist.app.fakes.FakeDetectionStore
import com.fridgelist.app.fakes.FakeFences
import com.fridgelist.app.fakes.FakeFlags
import com.fridgelist.app.fakes.FakeItems
import com.fridgelist.app.fakes.FakePermissions
import com.fridgelist.app.fakes.FakeSession
import com.fridgelist.app.fakes.FakeStoreRepository
import com.fridgelist.app.fakes.FakeTripNotifier
import com.fridgelist.app.fakes.FakeTrips
import com.fridgelist.app.fakes.MainDispatcherRule
import com.fridgelist.app.fakes.householdContext
import com.fridgelist.app.feature.going.LocationSource
import com.fridgelist.app.feature.settings.DetectionStatus
import com.fridgelist.app.feature.settings.DetectionViewModel
import com.fridgelist.app.feature.settings.PermissionStep
import com.fridgelist.app.location.ArrivalNotice
import com.fridgelist.app.location.DetectionNotifications
import com.fridgelist.app.location.DetectionRules
import com.fridgelist.app.location.DetectionRules.Decision
import com.fridgelist.app.location.DetectionRules.Skip
import com.fridgelist.app.location.StoreDetection
import com.fridgelist.core.data.items.ListItems
import com.fridgelist.core.data.session.ActiveShopping
import com.fridgelist.core.data.session.Session
import com.fridgelist.core.data.stores.AutoTrip
import com.fridgelist.core.data.stores.DetectionState
import com.fridgelist.core.data.stores.MutedStore
import com.fridgelist.core.model.Flags
import com.fridgelist.core.model.ItemDoc
import com.fridgelist.core.model.ItemStatus
import com.fridgelist.core.model.ListDoc
import com.fridgelist.core.model.ListType
import com.fridgelist.core.model.NearbyStore
import com.fridgelist.core.model.Role
import com.fridgelist.core.model.TripStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class StoreDetectionTest {

    @get:Rule val main = MainDispatcherRule()

    private val now = 1_800_000_000_000L
    private val migros = NearbyStore("osm_node_1", "Migros", "migros", 40.99, 29.03, 80.0)

    private fun store(id: Int, distance: Double) = NearbyStore("s$id", "Store $id", null, 0.0, 0.0, distance)

    private fun input(
        enabled: Boolean = true,
        kill: Boolean = true,
        role: Role? = Role.ADULT,
        muted: Boolean = false,
        lastFired: Long? = null,
        open: Int = 3,
        heading: Boolean? = null,
    ) = DetectionRules.Input(enabled, kill, signedIn = role != null, role, knownStore = true, muted, lastFired, now, open, heading)

    @Test
    fun rulesFireOnlyWhenEverythingHolds() {
        assertEquals(Decision.Fire, DetectionRules.decide(input()))
        assertEquals(Decision.Skipped(Skip.DISABLED), DetectionRules.decide(input(enabled = false)))
        assertEquals(Decision.Skipped(Skip.KILL_SWITCH), DetectionRules.decide(input(kill = false)))
        assertEquals(Decision.Skipped(Skip.KID), DetectionRules.decide(input(role = Role.KID)))
        assertEquals(Decision.Skipped(Skip.MUTED), DetectionRules.decide(input(muted = true)))
        assertEquals(Decision.Skipped(Skip.NO_OPEN_ITEMS), DetectionRules.decide(input(open = 0)))
        assertEquals(Decision.Skipped(Skip.ALREADY_SHOPPING), DetectionRules.decide(input(heading = false)))
        assertEquals(Decision.Arrived, DetectionRules.decide(input(heading = true)))
    }

    @Test
    fun cooldownIsThreeHoursPerStore() {
        val threeHours = 3 * 60 * 60 * 1000L
        assertEquals(Decision.Skipped(Skip.COOLDOWN), DetectionRules.decide(input(lastFired = now - threeHours + 1)))
        assertEquals(Decision.Fire, DetectionRules.decide(input(lastFired = now - threeHours)))
    }

    @Test
    fun watchSetIsTheNearestNinetyUnmutedStores() {
        val stores = (1..120).map { store(it, distance = (121 - it).toDouble()) } + store(120, 0.5) // duplicate id
        val set = DetectionRules.watchSet(stores, muted = setOf("s119"))
        assertEquals(90, set.size)
        assertEquals("s120", set.first().storeId)
        assertTrue(set.none { it.storeId == "s119" })
        assertEquals(set.map { it.storeId }.distinct(), set.map { it.storeId })
        assertTrue(set.zipWithNext().all { (a, b) -> a.distanceM <= b.distanceM })
    }

    @Test
    fun fenceIdsAndRefreshCircle() {
        assertEquals("osm_node_1", DetectionRules.storeIdOf(DetectionRules.fenceId("osm_node_1")))
        assertNull(DetectionRules.storeIdOf(DetectionRules.REFRESH_ID))
        assertNull(DetectionRules.storeIdOf("store:"))
        assertTrue(DetectionRules.outsideRefreshCircle(41.02, 29.03, 40.99, 29.03)) // ~3.3 km north
        assertTrue(!DetectionRules.outsideRefreshCircle(41.00, 29.03, 40.99, 29.03)) // ~1.1 km
        assertTrue(DetectionRules.outsideRefreshCircle(41.0, 29.0, null, null))
    }

    private class Setup(
        role: Role = Role.ADULT,
        val session: FakeSession = FakeSession(Session("Ayşe", "tr", "h1", true)),
        val store: FakeDetectionStore = FakeDetectionStore(),
        flags: FakeFlags = FakeFlags(),
        val trips: FakeTrips = FakeTrips("t9"),
        openItems: Int = 2,
    ) {
        val items = FakeItems().apply {
            lists.value = listOf(ListDoc("l1", "Market", ListType.GROCERY))
            listItems.value = ListItems((1..openItems).map { ItemDoc(id = "i$it", name = "x", status = ItemStatus.OPEN, addedBy = "me") })
        }
        val tripNotifier = FakeTripNotifier()
        val notifier = FakeDetectionNotifier()
        val detection = StoreDetection(store, flags, session, householdContext(role, session), items, trips, tripNotifier, notifier)
    }

    private fun watching(vararg stores: NearbyStore) = DetectionState(enabled = true, watched = stores.toList())

    @Test
    fun arrivalStartsAnAutoTripAndShowsItsNotifications() = runTest {
        val s = Setup(store = FakeDetectionStore(watching(migros)))
        s.detection.now = { now }
        assertEquals(Decision.Fire, s.detection.arrive("osm_node_1"))
        val start = s.trips.started.single()
        assertEquals(true, start.auto)
        assertEquals(TripStatus.SHOPPING.wire, start.status)
        assertEquals("osm_node_1", start.storeId)
        assertEquals(listOf("l1"), start.listIds)
        assertEquals(ArrivalNotice("h1", "Migros", 2, "t9"), s.notifier.arrived.single())
        assertEquals("t9", s.tripNotifier.shown.single().tripId)
        assertEquals(ActiveShopping("l1", "t9", heading = false, storeName = "Migros"), s.session.state.value.shopping)
        val state = s.store.data.value
        assertEquals(now, state.lastFired["osm_node_1"])
        assertEquals(AutoTrip("h1", "osm_node_1", "Migros", "t9"), state.autoTrip)
        // Same store again within 3 h: nothing.
        s.session.endShopping()
        assertEquals(Decision.Skipped(Skip.COOLDOWN), s.detection.arrive("osm_node_1"))
        assertEquals(1, s.trips.started.size)
    }

    @Test
    fun offlineArrivalStillStartsShoppingLocally() = runTest {
        val s = Setup(store = FakeDetectionStore(watching(migros)), trips = FakeTrips(failWith = IllegalStateException("offline")))
        assertEquals(Decision.Fire, s.detection.arrive("osm_node_1"))
        assertEquals(ArrivalNotice("h1", "Migros", 2, null), s.notifier.arrived.single())
        assertEquals(ActiveShopping("l1", null, heading = false, storeName = "Migros"), s.session.state.value.shopping)
        assertTrue(s.tripNotifier.shown.isEmpty())
    }

    @Test
    fun nothingHappensForKidsEmptyListsMutedOrKillSwitch() = runTest {
        for (setup in listOf(
            Setup(role = Role.KID, store = FakeDetectionStore(watching(migros))),
            Setup(openItems = 0, store = FakeDetectionStore(watching(migros))),
            Setup(store = FakeDetectionStore(watching(migros).copy(muted = listOf(MutedStore("osm_node_1", "Migros"))))),
            Setup(store = FakeDetectionStore(watching(migros)), flags = FakeFlags(mapOf(Flags.STORE_DETECTION_ENABLED to false))),
            Setup(store = FakeDetectionStore(watching(migros).copy(enabled = false))),
        )) {
            assertTrue(setup.detection.arrive("osm_node_1") is Decision.Skipped)
            assertTrue(setup.trips.started.isEmpty())
            assertTrue(setup.notifier.arrived.isEmpty())
        }
    }

    @Test
    fun arrivingOnAnImGoingTripMovesItToShopping() = runTest {
        val session = FakeSession(Session("Ayşe", "tr", "h1", true, ActiveShopping("l1", "t1", heading = true, storeName = "")))
        val s = Setup(session = session, store = FakeDetectionStore(watching(migros)))
        assertEquals(Decision.Arrived, s.detection.arrive("osm_node_1"))
        assertEquals("shopping", s.trips.updates.single().status)
        assertTrue(s.trips.started.isEmpty())
        assertEquals(ActiveShopping("l1", "t1", heading = false, storeName = "Migros"), session.state.value.shopping)
        assertNull(s.store.data.value.autoTrip) // not an auto trip: leaving won't ask
    }

    @Test
    fun leavingTheAutoTripStoreAsksDoneShopping() = runTest {
        val s = Setup(store = FakeDetectionStore(watching(migros)))
        s.detection.arrive("osm_node_1")
        assertTrue(!s.detection.leave("osm_other"))
        assertTrue(s.detection.leave("osm_node_1"))
        assertEquals("Migros", s.notifier.left.single().storeName)
        // The trip ended by hand meanwhile: no question, and the marker goes.
        s.session.endShopping()
        assertTrue(!s.detection.leave("osm_node_1"))
        assertNull(s.store.data.value.autoTrip)
    }

    /** Subscribes at once, so state.value is current after each call. */
    private fun TestScope.observe(vm: DetectionViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    }

    private class FixedLocation(private val at: Pair<Double, Double>?) : LocationSource {
        override suspend fun current() = at
    }

    private fun vm(
        permissions: FakePermissions,
        store: FakeDetectionStore = FakeDetectionStore(),
        fences: FakeFences = FakeFences(),
        stores: FakeStoreRepository = FakeStoreRepository(),
        location: Pair<Double, Double>? = 40.99 to 29.03,
        role: Role = Role.ADULT,
    ): DetectionViewModel {
        val s = Setup(store = store)
        return DetectionViewModel(store, fences, permissions, FakeFlags(), stores, FixedLocation(location), s.detection, householdContext(role))
    }

    @Test
    fun turningOnWalksThroughRationaleFineDisclosureBackground() = runTest {
        val permissions = FakePermissions()
        val store = FakeDetectionStore()
        val fences = FakeFences()
        val vm = vm(permissions, store, fences)
        observe(vm)
        vm.onToggle(true)
        assertEquals(PermissionStep.RATIONALE, vm.state.first { it.step == PermissionStep.RATIONALE }.step)
        vm.onRationaleContinue()
        assertEquals(PermissionStep.ASK_FINE, vm.state.value.step)
        permissions.fine = true
        vm.onFineResult(true)
        assertEquals(PermissionStep.DISCLOSURE, vm.state.value.step) // prominent disclosure before the system prompt
        vm.onDisclosureContinue()
        assertEquals(PermissionStep.ASK_BACKGROUND, vm.state.value.step)
        assertTrue(!store.data.value.enabled)
        permissions.background = true
        vm.onBackgroundResult(true)
        assertTrue(store.data.value.enabled)
        assertEquals(listOf("refresh"), fences.calls)
        vm.onToggle(false)
        assertTrue(!store.data.value.enabled)
        assertEquals("clear", fences.calls.last())
    }

    @Test
    fun deniedPermissionsLeaveItOffAndShowWhatIsMissing() = runTest {
        val permissions = FakePermissions(fine = true, background = false)
        val store = FakeDetectionStore()
        val vm = vm(permissions, store)
        observe(vm)
        vm.onToggle(true)
        assertEquals(PermissionStep.DISCLOSURE, vm.state.value.step)
        vm.onDisclosureContinue()
        vm.onBackgroundResult(false)
        assertTrue(!store.data.value.enabled)
        assertEquals(PermissionStep.NONE, vm.state.value.step)
        // Turned on earlier, background revoked later in system settings.
        store.setEnabled(true)
        assertEquals(DetectionStatus.NEEDS_BACKGROUND, vm.state.first { it.enabled }.status)
    }

    @Test
    @Config(sdk = [28])
    fun beforeAndroid10FineIsEnough() = runTest {
        val permissions = FakePermissions(separate = false)
        val store = FakeDetectionStore()
        val vm = vm(permissions, store)
        observe(vm)
        vm.onToggle(true)
        vm.onRationaleContinue()
        permissions.fine = true
        permissions.background = true // granted with fine on Android 9
        vm.onFineResult(true)
        assertTrue(store.data.value.enabled)
    }

    @Test
    fun muteAndAddStore() = runTest {
        val store = FakeDetectionStore(watching(migros, store(2, 300.0)))
        val fences = FakeFences()
        val stores = FakeStoreRepository()
        val vm = vm(FakePermissions(true, true), store, fences, stores)
        observe(vm)
        assertEquals(DetectionStatus.WATCHING, vm.state.first { it.enabled }.status)
        vm.mute(migros)
        assertEquals(listOf(MutedStore("osm_node_1", "Migros")), store.data.value.muted)
        assertEquals(1, vm.state.first { it.muted.isNotEmpty() }.watchedCount)
        vm.unmute("osm_node_1")
        assertTrue(store.data.value.muted.isEmpty())
        assertEquals(listOf("reregister", "refresh"), fences.calls)

        vm.onStoreName("  Bakkal Ali  ")
        vm.addStore()
        assertEquals("Bakkal Ali", stores.added.single().name)
        assertEquals(40.99, stores.added.single().lat)
        assertEquals(R.string.detect_add_store_done, vm.state.first { it.message != null }.message)
        assertEquals("", vm.state.value.storeName)
    }

    @Test
    fun addStoreWithoutLocationOrNetworkSaysWhy() = runTest {
        val noLocation = vm(FakePermissions(true, true), location = null)
        observe(noLocation)
        noLocation.onStoreName("Bakkal")
        noLocation.addStore()
        assertEquals(R.string.detect_add_store_no_location, noLocation.state.first { it.message != null }.message)
        val offline = vm(FakePermissions(true, true), stores = FakeStoreRepository(failAdd = true))
        observe(offline)
        offline.onStoreName("Bakkal")
        offline.addStore()
        assertEquals(R.string.detect_add_store_failed, offline.state.first { it.message != null }.message)
        assertEquals("Bakkal", offline.state.value.storeName) // kept to retry
    }

    @Test
    fun kidsDontSeeStoreDetection() = runTest {
        val vm = vm(FakePermissions(true, true), role = Role.KID)
        observe(vm)
        assertTrue(!vm.state.first { !it.canShop }.canShop)
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun actions(n: Notification) = n.actions.map { it.title.toString() }

    @Test
    fun arrivedAndLeftNotificationsInEnglish() {
        val arrived = DetectionNotifications.buildArrived(context, ArrivalNotice("h1", "Migros", 9, "t1"))
        assertEquals("At Migros · 9 items", title(arrived))
        assertEquals(listOf("Open list", "Not shopping"), actions(arrived))
        assertEquals("At Migros · 1 item", title(DetectionNotifications.buildArrived(context, ArrivalNotice("h1", "Migros", 1, null))))
        val left = DetectionNotifications.buildLeft(context, AutoTrip("h1", "s1", "Migros", "t1"))
        assertEquals("Done shopping?", title(left))
        assertEquals(listOf("Done shopping", "Still here"), actions(left))
    }

    @Test
    @Config(qualifiers = "ar")
    fun arrivedNotificationInArabicUsesArabicPlurals() {
        assertEquals("في بنده · صنفان", title(DetectionNotifications.buildArrived(context, ArrivalNotice("h1", "بنده", 2, "t1"))))
        assertEquals(listOf("افتح القائمة", "لا أتسوق"), actions(DetectionNotifications.buildArrived(context, ArrivalNotice("h1", "بنده", 2, "t1"))))
    }
}
