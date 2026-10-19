package com.fridgelist.app.work

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fridgelist.app.location.DetectionNotifications
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.trips.TripRepository
import com.fridgelist.core.model.TripStatus
import com.fridgelist.core.model.UpdateTripRequest
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Ends a trip on the server (`updateTrip(done|cancelled)`), now or once the phone is back online. */
fun interface TripEnder {
    fun endTrip(householdId: String, tripId: String, status: TripStatus)

    fun endTrip(householdId: String, tripId: String) = endTrip(householdId, tripId, TripStatus.DONE)
}

class WorkManagerTripEnder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : TripEnder {
    override fun endTrip(householdId: String, tripId: String, status: TripStatus) {
        // However the trip ends (Shopping Mode, the trip card, AUTO_LEFT), "At Migros" / "Done shopping?" are stale now.
        NotificationManagerCompat.from(context).run {
            cancel(DetectionNotifications.ARRIVED_ID)
            cancel(DetectionNotifications.LEFT_ID)
        }
        val request = OneTimeWorkRequestBuilder<EndTripWorker>()
            .setInputData(workDataOf(KEY_HOUSEHOLD to householdId, KEY_TRIP to tripId, KEY_STATUS to status.wire))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // One job per trip: tapping Done twice never sends two requests.
        WorkManager.getInstance(context).enqueueUniqueWork("end-trip-$tripId", ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val KEY_HOUSEHOLD = "householdId"
        const val KEY_TRIP = "tripId"
        const val KEY_STATUS = "status"
    }
}

class EndTripWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun trips(): TripRepository
    }

    override suspend fun doWork(): Result {
        val householdId = inputData.getString(WorkManagerTripEnder.KEY_HOUSEHOLD) ?: return Result.failure()
        val tripId = inputData.getString(WorkManagerTripEnder.KEY_TRIP) ?: return Result.failure()
        val status = if (inputData.getString(WorkManagerTripEnder.KEY_STATUS) == TripStatus.CANCELLED.wire) TripStatus.CANCELLED else TripStatus.DONE
        val trips = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).trips()
        return outcome {
            trips.updateTrip(UpdateTripRequest(householdId, tripId, status.wire))
        }.also { Log.i(TAG, "end trip: $it (attempt $runAttemptCount)") }
    }

    private suspend fun outcome(call: suspend () -> Unit): Result = try {
        call()
        Result.success()
    } catch (e: ApiException) {
        decide(e.error, runAttemptCount)
    }

    companion object {
        private const val TAG = "EndTrip"
        private const val MAX_ATTEMPTS = 8

        /** Already ended / not ours / gone: nothing left to do. Network and server trouble: retry. */
        fun decide(error: ApiError, attempt: Int): Result = when (error) {
            ApiError.FAILED_PRECONDITION, ApiError.NOT_FOUND, ApiError.PERMISSION_DENIED, ApiError.INVALID_ARGUMENT -> Result.success()
            else -> if (attempt < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }
}
