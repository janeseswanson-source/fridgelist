package com.fridgelist.core.data.trips

import com.fridgelist.core.data.callable.CallableClient
import com.fridgelist.core.data.callable.call
import com.fridgelist.core.model.StartTripRequest
import com.fridgelist.core.model.StartTripResponse
import com.fridgelist.core.model.UpdateTripRequest
import com.fridgelist.core.model.UpdateTripResponse
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** CONTRACT §4 trips. Trips themselves are functions-only documents (§3.7), readable by members. */
interface TripRepository {
    suspend fun startTrip(request: StartTripRequest): StartTripResponse

    suspend fun updateTrip(request: UpdateTripRequest): UpdateTripResponse

    /** The trip's list IDs (for `fridgelist://shop?tripId=`), from cache or server; null when unknown. */
    suspend fun tripListIds(householdId: String, tripId: String): List<String>?
}

@Singleton
class FirebaseTripRepository @Inject constructor(
    private val callables: CallableClient,
    private val firestore: FirebaseFirestore,
) : TripRepository {

    override suspend fun startTrip(request: StartTripRequest): StartTripResponse =
        callables.call("startTrip", request, StartTripRequest.serializer(), StartTripResponse.serializer())

    override suspend fun updateTrip(request: UpdateTripRequest): UpdateTripResponse =
        callables.call("updateTrip", request, UpdateTripRequest.serializer(), UpdateTripResponse.serializer())

    override suspend fun tripListIds(householdId: String, tripId: String): List<String>? = withTimeoutOrNull(5_000) {
        runCatching {
            firestore.collection("households").document(householdId).collection("trips").document(tripId)
                .get().await().get("listIds") as? List<*>
        }.getOrNull()?.filterIsInstance<String>()
    }
}
