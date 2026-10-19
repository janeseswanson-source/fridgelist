package com.fridgelist.app.feature.going

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgelist.app.R
import com.fridgelist.app.feature.lists.HouseholdContextSource
import com.fridgelist.app.notifications.TripNotice
import com.fridgelist.app.notifications.TripNotifier
import com.fridgelist.app.ui.common.ErrorMessages
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.session.ActiveShopping
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.data.trips.StoreRepository
import com.fridgelist.core.data.trips.TripRepository
import com.fridgelist.core.model.LAST_CALL_MINUTES
import com.fridgelist.core.model.NearbyStore
import com.fridgelist.core.model.NearbyStoresRequest
import com.fridgelist.core.model.Platform
import com.fridgelist.core.model.StartTripRequest
import com.fridgelist.core.model.TripStatus
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject

/** The phone's location, once, for "nearest stores" (never sent anywhere else: CLAUDE.md rule 4). */
fun interface LocationSource {
    /** null when unavailable; callers check the permission first. */
    suspend fun current(): Pair<Double, Double>?
}

class FusedLocationSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LocationSource {
    @SuppressLint("MissingPermission") // the sheet asks for ACCESS_COARSE_LOCATION before calling
    override suspend fun current(): Pair<Double, Double>? = withTimeoutOrNull(10_000) {
        runCatching {
            val location = LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token)
                .await()
            location?.let { it.latitude to it.longitude }
        }.getOrNull()
    }
}

data class GoingUiState(
    val nearby: List<NearbyStore> = emptyList(),
    val loadingNearby: Boolean = false,
    /** Chain names offered without location (ARCHITECTURE §5.1 chains of the phone's country). */
    val chains: List<String> = emptyList(),
    val storeName: String = "",
    val storeId: String? = null,
    val lastCallMinutes: Int = 10,
    val sending: Boolean = false,
    @param:StringRes val message: Int? = null,
    val sent: Boolean = false,
)

@HiltViewModel
class GoingViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val contextSource: HouseholdContextSource,
    private val stores: StoreRepository,
    private val trips: TripRepository,
    private val session: SessionStore,
    private val notifier: TripNotifier,
    private val location: LocationSource,
) : ViewModel() {

    private val listId: String = checkNotNull(savedState.get<String>("listId"))

    private val _state = MutableStateFlow(GoingUiState(chains = chainsFor(Locale.getDefault().country)))
    val state: StateFlow<GoingUiState> = _state.asStateFlow()

    /** Call after ACCESS_COARSE_LOCATION is granted. */
    fun loadNearby() {
        _state.update { it.copy(loadingNearby = true, message = null) }
        viewModelScope.launch {
            val here = location.current()
            val found = here?.let { (lat, lng) ->
                runCatching { stores.nearby(NearbyStoresRequest(lat, lng, limit = NEAREST)).stores.take(NEAREST) }.getOrNull()
            }
            _state.update {
                it.copy(
                    loadingNearby = false,
                    nearby = found.orEmpty(),
                    message = if (found.isNullOrEmpty()) R.string.going_no_stores else null,
                )
            }
        }
    }

    fun pickStore(store: NearbyStore) = _state.update { it.copy(storeName = store.name, storeId = store.storeId) }

    fun pickChain(name: String) = _state.update { it.copy(storeName = name, storeId = null) }

    fun onStoreText(text: String) = _state.update { it.copy(storeName = text.take(40), storeId = null) }

    fun onLastCall(minutes: Int) {
        if (minutes in LAST_CALL_MINUTES) _state.update { it.copy(lastCallMinutes = minutes) }
    }

    /** `startTrip(status: heading)`; the household is notified by the server (CONTRACT §5.1 trip_heading). */
    fun send() {
        val s = _state.value
        if (s.sending || s.sent) return // one trip, one push to the household
        _state.update { it.copy(sending = true, message = null) }
        viewModelScope.launch {
            try {
                val ctx = contextSource.context.first()
                val tripId = trips.startTrip(
                    StartTripRequest(
                        householdId = ctx.householdId,
                        status = TripStatus.HEADING.wire,
                        auto = false,
                        storeId = s.storeId,
                        storeName = s.storeName.trim().ifEmpty { null },
                        lastCallMinutes = s.lastCallMinutes,
                        listIds = listOf(listId),
                        platform = Platform.ANDROID.wire,
                        deviceId = session.deviceId(),
                    ),
                ).tripId
                session.startShopping(ActiveShopping(listId, tripId, heading = true, storeName = s.storeName.trim()))
                val now = System.currentTimeMillis() / 1000
                notifier.show(
                    TripNotice(
                        householdId = ctx.householdId,
                        tripId = tripId,
                        status = TripStatus.HEADING,
                        storeName = s.storeName.trim(),
                        total = 0,
                        done = 0,
                        lastCallUntil = if (s.lastCallMinutes > 0) now + s.lastCallMinutes * 60 else null,
                    ),
                )
                _state.update { it.copy(sending = false, sent = true) }
            } catch (e: ApiException) {
                val msg = if (e.error == ApiError.NETWORK || e.error == ApiError.UNAVAILABLE) R.string.going_offline else ErrorMessages.general(e)
                _state.update { it.copy(sending = false, message = msg) }
            }
        }
    }

    companion object {
        private const val NEAREST = 5

        fun chainsFor(country: String): List<String> = when (country.uppercase(Locale.ROOT)) {
            "TR" -> listOf("Migros", "A101", "BİM", "ŞOK", "CarrefourSA")
            "SA" -> listOf("Panda", "Danube", "Tamimi", "Othaim", "LuLu")
            "AE" -> listOf("Carrefour", "LuLu", "Spinneys", "Union Coop", "Waitrose")
            else -> listOf("Migros", "Panda", "Carrefour", "LuLu", "BİM")
        }
    }
}
