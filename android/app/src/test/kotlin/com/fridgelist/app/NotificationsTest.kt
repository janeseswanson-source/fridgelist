package com.fridgelist.app

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.fridgelist.app.fakes.FakeSession
import com.fridgelist.app.fakes.FakeTripNotifier
import com.fridgelist.app.notifications.Channels
import com.fridgelist.app.notifications.HouseholdNotifier
import com.fridgelist.app.notifications.PushRouter
import com.fridgelist.app.notifications.TripNotice
import com.fridgelist.app.notifications.TripNotifications
import com.fridgelist.core.data.session.ActiveShopping
import com.fridgelist.core.data.session.Session
import com.fridgelist.core.model.PushMessage
import com.fridgelist.core.model.TripStatus
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Prompt A6: Robolectric tests for the notification builders, the inline reply and push routing. */
@RunWith(RobolectricTestRunner::class)
class NotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Channels.create(context)
    }

    private val shopping = TripNotice("h1", "t1", TripStatus.SHOPPING, "Migros", total = 12, done = 5, nextItems = listOf("süt", "ekmek", "yumurta", "zeytin"))

    @Test
    fun channelsMatchTheContract() {
        assertEquals(setOf("trip", "last_call", "household", "suggestions"), manager.notificationChannels.map { it.id }.toSet())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("last_call").importance)
        assertNull(manager.getNotificationChannel("trip").sound, "the trip channel is silent")
    }

    @Test
    fun shopperSeesProgressNextItemsAndDone() {
        val n = TripNotifications.build(context, shopping, nowSeconds = 1_000)
        assertEquals("trip", n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0, "ongoing")
        assertEquals("At Migros", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue("5 of 12 in the cart" in text, text)
        assertTrue("Next: süt, ekmek, yumurta" in text, "next 3 items: $text")
        assertEquals(listOf("Open", "Done"), n.actions.map { it.title.toString() })
        assertEquals(5, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(12, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
    }

    @Test
    fun liveUpdateRequestAndSegments() {
        val n = TripNotifications.build(context, shopping, nowSeconds = 1_000)
        // Promotion is requested on every version; Android 16+ honours it (verified on the API 36 emulator).
        assertTrue(n.extras.getBoolean(NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING), "requests promotion")
        assertEquals(12, TripNotifications.segments(12).size, "one segment per item")
        assertEquals(1, TripNotifications.segments(45).size, "a single bar beyond 30 items")
    }

    @Test
    fun headingShowsLastCallCountdownAndArrival() {
        val n = TripNotifications.build(context, shopping.copy(status = TripStatus.HEADING, lastCallUntil = 1_600), nowSeconds = 1_000)
        assertEquals("Heading to Migros", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(1_600_000L, n.`when`)
        assertTrue(n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(listOf("Open", "I’m at the store"), n.actions.map { it.title.toString() })
        val expired = TripNotifications.build(context, shopping.copy(status = TripStatus.HEADING, lastCallUntil = 900), nowSeconds = 1_000)
        assertTrue(!expired.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN), "no countdown once last call is over")
    }

    @Test
    fun tripHeadingHasTheInlineReply() {
        val (_, n) = HouseholdNotifier.build(
            context,
            PushMessage.TripHeading("h1", "t1", "l1", 1_760_780_600, "Ali is heading to Migros", "Add anything in the next 10 min"),
        )!!
        assertEquals("last_call", n.channelId)
        assertEquals("Ali is heading to Migros", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val reply = n.actions.single()
        assertEquals("item_text", reply.remoteInputs.single().resultKey)
        assertEquals(Notification.Action.SEMANTIC_ACTION_REPLY, reply.semanticAction)
    }

    @Test
    fun arrivedAndMissingUseTheHouseholdChannel() {
        val (_, arrived) = HouseholdNotifier.build(context, PushMessage.TripArrived("h1", "t1", "l1", "Ali is at Migros", ""))!!
        assertEquals("household", arrived.channelId)
        assertEquals("item_text", arrived.actions.single().remoteInputs.single().resultKey)
        val (_, missing) = HouseholdNotifier.build(context, PushMessage.ItemMissing("h1", "i1", "l1", "t1", "Ali couldn't find labneh", "It stays on the list"))!!
        assertEquals("household", missing.channelId)
        assertNull(missing.actions, "nothing to reply to")
    }

    @Test
    fun replyTurnsTheNotificationIntoAddedCheck() {
        val n = HouseholdNotifier.added(context, "last_call", "Ali is heading to Migros", "milk, bread")
        assertEquals("Added ✓", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("milk, bread", n.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)!!.single().toString())
    }

    @Test
    fun routerPostsHouseholdPushesAndDrivesTheTripNotification() = runTest {
        val trips = FakeTripNotifier()
        val session = FakeSession(Session("Ayşe", "tr", "h1", true, ActiveShopping("l1", "t1")))
        val router = PushRouter(HouseholdNotifier(context), trips, session)

        router.route(mapOf("type" to "trip_heading", "householdId" to "h1", "tripId" to "t9", "listId" to "l1", "title" to "Ali is heading to Migros", "body" to "…"))
        assertEquals(1, shadowOf(manager).allNotifications.size)

        router.route(mapOf("type" to "trip_update", "householdId" to "h1", "tripId" to "t1", "status" to "shopping", "storeName" to "Migros", "remaining" to "7", "done" to "5"))
        assertEquals(12, trips.shown.single().total)

        router.route(mapOf("type" to "trip_update", "householdId" to "h1", "tripId" to "t1", "status" to "done"))
        assertEquals(1, trips.cancelled)
        assertNull(session.state.value.shopping, "a server-ended trip ends the session here too")

        router.route(mapOf("type" to "something_new", "householdId" to "h1"))
        router.route(mapOf("type" to "trip_heading"))
        assertEquals(1, shadowOf(manager).allNotifications.size, "unknown or broken pushes are ignored")
    }
}
