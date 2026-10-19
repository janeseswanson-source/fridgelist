package com.fridgelist.app.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.fridgelist.app.MainActivity
import com.fridgelist.app.R
import com.fridgelist.core.model.PushMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifications other members get (CONTRACT §5.1). Text comes from the server, localized to the
 * recipient (`title`/`body` data keys, contract v2); the app adds the inline reply.
 */
@Singleton
class HouseholdNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    @SuppressLint("MissingPermission") // TripNotifications.canPost checks it
    fun show(message: PushMessage) {
        if (!TripNotifications.canPost(context)) return
        val (id, notification) = build(context, message) ?: return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        /** CONTRACT §5.1 inline reply: action `ADD_ITEM`, RemoteInput key `item_text`. */
        const val REPLY_KEY = "item_text"
        const val ACTION_ADD_ITEM = "ADD_ITEM"

        fun notificationId(kind: String, id: String?) = "$kind:$id".hashCode()

        fun build(context: Context, message: PushMessage): Pair<Int, Notification>? = when (message) {
            is PushMessage.TripHeading -> {
                val id = notificationId("trip", message.tripId)
                id to tripNotification(context, Channels.LAST_CALL, id, message.householdId, message.listId, message.title, message.body)
                    .apply {
                        // Last call: the countdown is the point of this notification.
                        message.lastCallUntil?.let { setWhen(it * 1000).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true) }
                        setPriority(NotificationCompat.PRIORITY_HIGH)
                    }.build()
            }
            is PushMessage.TripArrived -> {
                val id = notificationId("trip", message.tripId)
                id to tripNotification(context, Channels.HOUSEHOLD, id, message.householdId, message.listId, message.title, message.body).build()
            }
            is PushMessage.ItemMissing -> {
                val id = notificationId("missing", message.itemId)
                id to NotificationCompat.Builder(context, Channels.HOUSEHOLD)
                    .setSmallIcon(R.drawable.ic_cart)
                    .setContentTitle(message.title)
                    .setContentText(message.body)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(context, id))
                    .build()
            }
            else -> null
        }

        private fun tripNotification(
            context: Context,
            channel: String,
            id: Int,
            householdId: String,
            listId: String?,
            title: String,
            body: String,
        ): NotificationCompat.Builder {
            val builder = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_cart)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(openApp(context, id))
            if (listId != null) builder.addAction(replyAction(context, id, householdId, listId, channel, title, body))
            return builder
        }

        private fun replyAction(
            context: Context,
            id: Int,
            householdId: String,
            listId: String,
            channel: String,
            title: String,
            body: String,
        ): NotificationCompat.Action {
            val remoteInput = RemoteInput.Builder(REPLY_KEY).setLabel(context.getString(R.string.notif_reply_hint)).build()
            val intent = Intent(context, AddFromNotificationReceiver::class.java)
                .setAction(ACTION_ADD_ITEM)
                .putExtra(AddFromNotificationReceiver.EXTRA_HOUSEHOLD, householdId)
                .putExtra(AddFromNotificationReceiver.EXTRA_LIST, listId)
                .putExtra(AddFromNotificationReceiver.EXTRA_NOTIFICATION_ID, id)
                .putExtra(AddFromNotificationReceiver.EXTRA_CHANNEL, channel)
                .putExtra(AddFromNotificationReceiver.EXTRA_TITLE, title)
                .putExtra(AddFromNotificationReceiver.EXTRA_BODY, body)
            // RemoteInput needs a mutable PendingIntent (the system fills in the typed text).
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Action.Builder(R.drawable.ic_add, context.getString(R.string.notif_reply_label), pending)
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(false)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build()
        }

        /** Shown after a reply: "Added ✓", with the reply in the history, no new sound. */
        fun added(context: Context, channel: String, title: String, reply: CharSequence): Notification =
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_cart)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.notif_added))
                .setRemoteInputHistory(arrayOf(reply))
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setAutoCancel(true)
                .setTimeoutAfter(10_000)
                .build()

        private fun openApp(context: Context, requestCode: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MAIN, Uri.EMPTY, context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
