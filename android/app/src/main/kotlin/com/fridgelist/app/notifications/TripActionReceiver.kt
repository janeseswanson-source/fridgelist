package com.fridgelist.app.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fridgelist.app.work.TripEnder
import com.fridgelist.core.data.session.SessionStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "Done" on the ongoing trip notification: end the trip without opening the app. */
@AndroidEntryPoint
class TripActionReceiver : BroadcastReceiver() {

    @Inject lateinit var tripEnder: TripEnder

    @Inject lateinit var session: SessionStore

    @Inject lateinit var trips: TripNotifier

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DONE) return
        val householdId = intent.getStringExtra(EXTRA_HOUSEHOLD) ?: return
        val tripId = intent.getStringExtra(EXTRA_TRIP) ?: return
        trips.cancel()
        tripEnder.endTrip(householdId, tripId)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (session.current().shopping?.tripId == tripId) session.endShopping()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.fridgelist.app.action.TRIP_DONE"
        private const val EXTRA_HOUSEHOLD = "householdId"
        private const val EXTRA_TRIP = "tripId"

        fun done(context: Context, notice: TripNotice): PendingIntent {
            val intent = Intent(context, TripActionReceiver::class.java)
                .setAction(ACTION_DONE)
                .putExtra(EXTRA_HOUSEHOLD, notice.householdId)
                .putExtra(EXTRA_TRIP, notice.tripId)
            return PendingIntent.getBroadcast(context, notice.tripId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
