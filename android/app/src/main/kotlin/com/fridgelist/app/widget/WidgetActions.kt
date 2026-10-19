package com.fridgelist.app.widget

import android.content.Context
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fridgelist.app.R
import com.fridgelist.app.quickadd.QuickAdd
import com.fridgelist.core.data.auth.AuthRepository
import com.fridgelist.core.data.firestore.ItemPatches
import com.fridgelist.core.data.items.ItemRepository
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.model.ItemStatus
import com.fridgelist.core.model.WidgetSnapshot
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetDeps {
    fun writer(): WidgetSnapshotWriter
    fun items(): ItemRepository
    fun session(): SessionStore
    fun auth(): AuthRepository
}

/**
 * A widget checkbox (ARCHITECTURE §8.3): the item leaves the widget at once, the SDK writes `done`
 * (offline-first), we wait briefly for the server so other phones see it within seconds, then the
 * snapshot is rebuilt from Firestore.
 */
class ToggleItemCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val listId = parameters[LIST] ?: return
        val itemId = parameters[ITEM] ?: return
        val deps = EntryPointAccessors.fromApplication(context, WidgetDeps::class.java)
        val householdId = deps.session().current().householdId ?: return
        val uid = deps.auth().currentUser()?.uid ?: return
        deps.writer().removeItem(listId, itemId)
        // Ticked during this phone's trip on that list: it counts for the trip (CONTRACT §3.6 doneTripId).
        val tripId = deps.session().current().shopping?.takeIf { it.listId == listId }?.tripId
        deps.items().update(householdId, listId, itemId, ItemPatches.status(ItemStatus.DONE, uid, tripId))
        deps.items().flush()
        deps.writer().refreshOnce()
        Log.i("ListWidget", "toggled") // no item names in logs
    }

    companion object {
        val LIST = stringKey("listId")
        val ITEM = stringKey("itemId")
    }
}

/** `list_changed` pushes (CONTRACT §5.1) refresh widgets while the app isn't running. */
fun interface WidgetRefresher {
    fun refresh()
}

class WorkManagerWidgetRefresher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : WidgetRefresher {
    override fun refresh() {
        // One at a time; a second push while it runs is covered by the fresh read.
        WorkManager.getInstance(context)
            .enqueueUniqueWork("widget-refresh", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build())
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, WidgetDeps::class.java).writer().refreshOnce()
        return Result.success()
    }
}

/** Long-press shortcuts (ARCHITECTURE §8.3): static ones in `xml/shortcuts.xml`, one dynamic per list. */
object Shortcuts {
    private const val MAX_LISTS = 3

    fun update(context: Context, snapshot: WidgetSnapshot) {
        val shortcuts = snapshot.lists.take(MAX_LISTS).mapIndexed { rank, list ->
            ShortcutInfoCompat.Builder(context, "list_${list.id}")
                .setShortLabel(list.name.take(SHORT_LABEL_MAX))
                .setLongLabel(context.getString(R.string.shortcut_list_long, list.name))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_add))
                .setIntent(QuickAdd.intent(context, list.id, voice = true, via = null))
                .setRank(rank)
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    fun clear(context: Context) {
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }

    private const val SHORT_LABEL_MAX = 25
}
