package com.fridgelist.app.location

import com.fridgelist.core.model.NearbyStore
import com.fridgelist.core.model.Role
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** ARCHITECTURE §5.2 on Android. Pure, so the rules are unit-tested without a phone. */
object DetectionRules {
    const val STORE_RADIUS_M = 120f
    const val DWELL_MS = 120_000
    /** Geofencing allows 100 per app: 90 stores + the refresh fence, with room to spare. */
    const val MAX_STORES = 90
    const val REFRESH_RADIUS_M = 3_000f
    const val COOLDOWN_MS = 3 * 60 * 60 * 1000L
    /** `nearbyStores` maximums (CONTRACT §4). */
    const val FETCH_RADIUS_M = 10_000
    const val FETCH_LIMIT = 100

    const val REFRESH_ID = "refresh"
    private const val STORE_PREFIX = "store:"

    fun fenceId(storeId: String) = "$STORE_PREFIX$storeId"

    fun storeIdOf(fenceId: String): String? = fenceId.removePrefix(STORE_PREFIX).takeIf { fenceId.startsWith(STORE_PREFIX) && it.isNotEmpty() }

    /** The nearest [MAX_STORES] stores that aren't muted, one fence per store. */
    fun watchSet(stores: List<NearbyStore>, muted: Set<String>): List<NearbyStore> =
        stores.asSequence()
            .filterNot { it.storeId in muted }
            .distinctBy { it.storeId }
            .sortedBy { it.distanceM }
            .take(MAX_STORES)
            .toList()

    enum class Skip { DISABLED, KILL_SWITCH, SIGNED_OUT, KID, UNKNOWN_STORE, MUTED, COOLDOWN, NO_OPEN_ITEMS, ALREADY_SHOPPING }

    sealed interface Decision {
        /** Start an auto trip: AUTO_ARRIVED + `startTrip(shopping, auto: true)`. */
        data object Fire : Decision

        /** The shopper said "I'm going" and has now arrived: their trip moves to `shopping`. */
        data object Arrived : Decision

        data class Skipped(val reason: Skip) : Decision
    }

    data class Input(
        val enabled: Boolean,
        val killSwitchOn: Boolean,
        val signedIn: Boolean,
        val role: Role?,
        val knownStore: Boolean,
        val muted: Boolean,
        val lastFiredMs: Long?,
        val nowMs: Long,
        val openItems: Int,
        /** null: no trip on this device; true: an "I'm going" trip not yet at a store. */
        val activeTripHeading: Boolean?,
    )

    /** "Fire only if: detection on, ≥ 1 open item, store not muted, no trigger for this store in the last 3 h." */
    fun decide(i: Input): Decision {
        fun skip(reason: Skip) = Decision.Skipped(reason)
        return when {
            !i.enabled -> skip(Skip.DISABLED)
            !i.killSwitchOn -> skip(Skip.KILL_SWITCH)
            !i.signedIn -> skip(Skip.SIGNED_OUT)
            i.role == Role.KID || i.role == null -> skip(Skip.KID) // kids can't start trips (CONTRACT v3)
            !i.knownStore -> skip(Skip.UNKNOWN_STORE)
            i.muted -> skip(Skip.MUTED)
            i.lastFiredMs != null && i.nowMs - i.lastFiredMs < COOLDOWN_MS -> skip(Skip.COOLDOWN)
            i.activeTripHeading == true -> Decision.Arrived
            i.activeTripHeading == false -> skip(Skip.ALREADY_SHOPPING)
            i.openItems < 1 -> skip(Skip.NO_OPEN_ITEMS)
            else -> Decision.Fire
        }
    }

    /** True once the phone is outside the 3 km circle around the last refresh point. */
    fun outsideRefreshCircle(lat: Double, lng: Double, centerLat: Double?, centerLng: Double?): Boolean =
        centerLat == null || centerLng == null || distanceM(lat, lng, centerLat, centerLng) > REFRESH_RADIUS_M

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
