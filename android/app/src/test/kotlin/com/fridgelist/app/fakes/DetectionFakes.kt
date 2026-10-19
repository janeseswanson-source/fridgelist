package com.fridgelist.app.fakes

import com.fridgelist.app.feature.settings.LocationPermissionState
import com.fridgelist.app.location.ArrivalNotice
import com.fridgelist.app.location.DetectionNotifier
import com.fridgelist.app.location.RefreshResult
import com.fridgelist.app.location.StoreFences
import com.fridgelist.core.data.flags.RemoteFlags
import com.fridgelist.core.data.stores.AutoTrip
import com.fridgelist.core.data.stores.DetectionState
import com.fridgelist.core.data.stores.DetectionStore
import com.fridgelist.core.data.stores.MutedStore
import com.fridgelist.core.data.trips.StoreRepository
import com.fridgelist.core.model.AddStoreRequest
import com.fridgelist.core.model.AddStoreResponse
import com.fridgelist.core.model.Flags
import com.fridgelist.core.model.NearbyStore
import com.fridgelist.core.model.NearbyStoresRequest
import com.fridgelist.core.model.NearbyStoresResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class FakeDetectionStore(initial: DetectionState = DetectionState()) : DetectionStore {
    val data = MutableStateFlow(initial)
    override val state: Flow<DetectionState> = data
    override suspend fun setEnabled(enabled: Boolean) = data.update { it.copy(enabled = enabled) }
    override suspend fun mute(store: MutedStore) = data.update { it.copy(muted = it.muted.filterNot { m -> m.storeId == store.storeId } + store) }
    override suspend fun unmute(storeId: String) = data.update { it.copy(muted = it.muted.filterNot { m -> m.storeId == storeId }) }
    override suspend fun markFired(storeId: String, atMs: Long) = data.update { it.copy(lastFired = it.lastFired + (storeId to atMs)) }
    override suspend fun saveWatched(stores: List<NearbyStore>, lat: Double, lng: Double) =
        data.update { it.copy(watched = stores, refreshLat = lat, refreshLng = lng) }
    override suspend fun setAutoTrip(trip: AutoTrip?) = data.update { it.copy(autoTrip = trip) }
    override suspend fun clear() = data.update { DetectionState() }
}

class FakeFences : StoreFences {
    val calls = mutableListOf<String>()
    override suspend fun refresh(): RefreshResult = RefreshResult.REGISTERED.also { calls += "refresh" }
    override suspend fun reregister(): RefreshResult = RefreshResult.REGISTERED.also { calls += "reregister" }
    override suspend fun clear() {
        calls += "clear"
    }
}

class FakeDetectionNotifier : DetectionNotifier {
    val arrived = mutableListOf<ArrivalNotice>()
    val left = mutableListOf<AutoTrip>()
    override fun arrived(notice: ArrivalNotice) {
        arrived += notice
    }
    override fun left(trip: AutoTrip) {
        left += trip
    }
    override fun clear() = Unit
}

class FakeFlags(private val values: Map<String, Boolean> = emptyMap()) : RemoteFlags {
    override suspend fun boolean(key: String): Boolean = values[key] ?: (Flags.DEFAULTS[key] as? Boolean ?: false)
}

class FakePermissions(var fine: Boolean = false, var background: Boolean = false, var separate: Boolean = true) : LocationPermissionState {
    override fun fine() = fine
    override fun background() = background
    override fun backgroundIsSeparate() = separate
}

class FakeStoreRepository(private val stores: List<NearbyStore> = emptyList(), private val failAdd: Boolean = false) : StoreRepository {
    val added = mutableListOf<AddStoreRequest>()
    override suspend fun nearby(request: NearbyStoresRequest) = NearbyStoresResponse(stores)
    override suspend fun add(request: AddStoreRequest): AddStoreResponse {
        if (failAdd) error("offline")
        added += request
        return AddStoreResponse("user_new")
    }
}
