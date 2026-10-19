package com.fridgelist.core.data.trips

import com.fridgelist.core.data.callable.CallableClient
import com.fridgelist.core.data.callable.call
import com.fridgelist.core.model.AddStoreRequest
import com.fridgelist.core.model.AddStoreResponse
import com.fridgelist.core.model.NearbyStoresRequest
import com.fridgelist.core.model.NearbyStoresResponse
import javax.inject.Inject
import javax.inject.Singleton

/** CONTRACT §4 `nearbyStores`: nearest stores, sorted by distance. Needs the network. */
interface StoreRepository {
    suspend fun nearby(request: NearbyStoresRequest): NearbyStoresResponse

    /** CONTRACT §4 `addStore` (20 per day per user). */
    suspend fun add(request: AddStoreRequest): AddStoreResponse
}

@Singleton
class CallableStoreRepository @Inject constructor(
    private val callables: CallableClient,
) : StoreRepository {
    override suspend fun nearby(request: NearbyStoresRequest): NearbyStoresResponse =
        callables.call("nearbyStores", request, NearbyStoresRequest.serializer(), NearbyStoresResponse.serializer())

    override suspend fun add(request: AddStoreRequest): AddStoreResponse =
        callables.call("addStore", request, AddStoreRequest.serializer(), AddStoreResponse.serializer())
}
