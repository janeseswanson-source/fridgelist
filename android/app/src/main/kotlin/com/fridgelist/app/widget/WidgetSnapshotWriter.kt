package com.fridgelist.app.widget

import android.content.Context
import android.util.Log
import androidx.core.os.ConfigurationCompat
import com.fridgelist.app.feature.lists.HouseholdContext
import com.fridgelist.app.feature.lists.HouseholdContextSource
import com.fridgelist.app.feature.lists.ListSectionsBuilder
import com.fridgelist.app.feature.lists.quantityText
import com.fridgelist.core.data.items.ItemRepository
import com.fridgelist.core.data.items.ListItems
import com.fridgelist.core.data.items.ListRepository
import com.fridgelist.core.data.session.ActiveShopping
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.data.widget.WidgetSnapshotStore
import com.fridgelist.core.model.ItemDoc
import com.fridgelist.core.model.ItemStatus
import com.fridgelist.core.model.ListDoc
import com.fridgelist.core.model.SnapshotItem
import com.fridgelist.core.model.SnapshotList
import com.fridgelist.core.model.SnapshotTrip
import com.fridgelist.core.model.WidgetSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** CONTRACT §7, built from the same sections and display rule as the list screen. Pure. */
object SnapshotBuilder {
    fun build(
        ctx: HouseholdContext,
        lists: List<ListDoc>,
        items: Map<String, ListItems>,
        shopping: ActiveShopping?,
        nowSeconds: Long,
        subtitle: (ItemDoc) -> String?,
    ): WidgetSnapshot {
        val snapshotLists = lists.map { list ->
            // To buy (open + claimed), in display order: category order, urgent first, manual order.
            val rows = ListSectionsBuilder.build(items[list.id] ?: ListItems(), ctx).groups.flatMap { it.second }
            SnapshotList(
                id = list.id,
                name = list.name,
                openCount = rows.size,
                items = rows.take(WidgetSnapshot.MAX_ITEMS_PER_LIST).map { row ->
                    SnapshotItem(row.item.id, row.title, subtitle(row.item), row.category.wire, row.item.status.wire, row.item.urgent)
                },
            )
        }
        val trip = shopping?.takeIf { !it.heading }?.tripId?.let { tripId ->
            val listItems = items[shopping.listId]?.items.orEmpty()
            SnapshotTrip(
                id = tripId,
                storeName = shopping.storeName,
                remaining = listItems.count { it.status == ItemStatus.OPEN || it.status == ItemStatus.CLAIMED },
                done = listItems.count { it.status == ItemStatus.DONE && it.doneTripId == tripId },
            )
        }
        return WidgetSnapshot(
            updatedAt = nowSeconds,
            viewerLang = ctx.viewerLang,
            householdId = ctx.householdId,
            lists = snapshotLists,
            activeTrip = trip,
        )
    }

    /** Ticked on a widget: gone from the snapshot at once, before the server round trip. */
    fun withoutItem(snapshot: WidgetSnapshot, listId: String, itemId: String): WidgetSnapshot =
        if (snapshot.lists.none { list -> list.id == listId && list.items.any { it.id == itemId } }) snapshot
        else snapshot.copy(
            lists = snapshot.lists.map { list ->
                if (list.id != listId || list.items.none { it.id == itemId }) list
                else list.copy(openCount = (list.openCount - 1).coerceAtLeast(0), items = list.items.filterNot { it.id == itemId })
            },
            activeTrip = snapshot.activeTrip?.let { t -> t.copy(remaining = (t.remaining - 1).coerceAtLeast(0), done = t.done + 1) },
        )
}

/**
 * Keeps the snapshot current: [observe] while the app is on screen (debounced 1 s, CONTRACT §7),
 * [refreshOnce] after a widget tap or a `list_changed` push. Each write updates the widgets and the
 * per-list shortcuts.
 */
/** Sign-out seam: widgets and shortcuts forget the household. */
fun interface WidgetCleaner {
    suspend fun clear()
}

@Singleton
class WidgetSnapshotWriter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val contextSource: HouseholdContextSource,
    private val lists: ListRepository,
    private val items: ItemRepository,
    private val session: SessionStore,
    private val store: WidgetSnapshotStore,
) : WidgetCleaner {
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun snapshots(): Flow<WidgetSnapshot> =
        contextSource.context.flatMapLatest { ctx ->
            lists.lists(ctx.householdId).catch { emit(emptyList()) }.flatMapLatest { listDocs ->
                val perList = listDocs.map { list -> items.items(ctx.householdId, list.id).catch { emit(ListItems()) }.map { list.id to it } }
                val all = if (perList.isEmpty()) flowOf(emptyMap()) else combine(perList) { it.toMap() }
                combine(all, session.session.map { it.shopping }.distinctUntilChanged()) { byList, shopping ->
                    SnapshotBuilder.build(ctx, listDocs, byList, shopping, System.currentTimeMillis() / 1000, ::subtitle)
                }
            }
        }

    @OptIn(FlowPreview::class)
    suspend fun observe() {
        snapshots().debounce(DEBOUNCE_MS).distinctUntilChanged { a, b -> a.copy(updatedAt = 0) == b.copy(updatedAt = 0) }.collect { write(it) }
    }

    /** Lets Firestore answer from cache and then the server, then writes once. Signed out → cleared. */
    @OptIn(FlowPreview::class)
    suspend fun refreshOnce() {
        if (session.current().householdId == null) return clear()
        val snapshot = withTimeoutOrNull(REFRESH_TIMEOUT_MS) { snapshots().debounce(SETTLE_MS).first() }
        if (snapshot != null) write(snapshot) else Log.i(TAG, "refresh timed out")
    }

    /** A widget tick: show it at once, then write it (the caller refreshes after). */
    suspend fun removeItem(listId: String, itemId: String) {
        store.current()?.let { write(SnapshotBuilder.withoutItem(it, listId, itemId)) }
    }

    override suspend fun clear() {
        store.clear()
        Widgets.updateAll(context)
        Shortcuts.clear(context)
    }

    private suspend fun write(snapshot: WidgetSnapshot) {
        store.write(snapshot)
        Widgets.updateAll(context)
        Shortcuts.update(context, snapshot)
    }

    private fun subtitle(item: ItemDoc): String? {
        val resources = context.resources
        val locale = ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.getDefault()
        return quantityText(resources, locale, item.qty, item.unit)
    }

    private companion object {
        const val TAG = "WidgetSnapshot"
        const val DEBOUNCE_MS = 1_000L
        const val SETTLE_MS = 1_500L
        const val REFRESH_TIMEOUT_MS = 8_000L
    }
}
