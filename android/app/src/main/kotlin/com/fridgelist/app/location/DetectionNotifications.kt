package com.fridgelist.app.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.fridgelist.app.BuildConfig
import com.fridgelist.app.MainActivity
import com.fridgelist.app.R
import com.fridgelist.app.notifications.Channels
import com.fridgelist.app.notifications.TripNotifications
import com.fridgelist.app.notifications.TripNotifier
import com.fridgelist.app.work.TripEnder
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.data.stores.AutoTrip
import com.fridgelist.core.data.stores.DetectionStore
import com.fridgelist.core.model.TripStatus
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What AUTO_ARRIVED shows: "At Migros · 9 items". [tripId] is null when startTrip was unreachable. */
data class ArrivalNotice(val householdId: String, val storeName: String, val openItems: Int, val tripId: String?)

/** CONTRACT §5.1 local notifications. Seam for tests. */
interface DetectionNotifier {
    fun arrived(notice: ArrivalNotice)
    fun left(trip: AutoTrip)
    fun clear()
}

@Singleton
class DetectionNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DetectionNotifier {

    @SuppressLint("MissingPermission") // checked in canPost()
    override fun arrived(notice: ArrivalNotice) {
        if (!TripNotifications.canPost(context)) return
        NotificationManagerCompat.from(context).notify(ARRIVED_ID, buildArrived(context, notice))
    }

    @SuppressLint("MissingPermission")
    override fun left(trip: AutoTrip) {
        if (!TripNotifications.canPost(context)) return
        NotificationManagerCompat.from(context).notify(LEFT_ID, buildLeft(context, trip))
    }

    override fun clear() {
        NotificationManagerCompat.from(context).run {
            cancel(ARRIVED_ID)
            cancel(LEFT_ID)
        }
    }

    companion object {
        const val ARRIVED_ID = 1101
        const val LEFT_ID = 1102

        // CONTRACT §5.1 identifiers.
        const val AUTO_ARRIVED = "AUTO_ARRIVED"
        const val AUTO_LEFT = "AUTO_LEFT"

        fun buildArrived(context: Context, notice: ArrivalNotice): Notification {
            val title = context.resources.getQuantityString(R.plurals.detect_arrived, notice.openItems, notice.storeName, notice.openItems)
            val open = openList(context, notice.tripId)
            return NotificationCompat.Builder(context, Channels.HOUSEHOLD)
                .setSmallIcon(R.drawable.ic_cart)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.detect_arrived_text))
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setGroup(AUTO_ARRIVED)
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.detect_action_open_list), open)
                .addAction(0, context.getString(R.string.detect_action_not_shopping), DetectionActionReceiver.intent(context, DetectionActionReceiver.NOT_SHOPPING))
                .build()
        }

        fun buildLeft(context: Context, trip: AutoTrip): Notification =
            NotificationCompat.Builder(context, Channels.HOUSEHOLD)
                .setSmallIcon(R.drawable.ic_cart)
                .setContentTitle(context.getString(R.string.detect_left_title))
                .setContentText(context.getString(R.string.detect_left_text, trip.storeName))
                .setGroup(AUTO_LEFT)
                .setContentIntent(openList(context, trip.tripId))
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.detect_action_done), DetectionActionReceiver.intent(context, DetectionActionReceiver.DONE_SHOPPING))
                .addAction(0, context.getString(R.string.detect_action_still_here), DetectionActionReceiver.intent(context, DetectionActionReceiver.STILL_HERE))
                .build()

        /** With a trip: the internal shop link (CONTRACT §6); offline: the app, which resumes Shopping Mode. */
        private fun openList(context: Context, tripId: String?): PendingIntent {
            val intent = if (tripId != null) {
                Intent(Intent.ACTION_VIEW, "${BuildConfig.URL_SCHEME}://shop?tripId=$tripId".toUri())
            } else {
                Intent(Intent.ACTION_MAIN, Uri.EMPTY)
            }.setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, ARRIVED_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}

/** NOT_SHOPPING cancels the auto trip; DONE_SHOPPING ends it; STILL_HERE just dismisses. */
@AndroidEntryPoint
class DetectionActionReceiver : BroadcastReceiver() {

    @Inject lateinit var detection: DetectionStore

    @Inject lateinit var session: SessionStore

    @Inject lateinit var tripEnder: TripEnder

    @Inject lateinit var tripNotifier: TripNotifier

    @Inject lateinit var notifier: DetectionNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        notifier.clear()
        if (action == STILL_HERE) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                endAutoTrip(if (action == NOT_SHOPPING) TripStatus.CANCELLED else TripStatus.DONE)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun endAutoTrip(status: TripStatus) {
        val auto = detection.current().autoTrip ?: return
        detection.setAutoTrip(null)
        val shopping = session.current().shopping
        // Only the trip detection started: never end a trip the user began by hand since.
        if (shopping != null && shopping.tripId == auto.tripId) {
            session.endShopping()
            tripNotifier.cancel()
        }
        auto.tripId?.let { tripEnder.endTrip(auto.householdId, it, status) }
    }

    companion object {
        const val NOT_SHOPPING = "com.fridgelist.app.action.NOT_SHOPPING"
        const val DONE_SHOPPING = "com.fridgelist.app.action.DONE_SHOPPING"
        const val STILL_HERE = "com.fridgelist.app.action.STILL_HERE"

        fun intent(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, DetectionActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
