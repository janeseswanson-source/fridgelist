package com.fridgelist.app.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fridgelist.app.BuildConfig
import com.fridgelist.app.MainActivity
import com.fridgelist.app.R
import com.fridgelist.app.feature.lists.Quantities
import com.fridgelist.core.model.LAST_CALL_MINUTES
import com.fridgelist.core.model.TripStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** What the shopper's ongoing notification shows (ARCHITECTURE §6, CONTRACT §5.3 fields). */
data class TripNotice(
    val householdId: String,
    val tripId: String,
    val status: TripStatus,
    val storeName: String,
    val total: Int,
    val done: Int,
    val nextItems: List<String> = emptyList(),
    /** Unix seconds; a countdown shows while it is in the future. */
    val lastCallUntil: Long? = null,
    val latestAddBy: String? = null,
    val latestAddName: String? = null,
) {
    val remaining: Int get() = (total - done).coerceAtLeast(0)
}

/**
 * The shopper's ongoing trip notification. On Android 16+ it is a Live Update:
 * `ProgressStyle` with one segment per item (progress = in the cart), promoted ongoing, and a
 * short "7 left" chip; older versions show the same as a standard ongoing notification
 * (NotificationCompat handles the fallback).
 */
/** Seam for tests: shows or clears the shopper's ongoing trip notification. */
interface TripNotifier {
    fun show(notice: TripNotice)
    fun cancel()
}

@Singleton
class TripNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : TripNotifier {
    @SuppressLint("MissingPermission") // checked in canPost()
    override fun show(notice: TripNotice) {
        if (!canPost(context)) return
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, notice, nowSeconds()))
    }

    override fun cancel() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    companion object {
        /** One active trip per shopper (CONTRACT §4 startTrip), so one ID. */
        const val NOTIFICATION_ID = 1001

        /** One progress segment per item up to this many; beyond it, a single bar. */
        private const val MAX_SEGMENTS = 30

        fun canPost(context: Context): Boolean =
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                (android.os.Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

        private fun nowSeconds() = System.currentTimeMillis() / 1000

        fun build(context: Context, notice: TripNotice, nowSeconds: Long): Notification {
            val locale = context.resources.configuration.locales[0] ?: Locale.ROOT
            fun n(value: Int) = Quantities.format(value.toDouble(), locale)
            val store = notice.storeName.ifBlank { context.getString(R.string.trip_store_unknown) }
            val shopping = notice.status == TripStatus.SHOPPING
            val title = context.getString(if (shopping) R.string.trip_at else R.string.trip_heading_to, store)
            val lines = buildList {
                if (notice.latestAddBy != null && notice.latestAddName != null) {
                    add(context.getString(R.string.trip_latest_add, notice.latestAddBy, notice.latestAddName))
                }
                if (shopping) add(context.getString(R.string.trip_progress, n(notice.done), n(notice.total)))
                if (notice.nextItems.isNotEmpty()) {
                    add(context.getString(R.string.trip_next, notice.nextItems.take(3).joinToString(separator(locale))))
                }
            }

            val builder = NotificationCompat.Builder(context, Channels.TRIP)
                .setSmallIcon(R.drawable.ic_cart)
                .setContentTitle(title)
                .setContentText(lines.joinToString(" · "))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(openShopping(context, notice))
                .setRequestPromotedOngoing(true)
                .setShortCriticalText(
                    if (shopping) context.getString(R.string.trip_left, n(notice.remaining)) else store,
                )
                .addAction(0, context.getString(R.string.action_open), openShopping(context, notice))
                .addAction(
                    0,
                    context.getString(if (shopping) R.string.shop_done else R.string.action_at_store),
                    if (shopping) TripActionReceiver.done(context, notice) else openShopping(context, notice),
                )

            if (shopping && notice.total > 0) {
                val style = NotificationCompat.ProgressStyle()
                    .setProgressSegments(segments(notice.total))
                    .setProgress(notice.done.coerceAtMost(notice.total))
                builder.setStyle(style)
            }

            val lastCall = liveCountdown(notice.lastCallUntil, nowSeconds)
            if (lastCall != null) {
                builder.setWhen(lastCall * 1000)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setSubText(context.getString(R.string.trip_last_call))
            }
            return builder.build()
        }

        /**
         * The last-call end worth a countdown: in the future and within the longest last call. A
         * past value or a malformed one (milliseconds instead of seconds) shows no countdown.
         */
        fun liveCountdown(lastCallUntil: Long?, nowSeconds: Long): Long? =
            lastCallUntil?.takeIf { it > nowSeconds && it - nowSeconds <= LAST_CALL_MINUTES.max() * 60L + 60 }

        fun segments(total: Int): List<NotificationCompat.ProgressStyle.Segment> =
            if (total <= MAX_SEGMENTS) List(total) { NotificationCompat.ProgressStyle.Segment(1) } else listOf(NotificationCompat.ProgressStyle.Segment(total))

        private fun separator(locale: Locale) = if (locale.language == "ar") "، " else ", "

        /** Taps go through the internal link (CONTRACT §6 `{URL_SCHEME}://shop?tripId=`). */
        fun openShopping(context: Context, notice: TripNotice): PendingIntent {
            val intent = Intent(Intent.ACTION_VIEW, "${BuildConfig.URL_SCHEME}://shop?tripId=${notice.tripId}".toUri())
                .setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, notice.tripId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
