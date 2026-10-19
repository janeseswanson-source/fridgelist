package com.fridgelist.app.notifications

import android.util.Log
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.model.PushMessage
import com.fridgelist.core.model.TripStatus
import javax.inject.Inject

/** Routes every CONTRACT §5.1 push type. Plain class so the routing is unit-testable. */
class PushRouter @Inject constructor(
    private val household: HouseholdNotifier,
    private val trips: TripNotifier,
    private val session: SessionStore,
) {
    suspend fun route(data: Map<String, String>) {
        when (val message = PushMessage.parse(data)) {
            is PushMessage.TripHeading, is PushMessage.TripArrived, is PushMessage.ItemMissing -> household.show(message)
            is PushMessage.TripUpdate -> onTripUpdate(message)
            // A8 refreshes widgets on list_changed; nothing to show before that.
            is PushMessage.ListChanged -> Unit
            is PushMessage.Unknown -> Log.i(TAG, "ignored push type ${message.type}")
            null -> Log.w(TAG, "push without household")
        }
    }

    /** §5.3: only the shopper's own device gets these; ending statuses clear the notification. */
    private suspend fun onTripUpdate(update: PushMessage.TripUpdate) {
        when (update.status) {
            TripStatus.DONE, TripStatus.CANCELLED -> {
                trips.cancel()
                if (session.current().shopping?.tripId == update.tripId) session.endShopping()
            }
            TripStatus.HEADING, TripStatus.SHOPPING, null -> trips.show(
                TripNotice(
                    householdId = update.householdId,
                    tripId = update.tripId,
                    status = update.status ?: TripStatus.SHOPPING,
                    storeName = update.storeName,
                    total = update.done + update.remaining,
                    done = update.done,
                    nextItems = update.nextItems,
                    lastCallUntil = update.lastCallUntil,
                    latestAddBy = update.latestAddBy,
                    latestAddName = update.latestAddName,
                ),
            )
        }
    }

    private companion object {
        const val TAG = "PushRouter"
    }
}
