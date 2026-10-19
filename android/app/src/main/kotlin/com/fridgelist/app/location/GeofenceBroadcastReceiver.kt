package com.fridgelist.app.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.location.LocationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fridgelist.app.R
import com.fridgelist.app.notifications.Channels
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Geofence events → an expedited [DetectionWorker] (ARCHITECTURE §5.4). The receiver only reads
 * the event and enqueues, so it never runs past the broadcast time limit.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "geofence error ${event.errorCode}")
            // Location was turned off: the fences are gone; LocationStateReceiver re-adds them.
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) return
            DetectionWorker.enqueue(context, DetectionWorker.REREGISTER)
            return
        }
        for (fence in event.triggeringGeofences.orEmpty()) {
            val id = fence.requestId
            when {
                id == DetectionRules.REFRESH_ID -> DetectionWorker.enqueue(context, DetectionWorker.REFRESH)
                event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_DWELL ->
                    DetectionRules.storeIdOf(id)?.let { DetectionWorker.enqueue(context, DetectionWorker.ARRIVE, it) }
                event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT ->
                    DetectionRules.storeIdOf(id)?.let { DetectionWorker.enqueue(context, DetectionWorker.LEAVE, it) }
            }
        }
    }

    companion object {
        const val ACTION = "com.fridgelist.app.action.GEOFENCE"
        /** Dev-only GPS keep-alive updates (see [EmulatorGps]); ignored here. */
        const val ACTION_GPS = "com.fridgelist.app.action.GPS"
        private const val TAG = "Geofence"
    }
}

/** Geofences are dropped on reboot, app update and when location is switched off (ARCHITECTURE §5.4). */
class LocationStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> DetectionWorker.enqueue(context, DetectionWorker.REREGISTER)
            LocationManager.PROVIDERS_CHANGED_ACTION -> {
                val manager = context.getSystemService(LocationManager::class.java)
                if (manager != null && LocationManagerCompat.isLocationEnabled(manager)) DetectionWorker.enqueue(context, DetectionWorker.REREGISTER)
            }
        }
    }
}

class DetectionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun detection(): StoreDetection
        fun fences(): StoreFences
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val storeId = inputData.getString(KEY_STORE)
        when (inputData.getString(KEY_ACTION)) {
            ARRIVE -> storeId?.let { deps.detection().arrive(it) }
            LEAVE -> storeId?.let { deps.detection().leave(it) }
            REFRESH -> deps.fences().refresh()
            REREGISTER -> deps.fences().reregister()
        }
        return Result.success()
    }

    /** Android 11 and older run expedited work as a short foreground service, which needs this. */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        WORKING_ID,
        NotificationCompat.Builder(applicationContext, Channels.TRIP)
            .setSmallIcon(R.drawable.ic_cart)
            .setContentTitle(applicationContext.getString(R.string.detect_working))
            .setSilent(true)
            .build(),
    )

    companion object {
        const val ARRIVE = "arrive"
        const val LEAVE = "leave"
        const val REFRESH = "refresh"
        const val REREGISTER = "reregister"
        private const val KEY_ACTION = "action"
        private const val KEY_STORE = "storeId"
        private const val WORKING_ID = 1103

        fun enqueue(context: Context, action: String, storeId: String? = null) {
            val request = OneTimeWorkRequestBuilder<DetectionWorker>()
                .setInputData(workDataOf(KEY_ACTION to action, KEY_STORE to storeId))
                // Arrival should feel instant; past the expedited quota it still runs, just later.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            val name = if (storeId == null) "detect-$action" else "detect-$action-$storeId"
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
        }
    }
}
