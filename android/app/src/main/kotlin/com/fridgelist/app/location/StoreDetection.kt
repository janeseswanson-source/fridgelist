package com.fridgelist.app.location

import android.util.Log
import com.fridgelist.app.feature.lists.HouseholdContextSource
import com.fridgelist.app.notifications.TripNotice
import com.fridgelist.app.notifications.TripNotifier
import com.fridgelist.core.data.flags.RemoteFlags
import com.fridgelist.core.data.items.ListRepository
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.data.stores.AutoTrip
import com.fridgelist.core.data.stores.DetectionStore
import com.fridgelist.core.data.trips.TripRepository
import com.fridgelist.core.model.Flags
import com.fridgelist.core.model.Platform
import com.fridgelist.core.model.StartTripRequest
import com.fridgelist.core.model.TripStatus
import com.fridgelist.core.model.UpdateTripRequest
import com.fridgelist.core.data.session.ActiveShopping
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * What happens when a store fence fires (ARCHITECTURE §5.2): apply the rules, then AUTO_ARRIVED,
 * `startTrip(shopping, auto: true)` and the ongoing trip notification. Runs in a worker, often
 * with the app closed, so every read has a timeout and works from the Firestore cache.
 */
class StoreDetection @Inject constructor(
    private val detection: DetectionStore,
    private val flags: RemoteFlags,
    private val session: SessionStore,
    private val contextSource: HouseholdContextSource,
    private val lists: ListRepository,
    private val trips: TripRepository,
    private val tripNotifier: TripNotifier,
    private val notifier: DetectionNotifier,
) {
    /** Tests move the clock. */
    var now: () -> Long = System::currentTimeMillis

    suspend fun arrive(storeId: String): DetectionRules.Decision {
        val state = detection.current()
        val store = state.watched.firstOrNull { it.storeId == storeId }
        val active = session.current().shopping
        val ctx = withTimeoutOrNull(READ_TIMEOUT_MS) { contextSource.context.first() }
        // Lists with open items, first by display order: the trip covers the first one (like "I'm going").
        val open = ctx?.let { c ->
            withTimeoutOrNull(READ_TIMEOUT_MS) {
                lists.lists(c.householdId).first().map { it.id to lists.openCount(c.householdId, it.id).first() }
            }
        }.orEmpty().filter { it.second > 0 }
        val decision = DetectionRules.decide(
            DetectionRules.Input(
                enabled = state.enabled,
                killSwitchOn = flags.boolean(Flags.STORE_DETECTION_ENABLED),
                signedIn = ctx != null,
                role = ctx?.role,
                knownStore = store != null,
                muted = state.isMuted(storeId),
                lastFiredMs = state.lastFired[storeId],
                nowMs = now(),
                openItems = open.sumOf { it.second },
                activeTripHeading = active?.heading,
            ),
        )
        Log.i(TAG, "arrive: $decision") // no store or item names in logs (CLAUDE.md rule 4)
        if (ctx == null || store == null) return decision
        when (decision) {
            DetectionRules.Decision.Fire -> {
                detection.markFired(storeId, now())
                val listId = open.first().first
                val total = open.first().second
                val tripId = withTimeoutOrNull(CALL_TIMEOUT_MS) {
                    runCatching {
                        trips.startTrip(
                            StartTripRequest(
                                householdId = ctx.householdId,
                                status = TripStatus.SHOPPING.wire,
                                auto = true,
                                storeId = store.storeId,
                                storeName = store.name,
                                listIds = listOf(listId),
                                platform = Platform.ANDROID.wire,
                                deviceId = session.deviceId(),
                            ),
                        ).tripId
                    }.getOrNull()
                }
                // Offline: Shopping Mode still starts locally, like the manual flow (A5 local mode).
                session.startShopping(ActiveShopping(listId, tripId, heading = false, storeName = store.name))
                detection.setAutoTrip(AutoTrip(ctx.householdId, store.storeId, store.name, tripId))
                notifier.arrived(ArrivalNotice(ctx.householdId, store.name, total, tripId))
                if (tripId != null) {
                    tripNotifier.show(TripNotice(ctx.householdId, tripId, TripStatus.SHOPPING, store.name, total = total, done = 0))
                }
            }
            DetectionRules.Decision.Arrived -> {
                val shopping = checkNotNull(active)
                detection.markFired(storeId, now())
                shopping.tripId?.let { tripId ->
                    withTimeoutOrNull(CALL_TIMEOUT_MS) {
                        runCatching { trips.updateTrip(UpdateTripRequest(ctx.householdId, tripId, TripStatus.SHOPPING.wire)) }
                    }
                }
                session.startShopping(shopping.copy(heading = false, storeName = shopping.storeName.ifEmpty { store.name }))
                notifier.arrived(ArrivalNotice(ctx.householdId, shopping.storeName.ifEmpty { store.name }, open.sumOf { it.second }, shopping.tripId))
            }
            is DetectionRules.Decision.Skipped -> Unit
        }
        return decision
    }

    /** Leaving the store of the trip detection started: "Done shopping?" (AUTO_LEFT). */
    suspend fun leave(storeId: String): Boolean {
        val auto = detection.current().autoTrip ?: return false
        if (auto.storeId != storeId) return false
        val shopping = session.current().shopping
        if (shopping == null || shopping.tripId != auto.tripId) {
            detection.setAutoTrip(null) // that trip is already over
            return false
        }
        notifier.left(auto)
        return true
    }

    private companion object {
        const val TAG = "StoreDetection"
        const val READ_TIMEOUT_MS = 5_000L
        const val CALL_TIMEOUT_MS = 10_000L
    }
}
