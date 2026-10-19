package com.fridgelist.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.fridgelist.app.MainActivity
import com.fridgelist.app.R
import com.fridgelist.app.ui.theme.FridgeListTheme
import com.fridgelist.core.data.widget.WidgetSnapshotStore
import com.fridgelist.core.model.SnapshotList
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Picks the list a widget shows (ARCHITECTURE §8.3). Reconfigurable on Android 12+. */
@AndroidEntryPoint
class WidgetConfigActivity : AppCompatActivity() {

    @Inject lateinit var snapshots: WidgetSnapshotStore

    @Inject lateinit var writer: WidgetSnapshotWriter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves no half-set-up widget behind.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        lifecycleScope.launch { writer.refreshOnce() }
        setContent {
            FridgeListTheme {
                val snapshot by snapshots.snapshot.collectAsState(initial = null)
                ConfigScreen(
                    lists = snapshot?.lists,
                    onPick = { pick(appWidgetId, it.id) },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                )
            }
        }
    }

    private fun pick(appWidgetId: Int, listId: String) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@WidgetConfigActivity, glanceId) { it[ListWidget.LIST_ID] = listId }
            ListWidget().update(this@WidgetConfigActivity, glanceId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(lists: List<SnapshotList>?, onPick: (SnapshotList) -> Unit, onOpenApp: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_config_title)) }) }) { padding ->
        if (lists.isNullOrEmpty()) {
            Text(
                stringResource(R.string.widget_open_app),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(padding).padding(24.dp).clickable(onClick = onOpenApp),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding)) {
            items(lists, key = { it.id }) { list ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable { onPick(list) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text(list.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        pluralStringResource(R.plurals.widget_to_buy, list.openCount, list.openCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
