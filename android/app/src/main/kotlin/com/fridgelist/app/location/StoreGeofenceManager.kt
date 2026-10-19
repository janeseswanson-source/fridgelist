package com.fridgelist.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.fridgelist.core.data.flags.RemoteFlags
import com.fridgelist.core.data.stores.DetectionStore
import com.fridgelist.core.data.trips.StoreRepository
import com.fridgelist.core.model.Flags
import com.fridgelist.core.model.NearbyStore
import com.fridgelist.core.model.NearbyStoresRequest
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Location permissions store detection needs (ARCHITECTURE §5.4). */
object LocationPermissions {
    fun fine(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Before Android 10 there is no separate background permission. */
    fun background(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun all(context: Context) = fine(context) && background(context)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** Seam for tests and the debug screen. */
interface StoreFences {
    /** Fetch the nearest stores around the phone and replace the fences. Off/no permission → clear. */
    suspend fun refresh(): RefreshResult

    /** Register the saved set again (after reboot, app update, location back on). */
    suspend fun reregister(): RefreshResult

    suspend fun clear()
}

enum class RefreshResult { REGISTERED, CLEARED, NO_PERMISSION, NO_LOCATION, FAILED }

@Singleton
class StoreGeofenceManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val stores: StoreRepository,
    private val detection: DetectionStore,
    private val flags: RemoteFlags,
) : StoreFences {

    private val client by lazy { LocationServices.getGeofencingClient(context) }

    override suspend fun refresh(): RefreshResult {
        precondition()?.let { return it }
        val (lat, lng) = currentLocation() ?: run {
            Log.i(TAG, "no location fix")
            return reregister().let { if (it == RefreshResult.REGISTERED) it else RefreshResult.NO_LOCATION }
        }
        val state = detection.current()
        val nearby = runCatching {
            stores.nearby(NearbyStoresRequest(lat, lng, DetectionRules.FETCH_RADIUS_M, DetectionRules.FETCH_LIMIT)).stores
        }.getOrElse {
            // Offline: keep the fences we have; the refresh fence fires again later.
            Log.i(TAG, "nearbyStores failed: ${it::class.simpleName}")
            return reregister()
        }
        val set = DetectionRules.watchSet(nearby, state.muted.map { it.storeId }.toSet())
        detection.saveWatched(set, lat, lng)
        return register(set, lat, lng)
    }

    override suspend fun reregister(): RefreshResult {
        precondition()?.let { return it }
        val state = detection.current()
        val lat = state.refreshLat ?: return RefreshResult.NO_LOCATION
        val lng = state.refreshLng ?: return RefreshResult.NO_LOCATION
        val muted = state.muted.map { it.storeId }.toSet()
        return register(state.watched.filterNot { it.storeId in muted }, lat, lng)
    }

    override suspend fun clear() {
        runCatching { client.removeGeofences(pendingIntent()).await() }
    }

    private suspend fun precondition(): RefreshResult? {
        val on = detection.current().enabled && flags.boolean(Flags.STORE_DETECTION_ENABLED)
        if (!on) return RefreshResult.CLEARED.also { clear() }
        if (!LocationPermissions.all(context)) return RefreshResult.NO_PERMISSION.also { clear() }
        return null
    }

    @SuppressLint("MissingPermission") // precondition() checked fine + background location
    private suspend fun register(set: List<NearbyStore>, lat: Double, lng: Double): RefreshResult {
        val fences = set.map { store ->
            Geofence.Builder()
                .setRequestId(DetectionRules.fenceId(store.storeId))
                .setCircularRegion(store.lat, store.lng, DetectionRules.STORE_RADIUS_M)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT)
                .setLoiteringDelay(DetectionRules.DWELL_MS)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build()
        } + Geofence.Builder()
            .setRequestId(DetectionRules.REFRESH_ID)
            .setCircularRegion(lat, lng, DetectionRules.REFRESH_RADIUS_M)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()
        val request = GeofencingRequest.Builder()
            // Already standing in a store when the set is built: dwell still counts.
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofences(fences)
            .build()
        return runCatching {
            client.removeGeofences(pendingIntent()).await()
            client.addGeofences(request, pendingIntent()).await()
            Log.i(TAG, "registered ${set.size} store fences")
            RefreshResult.REGISTERED
        }.getOrElse {
            // GEOFENCE_NOT_AVAILABLE (location off) lands here; LocationStateReceiver retries.
            Log.w(TAG, "addGeofences failed: ${it::class.simpleName}")
            RefreshResult.FAILED
        }
    }

    /**
     * A GPS-grade fix: this runs rarely (leaving the 3 km circle) and often with the app closed, where
     * a balanced request may get nothing. Falls back to the last known location.
     */
    @SuppressLint("MissingPermission") // precondition() checked
    private suspend fun currentLocation(): Pair<Double, Double>? {
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val fix = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            runCatching { fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await() }.getOrNull()
        } ?: runCatching { fused.lastLocation.await() }.getOrNull()
        return fix?.let { it.latitude to it.longitude }
    }

    /** Must be mutable: Play services adds the geofencing event to the intent. */
    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).setAction(GeofenceBroadcastReceiver.ACTION)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    private companion object {
        const val TAG = "StoreFences"
        const val LOCATION_TIMEOUT_MS = 20_000L
    }
}
