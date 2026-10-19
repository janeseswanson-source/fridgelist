package com.fridgelist.app.notifications

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.fridgelist.app.R

/** Notification channels (ARCHITECTURE §8.4, CONTRACT §5.1 "Android channel"). IDs are contract values. */
object Channels {
    const val TRIP = "trip"
    const val LAST_CALL = "last_call"
    const val HOUSEHOLD = "household"
    const val SUGGESTIONS = "suggestions"

    /** Idempotent; names follow the app language at the time it runs (re-run on language change). */
    fun create(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        listOf(
            channel(context, TRIP, NotificationManagerCompat.IMPORTANCE_LOW, R.string.channel_trip, R.string.channel_trip_desc),
            channel(context, LAST_CALL, NotificationManagerCompat.IMPORTANCE_HIGH, R.string.channel_last_call, R.string.channel_last_call_desc),
            channel(context, HOUSEHOLD, NotificationManagerCompat.IMPORTANCE_DEFAULT, R.string.channel_household, R.string.channel_household_desc),
            channel(context, SUGGESTIONS, NotificationManagerCompat.IMPORTANCE_LOW, R.string.channel_suggestions, R.string.channel_suggestions_desc),
        ).forEach(manager::createNotificationChannel)
    }

    private fun channel(context: Context, id: String, importance: Int, name: Int, description: Int) =
        NotificationChannelCompat.Builder(id, importance)
            .setName(context.getString(name))
            .setDescription(context.getString(description))
            .apply { if (id == TRIP) setSound(null, null).setVibrationEnabled(false) }
            .build()
}
