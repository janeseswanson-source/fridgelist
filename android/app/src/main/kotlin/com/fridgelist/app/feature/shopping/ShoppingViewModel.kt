package com.fridgelist.app.feature.shopping

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgelist.app.R
import com.fridgelist.app.feature.lists.HouseholdContext
import com.fridgelist.app.feature.lists.HouseholdContextSource
import com.fridgelist.app.feature.lists.ItemRowUi
import com.fridgelist.app.feature.lists.ListSectionsBuilder
import com.fridgelist.app.feature.lists.UndoQueue
import com.fridgelist.app.feature.lists.UndoUi
import com.fridgelist.app.notifications.TripNotice
import com.fridgelist.app.notifications.TripNotifier
import com.fridgelist.app.work.TripEnder
import com.fridgelist.core.data.firestore.ItemPatches
import com.fridgelist.core.data.items.ItemRepository
import com.fridgelist.core.data.items.ListItems
import com.fridgelist.core.data.items.ListRepository
import com.fridgelist.core.data.session.ActiveShopping
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.data.trips.TripRepository
import com.fridgelist.core.model.Category
import com.fridgelist.core.model.ItemStatus
import com.fridgelist.core.model.Platform
import com.fridgelist.core.model.Role
import com.fridgelist.core.model.StartTripRequest
import com.fridgelist.core.model.StatusRules
import com.fridgelist.core.model.TripStatus
import com.fridgelist.core.model.UpdateTripRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class ShoppingSummary(val bought: List<ItemRowUi>, val missing: List<ItemRowUi>, val rest: List<ItemRowUi>)

data class ShoppingUiState(
    val listName: String = "",
    /** Open and my-claimed items in aisle (category) order. */
    val toBuy: List<Pair<Category, List<ItemRowUi>>> = emptyList(),
    val claimedByOthers: List<ItemRowUi> = emptyList(),
    /** Bought on this trip (or in this session in local mode). */
    val inCart: List<ItemRowUi> = emptyList(),
    val missing: List<ItemRowUi> = emptyList(),
    /** No trip on the server (offline or backend unreachable): everything still works locally. */
    val localMode: Boolean = false,
    val summary: ShoppingSummary? = null,
    val loaded: Boolean = false,
) {
    val toBuyCount: Int get() = toBuy.sumOf { it.second.size }
}

/**
 * Shopping Mode (prompt A5, PRODUCT_SPEC F5): big rows in aisle order, tick / couldn't find with
 * Undo, works fully offline. A trip is started on the server when reachable; never waited on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShoppingViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    contextSource: HouseholdContextSource,
    private val lists: ListRepository,
    private val items: ItemRepository,
    private val trips: TripRepository,
    private val session: SessionStore,
    private val tripEnder: TripEnder,
    private val tripNotifications: TripNotifier,
) : ViewModel() {

    private var storeName: String = ""

    private val context = contextSource.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val listId = MutableStateFlow(savedState.get<String>("listId"))
    private val tripId = MutableStateFlow<String?>(savedState.get<String>("tripId"))
    /** Ticked in this session; saved so local mode keeps its cart across process death. */
    private val tickedHere = MutableStateFlow(savedState.get<Array<String>>(KEY_TICKED).orEmpty().toSet())
    private val summary = MutableStateFlow<ShoppingSummary?>(null)
    private val undoQueue = UndoQueue()
    val undo: StateFlow<UndoUi?> = undoQueue.undo

    /** Resolved start (list known, trip attempt finished or given up). */
    private val started = MutableStateFlow(false)

    /** A shop link for a trip whose list can't be found (ended, deleted, other household). */
    private val _tripGone = MutableStateFlow(false)
    val tripGone: StateFlow<Boolean> = _tripGone

    val state: StateFlow<ShoppingUiState> = combine(contextSource.context, listId.filterNotNull()) { ctx, lid -> ctx to lid }
        .flatMapLatest { (ctx, lid) ->
            combine(
                // null until Firestore answers: "everything is in the cart" must never flash at the start.
                items.items(ctx.householdId, lid).map<ListItems, ListItems?> { it }.onStart { emit(null) }.catch { emit(ListItems()) },
                lists.lists(ctx.householdId).map { all -> all.firstOrNull { it.id == lid }?.name.orEmpty() }.catch { emit("") },
                tripId,
                tickedHere,
                combine(summary, started) { s, st -> s to st },
            ) { listItems, name, trip, ticked, (sum, st) ->
                build(listItems ?: ListItems(), ctx, name, trip, ticked, sum, st).copy(loaded = listItems != null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppingUiState())

    init {
        viewModelScope.launch { tickedHere.collect { savedState[KEY_TICKED] = it.toTypedArray() } }
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        // Restored after process death on the summary: the trip is over; never start a new one.
        if (savedState.get<Boolean>(KEY_FINISHED) == true) {
            _tripGone.value = true
            return
        }
        val ctx = context.filterNotNull().first()
        // Kids don't shop (PRODUCT_SPEC roles): an old link or a resumed session must not start a trip.
        if (ctx.role == Role.KID) {
            session.endShopping() // a stale saved trip would keep "Continue shopping" on Home forever
            _tripGone.value = true
            return
        }
        val saved = session.current().shopping
        // Opened from fridgelist://shop?tripId=… : find its list.
        if (listId.value == null) {
            val fromTrip = tripId.value?.let { trips.tripListIds(ctx.householdId, it)?.firstOrNull() }
            listId.value = fromTrip ?: saved?.listId
            if (listId.value == null) {
                _tripGone.value = true
                return
            }
        }
        val lid = checkNotNull(listId.value)
        if (saved?.listId == lid) {
            if (tripId.value == null) tripId.value = saved.tripId
            storeName = saved.storeName
            // "I'm going" trip and now at the store: heading → shopping (best effort, never blocks).
            val trip = saved.tripId
            if (saved.heading && trip != null) {
                viewModelScope.launch {
                    runCatching { trips.updateTrip(UpdateTripRequest(ctx.householdId, trip, TripStatus.SHOPPING.wire)) }
                }
            }
        }
        if (tripId.value == null) tripId.value = startTrip(ctx, lid)
        session.startShopping(ActiveShopping(lid, tripId.value, heading = false, storeName = storeName))
        started.value = true
        // Keep the ongoing trip notification (Live Update on Android 16+) in step with the list.
        viewModelScope.launch {
            state.collect { s -> syncNotification(ctx, s) }
        }
    }

    private suspend fun syncNotification(ctx: HouseholdContext, s: ShoppingUiState) {
        val trip = tripId.value ?: return
        if (!s.loaded || s.summary != null || _tripGone.value) return
        // Ended elsewhere ("Done" on the notification, or the server): never post it again.
        if (session.current().shopping?.tripId != trip) {
            tripNotifications.cancel()
            _tripGone.value = true
            return
        }
        val toBuy = s.toBuy.flatMap { it.second }
        tripNotifications.show(
            TripNotice(
                householdId = ctx.householdId,
                tripId = trip,
                status = TripStatus.SHOPPING,
                storeName = storeName,
                total = toBuy.size + s.claimedByOthers.size + s.inCart.size + s.missing.size,
                done = s.inCart.size,
                nextItems = toBuy.take(4).map { it.title },
            ),
        )
    }

    /** null when the server can't be reached quickly: Shopping Mode runs locally instead. */
    private suspend fun startTrip(ctx: HouseholdContext, lid: String): String? = withTimeoutOrNull(START_TIMEOUT_MS) {
        runCatching {
            trips.startTrip(
                StartTripRequest(
                    householdId = ctx.householdId,
                    status = TripStatus.SHOPPING.wire,
                    auto = false,
                    listIds = listOf(lid),
                    platform = Platform.ANDROID.wire,
                    deviceId = session.deviceId(),
                ),
            ).tripId
        }.getOrNull()
    }

    private fun build(
        listItems: ListItems,
        ctx: HouseholdContext,
        name: String,
        trip: String?,
        ticked: Set<String>,
        sum: ShoppingSummary?,
        st: Boolean,
    ): ShoppingUiState {
        val sections = ListSectionsBuilder.build(listItems, ctx)
        val all = sections.groups.flatMap { it.second }
        val othersClaimed = all.filter { it.item.status == ItemStatus.CLAIMED && !it.claimedByMe }
        return ShoppingUiState(
            listName = name,
            toBuy = sections.groups
                .map { (cat, rows) -> cat to rows.filterNot { it in othersClaimed } }
                .filter { it.second.isNotEmpty() },
            claimedByOthers = othersClaimed,
            inCart = sections.done.filter { (trip != null && it.item.doneTripId == trip) || it.item.id in ticked },
            missing = sections.missing,
            localMode = st && trip == null,
            summary = sum,
            loaded = true,
        )
    }

    // --- Actions

    /** Tap a row: into the cart (or back out of it). */
    fun tick(row: ItemRowUi) {
        if (row.item.status == ItemStatus.DONE) {
            move(row, ItemStatus.OPEN, undoMessage = null)
            tickedHere.update { it - row.item.id }
        } else if (move(row, ItemStatus.DONE, R.string.undo_done)) {
            tickedHere.update { it + row.item.id }
        }
    }

    fun markMissing(row: ItemRowUi) {
        move(row, ItemStatus.MISSING, R.string.undo_missing)
    }

    fun can(row: ItemRowUi, to: ItemStatus): Boolean {
        val ctx = context.value ?: return false
        return StatusRules.canTransition(row.item.status, to, ctx.role, row.item.claimedBy == ctx.uid)
    }

    private fun move(row: ItemRowUi, to: ItemStatus, undoMessage: Int?): Boolean {
        val ctx = context.value ?: return false
        val lid = listId.value ?: return false
        val from = row.item.status
        val isClaimer = row.item.claimedBy == ctx.uid
        if (!StatusRules.canTransition(from, to, ctx.role, isClaimer)) return false
        items.update(ctx.householdId, lid, row.item.id, ItemPatches.status(to, ctx.uid, tripId.value))
        if (undoMessage != null) {
            StatusRules.undoTarget(from, to, ctx.role, isClaimer)?.let { back ->
                undoQueue.offer(undoMessage, undo = {
                    items.update(ctx.householdId, lid, row.item.id, ItemPatches.status(back, ctx.uid))
                    tickedHere.update { it - row.item.id }
                })
            }
        }
        return true
    }

    fun settle(undone: Boolean) = undoQueue.settle(undone)

    /** Done: summary at once; the server ends the trip when reachable (missing → open there). */
    fun finish() {
        val ctx = context.value ?: return
        val lid = listId.value ?: return
        undoQueue.settle(undone = false)
        savedState[KEY_FINISHED] = true
        val s = state.value
        val rest = s.toBuy.flatMap { it.second } + s.claimedByOthers + s.missing
        summary.value = ShoppingSummary(bought = s.inCart, missing = s.missing, rest = rest)
        val trip = tripId.value
        tripNotifications.cancel()
        if (trip != null) {
            tripEnder.endTrip(ctx.householdId, trip)
        } else {
            // Local mode: no server trip will reopen them, so do it here (missing → open is allowed).
            s.missing.forEach { items.update(ctx.householdId, lid, it.item.id, ItemPatches.status(ItemStatus.OPEN, ctx.uid)) }
        }
        viewModelScope.launch { session.endShopping() }
    }

    private companion object {
        const val START_TIMEOUT_MS = 8_000L
        const val KEY_TICKED = "ticked"
        const val KEY_FINISHED = "finished"
    }
}
