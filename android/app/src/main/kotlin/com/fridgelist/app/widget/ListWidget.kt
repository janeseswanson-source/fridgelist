package com.fridgelist.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.os.ConfigurationCompat
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.fridgelist.app.MainActivity
import com.fridgelist.app.R
import com.fridgelist.app.feature.lists.Quantities
import com.fridgelist.app.quickadd.QuickAdd
import com.fridgelist.app.ui.theme.DarkColors
import com.fridgelist.app.ui.theme.LightColors
import com.fridgelist.core.data.widget.WidgetSnapshotStore
import com.fridgelist.core.model.AddedVia
import com.fridgelist.core.model.SnapshotItem
import com.fridgelist.core.model.SnapshotList
import com.fridgelist.core.model.WidgetSnapshot
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The list widget (ARCHITECTURE §8.3): 2×2 count + next 2, 4×2 next 5, 4×4 the list. It only reads
 * the CONTRACT §7 snapshot; checkboxes run [ToggleItemCallback] in the app process.
 * [preview] renders a fixed snapshot (screenshot tests, no Hilt).
 */
class ListWidget(private val preview: WidgetSnapshot? = null) : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun snapshots(): WidgetSnapshotStore
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val source: Flow<WidgetSnapshot?> =
            if (preview != null) flowOf(preview) else EntryPointAccessors.fromApplication(context, Deps::class.java).snapshots().snapshot
        provideContent {
            val snapshot by source.collectAsState(initial = preview)
            val listId = currentState<Preferences>()[LIST_ID]
            GlanceTheme(colors = ColorProviders(light = LightColors, dark = DarkColors)) {
                WidgetContent(snapshot, listId)
            }
        }
    }

    companion object {
        // Smallest size of each layout; launchers report cells minus padding (a 2×2 is often ~94 dp).
        val SMALL = DpSize(80.dp, 80.dp)
        val MEDIUM = DpSize(180.dp, 80.dp)
        val LARGE = DpSize(180.dp, 180.dp)

        /** Per-widget choice from [WidgetConfigActivity]; the first list when unset or deleted. */
        val LIST_ID = stringPreferencesKey("list_id")
    }
}

class ListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ListWidget()
}

/** The widget's UI locale (per-app language), for numbers. */
object Locales {
    fun current(context: Context): java.util.Locale =
        ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: java.util.Locale.getDefault()
}

object Widgets {
    suspend fun updateAll(context: Context) {
        runCatching { ListWidget().updateAll(context) }
    }
}

@Composable
private fun WidgetContent(snapshot: WidgetSnapshot?, listId: String?) {
    val context = LocalContext.current
    val size = LocalSize.current
    Column(
        GlanceModifier
            .fillMaxSize()
            .cornerRadius(20.dp)
            .background(GlanceTheme.colors.surface)
            .padding(10.dp),
    ) {
        val list = snapshot?.list(listId)
        if (snapshot == null || list == null) {
            Text(
                context.getString(R.string.widget_open_app),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
            )
            return@Column
        }
        val small = size.height < ListWidget.LARGE.height && size.width < ListWidget.MEDIUM.width
        if (small) SmallContent(snapshot, list) else {
            Header(snapshot, list)
            Spacer(GlanceModifier.height(4.dp))
            if (list.items.isEmpty()) AllDone() else Checklist(list, if (size.height < ListWidget.LARGE.height) list.items.take(5) else list.items)
        }
    }
}

@Composable
private fun AllDone() {
    Text(
        LocalContext.current.getString(R.string.widget_all_done),
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
    )
}

@Composable
private fun headerTitle(snapshot: WidgetSnapshot, list: SnapshotList): String {
    val context = LocalContext.current
    return snapshot.activeTrip?.let {
        context.resources.getQuantityString(R.plurals.widget_trip, it.remaining, it.storeName.ifEmpty { list.name }, it.remaining)
    } ?: list.name
}

@Composable
private fun AddButton(list: SnapshotList, size: Int) {
    val context = LocalContext.current
    Image(
        provider = ImageProvider(R.drawable.ic_add),
        contentDescription = context.getString(R.string.widget_add),
        modifier = GlanceModifier
            .size(size.dp)
            .cornerRadius((size / 2).dp)
            .background(GlanceTheme.colors.primaryContainer)
            .padding((size / 5).dp)
            .clickable(actionStartActivity(QuickAdd.intent(context, list.id, voice = true, via = AddedVia.WIDGET))),
    )
}

/** 4×2 and 4×4: one header line (name or trip · count) and the + button. */
@Composable
private fun Header(snapshot: WidgetSnapshot, list: SnapshotList) {
    val context = LocalContext.current
    val count = context.resources.getQuantityString(R.plurals.widget_to_buy, list.openCount, list.openCount)
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight().clickable(actionStartActivity(Intent(context, MainActivity::class.java)))) {
            Text(headerTitle(snapshot, list), maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp))
            Text(count, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        }
        AddButton(list, 32)
    }
}

/** 2×2: the count big, the + button and the next two items (a 2×2 cell is ~94 dp tall). */
@Composable
private fun SmallContent(snapshot: WidgetSnapshot, list: SnapshotList) {
    val context = LocalContext.current
    val open = GlanceModifier.clickable(actionStartActivity(Intent(context, MainActivity::class.java)))
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            Quantities.format(list.openCount.toDouble(), Locales.current(context)),
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 22.sp),
            modifier = open,
        )
        Spacer(GlanceModifier.defaultWeight())
        AddButton(list, 28)
    }
    if (list.items.isEmpty()) AllDone()
    list.items.take(2).forEach { item ->
        Text("• \u2068" + item.title + "\u2069", maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp), modifier = open)
    }
}

@Composable
private fun Checklist(list: SnapshotList, items: List<SnapshotItem>) {
    LazyColumn {
        items(items, itemId = { it.id.hashCode().toLong() }) { item ->
            CheckBox(
                checked = false,
                onCheckedChange = actionRunCallback<ToggleItemCallback>(
                    actionParametersOf(ToggleItemCallback.LIST to list.id, ToggleItemCallback.ITEM to item.id),
                ),
                text = itemLine(item),
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                modifier = GlanceModifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * "❗ حليب · 2 L". Title and quantity are bidi-isolated (FSI…PDI): an Arabic name next to a Latin
 * unit must not reorder into "2 · حليب L" in a Turkish or English widget, and the reverse in Arabic.
 */
internal fun itemLine(item: SnapshotItem): String =
    (if (item.urgent) "❗ " else "") + listOfNotNull(item.title, item.subtitle).joinToString(" · ") { "\u2068$it\u2069" }

/** Keys for [ToggleItemCallback]. */
internal fun stringKey(name: String) = ActionParameters.Key<String>(name)
