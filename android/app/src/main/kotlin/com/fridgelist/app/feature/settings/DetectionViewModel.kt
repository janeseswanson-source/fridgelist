package com.fridgelist.app.feature.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgelist.app.R
import com.fridgelist.app.feature.going.LocationSource
import com.fridgelist.app.feature.lists.HouseholdContextSource
import com.fridgelist.app.location.LocationPermissions
import com.fridgelist.app.location.StoreDetection
import com.fridgelist.app.location.StoreFences
import com.fridgelist.core.data.flags.RemoteFlags
import com.fridgelist.core.data.stores.DetectionStore
import com.fridgelist.core.data.stores.MutedStore
import com.fridgelist.core.data.trips.StoreRepository
import com.fridgelist.core.model.AddStoreRequest
import com.fridgelist.core.model.Flags
import com.fridgelist.core.model.NearbyStore
import com.fridgelist.core.model.Role
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Location permission state, behind a seam so the flow is unit-tested. */
interface LocationPermissionState {
    fun fine(): Boolean
    fun background(): Boolean

    /** Android 10+ asks for background location separately. */
    fun backgroundIsSeparate(): Boolean
}

class AndroidLocationPermissionState @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LocationPermissionState {
    override fun fine() = LocationPermissions.fine(context)
    override fun background() = LocationPermissions.background(context)
    override fun backgroundIsSeparate() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
}

/** Which dialog or system prompt comes next when turning detection on. */
enum class PermissionStep { NONE, RATIONALE, ASK_FINE, DISCLOSURE, ASK_BACKGROUND }

enum class DetectionStatus { OFF, PAUSED, NEEDS_BACKGROUND, WAITING, WATCHING }

data class DetectionUi(
    /** Kids can't start trips (CONTRACT v3), so the section is hidden for them. */
    val canShop: Boolean = true,
    val enabled: Boolean = false,
    val status: DetectionStatus = DetectionStatus.OFF,
    val watchedCount: Int = 0,
    /** The nearest watched stores, to mute one. */
    val nearby: List<NearbyStore> = emptyList(),
    val muted: List<MutedStore> = emptyList(),
    val step: PermissionStep = PermissionStep.NONE,
    val storeName: String = "",
    val addingStore: Boolean = false,
    @param:StringRes val message: Int? = null,
    /** Dev debug screen only. */
    val watched: List<NearbyStore> = emptyList(),
    val refreshPoint: String? = null,
    val debugResult: String? = null,
)

private data class Local(
    val step: PermissionStep = PermissionStep.NONE,
    val storeName: String = "",
    val addingStore: Boolean = false,
    @param:StringRes val message: Int? = null,
    val killSwitchOn: Boolean = true,
    val permissionsTick: Int = 0,
    val debugResult: String? = null,
)

@HiltViewModel
class DetectionViewModel @Inject constructor(
    private val detection: DetectionStore,
    private val fences: StoreFences,
    private val permissions: LocationPermissionState,
    private val flags: RemoteFlags,
    private val stores: StoreRepository,
    private val location: LocationSource,
    private val storeDetection: StoreDetection,
    contextSource: HouseholdContextSource,
) : ViewModel() {

    private val local = MutableStateFlow(Local())

    private val canShop = contextSource.context.map { it.role != Role.KID }.onStart { emit(true) }

    val state: StateFlow<DetectionUi> = combine(detection.state, local, canShop) { d, l, shop ->
        val status = when {
            !d.enabled -> DetectionStatus.OFF
            !l.killSwitchOn -> DetectionStatus.PAUSED
            !permissions.fine() || !permissions.background() -> DetectionStatus.NEEDS_BACKGROUND
            d.watched.isEmpty() -> DetectionStatus.WAITING
            else -> DetectionStatus.WATCHING
        }
        DetectionUi(
            canShop = shop,
            enabled = d.enabled,
            status = status,
            watchedCount = d.watched.count { !d.isMuted(it.storeId) },
            nearby = d.watched.filterNot { d.isMuted(it.storeId) }.sortedBy { it.distanceM }.take(NEARBY_SHOWN),
            muted = d.muted,
            step = l.step,
            storeName = l.storeName,
            addingStore = l.addingStore,
            message = l.message,
            watched = d.watched.sortedBy { it.distanceM },
            refreshPoint = if (d.refreshLat != null && d.refreshLng != null) "%.4f, %.4f".format(d.refreshLat, d.refreshLng) else null,
            debugResult = l.debugResult,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetectionUi())

    init {
        viewModelScope.launch {
            val on = flags.boolean(Flags.STORE_DETECTION_ENABLED)
            local.update { it.copy(killSwitchOn = on) }
        }
    }

    /** The screen came back (e.g. from system settings): permissions may have changed. */
    fun onResume() {
        local.update { it.copy(permissionsTick = it.permissionsTick + 1) }
        viewModelScope.launch { if (detection.current().enabled) fences.reregister() }
    }

    fun onToggle(on: Boolean) {
        if (!on) {
            viewModelScope.launch {
                detection.setEnabled(false)
                fences.clear()
            }
            return
        }
        when {
            !permissions.fine() -> local.update { it.copy(step = PermissionStep.RATIONALE) }
            !permissions.background() -> local.update { it.copy(step = PermissionStep.DISCLOSURE) }
            else -> enable()
        }
    }

    fun onRationaleContinue() = local.update { it.copy(step = PermissionStep.ASK_FINE) }

    fun onDisclosureContinue() = local.update { it.copy(step = PermissionStep.ASK_BACKGROUND) }

    fun onDismissStep() = local.update { it.copy(step = PermissionStep.NONE) }

    fun onFineResult(granted: Boolean) {
        when {
            !granted -> local.update { it.copy(step = PermissionStep.NONE) }
            permissions.backgroundIsSeparate() && !permissions.background() -> local.update { it.copy(step = PermissionStep.DISCLOSURE) }
            else -> enable()
        }
    }

    fun onBackgroundResult(granted: Boolean) {
        if (granted || permissions.background()) enable() else local.update { it.copy(step = PermissionStep.NONE) }
    }

    private fun enable() {
        local.update { it.copy(step = PermissionStep.NONE) }
        viewModelScope.launch {
            detection.setEnabled(true)
            fences.refresh()
        }
    }

    fun mute(store: NearbyStore) {
        viewModelScope.launch {
            detection.mute(MutedStore(store.storeId, store.name))
            fences.reregister()
        }
    }

    fun unmute(storeId: String) {
        viewModelScope.launch {
            detection.unmute(storeId)
            fences.refresh() // the store was left out of the saved set; fetch it again
        }
    }

    fun onStoreName(text: String) = local.update { it.copy(storeName = text.take(STORE_NAME_MAX), message = null) }

    /** CONTRACT §4 `addStore` at the phone's position; the next refresh watches it. */
    fun addStore() {
        val name = local.value.storeName.trim()
        if (name.isEmpty() || local.value.addingStore) return
        local.update { it.copy(addingStore = true, message = null) }
        viewModelScope.launch {
            val here = location.current()
            val message = when {
                here == null -> R.string.detect_add_store_no_location
                runCatching { stores.add(AddStoreRequest(name, here.first, here.second)) }.isSuccess -> {
                    if (detection.current().enabled) fences.refresh()
                    R.string.detect_add_store_done
                }
                else -> R.string.detect_add_store_failed
            }
            local.update {
                it.copy(addingStore = false, message = message, storeName = if (message == R.string.detect_add_store_done) "" else it.storeName)
            }
        }
    }

    // Dev-only debug screen (ARCHITECTURE §8.5).
    fun debugRefresh() = debug { fences.refresh().name }

    fun debugDwell(storeId: String) = debug { storeDetection.arrive(storeId).toString() }

    fun debugExit(storeId: String) = debug { "AUTO_LEFT shown: ${storeDetection.leave(storeId)}" }

    private fun debug(block: suspend () -> String) {
        viewModelScope.launch {
            val result = runCatching { block() }.getOrElse { it::class.simpleName.orEmpty() }
            local.update { it.copy(debugResult = result) }
        }
    }

    private companion object {
        const val NEARBY_SHOWN = 8
        const val STORE_NAME_MAX = 60
    }
}
