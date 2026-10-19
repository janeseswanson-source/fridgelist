package com.fridgelist.app.quickadd

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.fridgelist.app.BuildConfig
import com.fridgelist.app.MainActivity
import com.fridgelist.app.R
import com.fridgelist.app.feature.lists.AddItemsRoute
import com.fridgelist.app.ui.theme.FridgeListTheme
import com.fridgelist.app.widget.WidgetRefresher
import com.fridgelist.core.data.items.ListRepository
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.data.widget.WidgetSnapshotStore
import com.fridgelist.core.model.AddedVia
import com.fridgelist.core.model.Links
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Builds the internal add link (CONTRACT §6) that widgets, the tile and shortcuts open. */
object QuickAdd {
    const val EXTRA_LIST_ID = "listId"
    const val EXTRA_VIA = "via"

    fun intent(context: Context, listId: String?, voice: Boolean, via: AddedVia?): Intent =
        Intent(Intent.ACTION_VIEW, Links.addUrl(BuildConfig.URL_SCHEME, listId, voice).toUri())
            .setClass(context, QuickAddActivity::class.java)
            .apply { if (via != null) putExtra(EXTRA_VIA, via.wire) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}

/**
 * Transparent, voice-first Quick Add (ARCHITECTURE §8.3) over whatever is on screen: the same add
 * sheet as the list screen, for the link's list or the first one. Writes are offline-first.
 */
@AndroidEntryPoint
class QuickAddActivity : AppCompatActivity() {

    @Inject lateinit var session: SessionStore

    @Inject lateinit var snapshots: WidgetSnapshotStore

    @Inject lateinit var lists: ListRepository

    @Inject lateinit var widgets: WidgetRefresher

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = Links.add(intent?.dataString.orEmpty(), BuildConfig.URL_SCHEME) ?: Links.AddLink(null, voice = true)
        setContent {
            FridgeListTheme {
                var listId by remember { mutableStateOf<String?>(intent.getStringExtra(QuickAdd.EXTRA_LIST_ID)) }
                LaunchedEffect(Unit) {
                    if (listId != null) return@LaunchedEffect
                    val resolved = resolveList(link.listId)
                    if (resolved == null) {
                        // Not signed in or no household yet: the app takes it from there.
                        startActivity(Intent(this@QuickAddActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    } else {
                        // AddItemsViewModel reads its list from the activity's extras (SavedStateHandle).
                        intent.putExtra(QuickAdd.EXTRA_LIST_ID, resolved)
                        listId = resolved
                    }
                }
                if (listId != null) {
                    ModalBottomSheet(onDismissRequest = ::finish, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        AddItemsRoute(
                            onDone = {
                                // Our own adds bring no list_changed push: refresh the widgets here.
                                widgets.refresh()
                                finish()
                            },
                            startWithVoice = link.voice,
                        )
                    }
                }
            }
        }
    }

    /** The link's list if it still exists, else the widget's first list, else the household's first. */
    private suspend fun resolveList(requested: String?): String? {
        val householdId = session.current().householdId ?: return null
        val known = snapshots.current()?.takeIf { it.householdId == householdId }?.lists?.map { it.id }
            ?: withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { lists.lists(householdId).first().map { it.id } }
            ?: return requested
        return requested?.takeIf { it in known } ?: known.firstOrNull()
    }

    private companion object {
        const val LOOKUP_TIMEOUT_MS = 3_000L
    }
}

/** Quick Settings tile "Add to list" (ARCHITECTURE §8.3). */
class QuickAddTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            label = getString(R.string.tile_label)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val intent = QuickAdd.intent(this, listId = null, voice = true, via = AddedVia.QS_TILE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
