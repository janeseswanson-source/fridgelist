package com.fridgelist.core.data.trips

import com.fridgelist.core.data.callable.CallableClient
import com.fridgelist.core.data.callable.call
import com.fridgelist.core.model.NearbyStoresRequest
import com.fridgelist.core.model.NearbyStoresResponse
import javax.inject.Inject
import javax.inject.Singleton

/** CONTRACT §4 `nearbyStores`: nearest stores, sorted by distance. Needs the network. */
interface StoreRepository {
    suspend fun nearby(request: NearbyStoresRequest): NearbyStoresResponse
}

@Singleton
class CallableStoreRepository @Inject constructor(
    private val callables: CallableClient,
) : StoreRepository {
    override suspend fun nearby(request: NearbyStoresRequest): NearbyStoresResponse =
        callables.call("nearbyStores", request, NearbyStoresRequest.serializer(), NearbyStoresResponse.serializer())
}
