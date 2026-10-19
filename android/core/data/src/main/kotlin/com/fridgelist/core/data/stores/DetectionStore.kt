package com.fridgelist.core.data.stores

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fridgelist.core.model.NearbyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A store the user muted; the name is kept only to show it in Settings. */
data class MutedStore(val storeId: String, val name: String)

/** The trip store detection started, so leaving that store can ask "Done shopping?". */
data class AutoTrip(val householdId: String, val storeId: String, val storeName: String, val tripId: String?)

/**
 * Store detection state. Device-only by design (CLAUDE.md rule 4): the watched stores, the last
 * refresh point and the per-store cooldowns never leave the phone.
 */
data class DetectionState(
    val enabled: Boolean = false,
    val muted: List<MutedStore> = emptyList(),
    /** storeId → epoch ms of the last trigger (3 h cooldown, ARCHITECTURE §5.2). */
    val lastFired: Map<String, Long> = emptyMap(),
    val watched: List<NearbyStore> = emptyList(),
    val refreshLat: Double? = null,
    val refreshLng: Double? = null,
    val autoTrip: AutoTrip? = null,
) {
    fun isMuted(storeId: String) = muted.any { it.storeId == storeId }
}

interface DetectionStore {
    val state: Flow<DetectionState>

    suspend fun current(): DetectionState = state.first()

    suspend fun setEnabled(enabled: Boolean)

    suspend fun mute(store: MutedStore)

    suspend fun unmute(storeId: String)

    suspend fun markFired(storeId: String, atMs: Long)

    /** Replaces the watched set and the refresh point (center of the 3 km fence). */
    suspend fun saveWatched(stores: List<NearbyStore>, lat: Double, lng: Double)

    suspend fun setAutoTrip(trip: AutoTrip?)

    /** Sign-out: forget everything. */
    suspend fun clear()
}

private val Context.detectionDataStore: DataStore<Preferences> by preferencesDataStore(name = "detection")

@Singleton
class DataStoreDetectionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : DetectionStore {

    private val store get() = context.detectionDataStore

    override val state: Flow<DetectionState> = store.data.map { p ->
        DetectionState(
            enabled = p[ENABLED] ?: false,
            muted = p[MUTED].orEmpty().mapNotNull(::decodeMuted).sortedBy { it.name },
            lastFired = p[FIRED].orEmpty().mapNotNull(::decodeFired).toMap(),
            watched = p[WATCHED]?.let { runCatching { JSON.decodeFromString(STORES, it) }.getOrNull() }.orEmpty(),
            refreshLat = p[REFRESH_LAT],
            refreshLng = p[REFRESH_LNG],
            autoTrip = p[AUTO_STORE]?.let { storeId ->
                AutoTrip(p[AUTO_HOUSEHOLD].orEmpty(), storeId, p[AUTO_STORE_NAME].orEmpty(), p[AUTO_TRIP])
            },
        )
    }

    override suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    override suspend fun mute(store: MutedStore) {
        this.store.edit { p ->
            p[MUTED] = p[MUTED].orEmpty().filterNot { decodeMuted(it)?.storeId == store.storeId }.toSet() +
                "${store.storeId}$SEP${store.name}"
        }
    }

    override suspend fun unmute(storeId: String) {
        store.edit { p -> p[MUTED] = p[MUTED].orEmpty().filterNot { decodeMuted(it)?.storeId == storeId }.toSet() }
    }

    override suspend fun markFired(storeId: String, atMs: Long) {
        store.edit { p ->
            // Drop entries past the cooldown so the set stays small.
            val kept = p[FIRED].orEmpty().mapNotNull(::decodeFired)
                .filter { (id, ms) -> id != storeId && atMs - ms < KEEP_MS }
            p[FIRED] = (kept.map { (id, ms) -> "$id$SEP$ms" } + "$storeId$SEP$atMs").toSet()
        }
    }

    override suspend fun saveWatched(stores: List<NearbyStore>, lat: Double, lng: Double) {
        store.edit {
            it[WATCHED] = JSON.encodeToString(STORES, stores)
            it[REFRESH_LAT] = lat
            it[REFRESH_LNG] = lng
        }
    }

    override suspend fun setAutoTrip(trip: AutoTrip?) {
        store.edit { p ->
            if (trip == null) {
                listOf(AUTO_HOUSEHOLD, AUTO_STORE, AUTO_STORE_NAME, AUTO_TRIP).forEach { p.remove(it) }
            } else {
                p[AUTO_HOUSEHOLD] = trip.householdId
                p[AUTO_STORE] = trip.storeId
                p[AUTO_STORE_NAME] = trip.storeName
                if (trip.tripId != null) p[AUTO_TRIP] = trip.tripId else p.remove(AUTO_TRIP)
            }
        }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        const val SEP = "\t"
        const val KEEP_MS = 24 * 60 * 60 * 1000L
        val JSON = Json { ignoreUnknownKeys = true }
        val STORES = ListSerializer(NearbyStore.serializer())

        val ENABLED = booleanPreferencesKey("enabled")
        val MUTED = stringSetPreferencesKey("muted")
        val FIRED = stringSetPreferencesKey("fired")
        val WATCHED = stringPreferencesKey("watched")
        val REFRESH_LAT = doublePreferencesKey("refresh_lat")
        val REFRESH_LNG = doublePreferencesKey("refresh_lng")
        val AUTO_HOUSEHOLD = stringPreferencesKey("auto_household")
        val AUTO_STORE = stringPreferencesKey("auto_store")
        val AUTO_STORE_NAME = stringPreferencesKey("auto_store_name")
        val AUTO_TRIP = stringPreferencesKey("auto_trip")

        fun decodeMuted(raw: String): MutedStore? =
            raw.split(SEP, limit = 2).takeIf { it.size == 2 }?.let { (id, name) -> MutedStore(id, name) }

        fun decodeFired(raw: String): Pair<String, Long>? =
            raw.split(SEP, limit = 2).takeIf { it.size == 2 }?.let { (id, ms) -> ms.toLongOrNull()?.let { id to it } }
    }
}
