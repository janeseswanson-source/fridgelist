package com.fridgelist.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fridgelist.app.fakes.FakeSession
import com.fridgelist.app.fakes.FakeTripNotifier
import com.fridgelist.app.fakes.FakeWidgetRefresher
import com.fridgelist.app.feature.lists.HouseholdContext
import com.fridgelist.app.notifications.HouseholdNotifier
import com.fridgelist.app.notifications.PushRouter
import com.fridgelist.app.quickadd.QuickAdd
import com.fridgelist.app.widget.SnapshotBuilder
import com.fridgelist.app.widget.itemLine
import com.fridgelist.core.data.items.ListItems
import com.fridgelist.core.data.session.ActiveShopping
import com.fridgelist.core.data.session.Session
import com.fridgelist.core.model.AddedVia
import com.fridgelist.core.model.Category
import com.fridgelist.core.model.ItemDoc
import com.fridgelist.core.model.ItemStatus
import com.fridgelist.core.model.ListDoc
import com.fridgelist.core.model.ListType
import com.fridgelist.core.model.Role
import com.fridgelist.core.model.SnapshotItem
import com.fridgelist.core.model.SnapshotTrip
import com.fridgelist.core.model.WidgetSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WidgetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Ayşe reads Turkish; the household default is Arabic. */
    private val ctx = HouseholdContext("h1", "me", Role.ADULT, viewerLang = "tr", defaultLanguage = "ar")

    private var created = 0L

    private fun item(
        id: String,
        name: String,
        status: ItemStatus = ItemStatus.OPEN,
        category: Category = Category.PANTRY,
        urgent: Boolean = false,
        names: Map<String, String>? = null,
        doneTripId: String? = null,
        qty: Double? = null,
    ) = ItemDoc(
        id = id, name = name, lang = "ar", names = names, category = category, status = status,
        addedBy = "mom", urgent = urgent, doneTripId = doneTripId, qty = qty, createdAt = ++created,
    )

    private val lists = listOf(ListDoc("l1", "البقالة", ListType.GROCERY), ListDoc("l2", "Eczane", ListType.PHARMACY))

    @Test
    fun snapshotHoldsTheToBuyItemsInDisplayOrderAndViewerLanguage() {
        val l1 = ListItems(
            listOf(
                item("a", "خبز", category = Category.BAKERY),
                item("b", "حليب", category = Category.DAIRY_EGGS, names = mapOf("tr" to "süt"), qty = 2.0),
                item("c", "طماطم", category = Category.PRODUCE),
                item("d", "لبنة", category = Category.DAIRY_EGGS, urgent = true),
                item("e", "شاي", status = ItemStatus.DONE),
                item("f", "موز", status = ItemStatus.PENDING),
                item("g", "جبن", status = ItemStatus.MISSING),
                item("h", "زيت", status = ItemStatus.CLAIMED),
            ),
        )
        val snapshot = SnapshotBuilder.build(ctx, lists, mapOf("l1" to l1), shopping = null, nowSeconds = 1_800_000_000) { it.qty?.let { q -> "${q.toInt()} L" } }
        val list = snapshot.lists.first()
        // Produce, bakery, dairy (urgent first), pantry: the same order as the list screen.
        assertEquals(listOf("c", "a", "d", "b", "h"), list.items.map { it.id })
        assertEquals(5, list.openCount)
        assertEquals(SnapshotItem("b", "süt", "2 L", "dairy_eggs", "open"), list.items[3])
        assertTrue(list.items[2].urgent)
        assertEquals("claimed", list.items[4].status)
        assertEquals(WidgetSnapshot(1, 1_800_000_000, "tr", "h1", snapshot.lists, null), snapshot)
        assertEquals(0, snapshot.lists[1].openCount) // no items loaded yet for l2
    }

    @Test
    fun atMostTwelveItemsButTheFullCount() {
        val many = ListItems((1..20).map { item("i$it", "صنف $it") })
        val list = SnapshotBuilder.build(ctx, lists.take(1), mapOf("l1" to many), null, 0) { null }.lists.single()
        assertEquals(12, list.items.size)
        assertEquals(20, list.openCount)
    }

    @Test
    fun activeTripOnlyWhileShoppingHere() {
        val l1 = ListItems(
            listOf(
                item("a", "خبز"),
                item("b", "حليب", status = ItemStatus.DONE, doneTripId = "t1"),
                item("c", "شاي", status = ItemStatus.DONE, doneTripId = "older"),
            ),
        )
        fun trip(shopping: ActiveShopping?) = SnapshotBuilder.build(ctx, lists, mapOf("l1" to l1), shopping, 0) { null }.activeTrip
        assertEquals(SnapshotTrip("t1", "Migros", remaining = 1, done = 1), trip(ActiveShopping("l1", "t1", storeName = "Migros")))
        assertNull(trip(ActiveShopping("l1", "t1", heading = true, storeName = "Migros")), "not at the store yet")
        assertNull(trip(ActiveShopping("l1", null)), "local mode has no trip")
        assertNull(trip(null))
    }

    @Test
    fun tickingRemovesTheItemAtOnce() {
        val snapshot = WidgetSnapshot(
            updatedAt = 1, viewerLang = "ar", householdId = "h1",
            lists = SnapshotBuilder.build(ctx, lists, mapOf("l1" to ListItems(listOf(item("a", "خبز"), item("b", "حليب")))), null, 1) { null }.lists,
            activeTrip = SnapshotTrip("t1", "Migros", remaining = 2, done = 0),
        )
        val after = SnapshotBuilder.withoutItem(snapshot, "l1", "a")
        assertEquals(listOf("b"), after.lists[0].items.map { it.id })
        assertEquals(1, after.lists[0].openCount)
        assertEquals(SnapshotTrip("t1", "Migros", remaining = 1, done = 1), after.activeTrip)
        assertEquals(after, SnapshotBuilder.withoutItem(after, "l1", "zzz"), "unknown items change nothing")
    }

    @Test
    fun snapshotRoundTripsAndRejectsNewerVersions() {
        val snapshot = SnapshotBuilder.build(ctx, lists, mapOf("l1" to ListItems(listOf(item("a", "خبز")))), null, 5) { null }
        assertEquals(snapshot, WidgetSnapshot.decode(snapshot.encode()))
        assertNull(WidgetSnapshot.decode(snapshot.copy(v = 2).encode()))
        assertNull(WidgetSnapshot.decode("{not json"))
        assertNull(WidgetSnapshot.decode(null))
        assertEquals("l2", snapshot.list("l2")?.id)
        assertEquals("l1", snapshot.list("deleted")?.id, "a deleted list falls back to the first")
    }

    @Test
    fun itemLinesKeepArabicNamesAndLatinUnitsInOrder() {
        val line = itemLine(SnapshotItem("a", "حليب", "2 L", "dairy_eggs", "open", urgent = true))
        assertEquals("❗ ⁨حليب⁩ · ⁨2 L⁩", line)
        assertEquals("⁨süt⁩", itemLine(SnapshotItem("b", "süt", null, "dairy_eggs", "open")))
    }

    @Test
    fun listChangedPushesRefreshWidgets() = runTest {
        val refresher = FakeWidgetRefresher()
        val router = PushRouter(HouseholdNotifier(context), FakeTripNotifier(), FakeSession(Session("Ayşe", "tr", "h1", true)), refresher)
        router.route(mapOf("type" to "list_changed", "householdId" to "h1", "listId" to "l1"))
        router.route(mapOf("type" to "list_changed", "householdId" to "other", "listId" to "l9"))
        assertEquals(1, refresher.refreshes, "another household's push is ignored")
    }

    @Test
    fun quickAddIntentsUseTheContractLink() {
        val intent = QuickAdd.intent(context, "l1", voice = true, via = AddedVia.WIDGET)
        assertEquals("fridgelist://add?listId=l1&mode=voice", intent.dataString)
        assertEquals("widget", intent.getStringExtra(QuickAdd.EXTRA_VIA))
        assertEquals("fridgelist://add?mode=voice", QuickAdd.intent(context, null, voice = true, via = null).dataString)
        assertNull(QuickAdd.intent(context, null, voice = true, via = null).getStringExtra(QuickAdd.EXTRA_VIA))
    }

    /** QA: another app started the exported config screen with a foreign widget ID and the app crashed. */
    @Test
    fun onlyOurOwnWidgetsCanBeConfigured() {
        val ours = android.content.ComponentName(context.packageName, com.fridgelist.app.widget.ListWidgetReceiver::class.java.name)
        assertTrue(com.fridgelist.app.widget.isOurWidget(ours, context.packageName))
        assertTrue(!com.fridgelist.app.widget.isOurWidget(null, context.packageName), "unknown ID")
        assertTrue(!com.fridgelist.app.widget.isOurWidget(android.content.ComponentName("com.other.app", ours.className), context.packageName))
        assertTrue(!com.fridgelist.app.widget.isOurWidget(android.content.ComponentName(context.packageName, "com.fridgelist.app.Other"), context.packageName))
    }
}
