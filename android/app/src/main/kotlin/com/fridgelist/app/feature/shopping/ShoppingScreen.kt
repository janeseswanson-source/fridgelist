package com.fridgelist.app.feature.shopping

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgelist.app.R
import com.fridgelist.app.feature.lists.ItemRowUi
import com.fridgelist.app.feature.lists.label
import com.fridgelist.app.feature.lists.quantityText
import com.fridgelist.app.platform.Share
import com.fridgelist.core.model.ItemStatus

/** Row actions; the screen wires them to [ShoppingViewModel]. */
interface ShoppingActions {
    fun canTick(row: ItemRowUi): Boolean
    fun canMiss(row: ItemRowUi): Boolean
    fun tick(row: ItemRowUi)
    fun miss(row: ItemRowUi)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(state: ShoppingUiState, actions: ShoppingActions, snackbar: SnackbarHostState, onDone: () -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.listName.ifEmpty { stringResource(R.string.shop_title) }) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) }
                },
                actions = { Button(onClick = onDone, modifier = Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.shop_done)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (state.localMode) item(key = "local") { Banner(stringResource(R.string.shop_local_mode)) }
            if (state.loaded && state.toBuyCount == 0) {
                item(key = "all-done") { Banner(stringResource(R.string.shop_all_done)) }
            } else {
                item(key = "hint") {
                    Text(
                        stringResource(R.string.shop_tap_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            state.toBuy.forEach { (category, rows) -> group("cat-${category.wire}", { stringResource(category.label()) }, rows, actions) }
            group("missing", { stringResource(R.string.section_missing) }, state.missing, actions)
            group("others", { stringResource(R.string.shop_claimed_by_others) }, state.claimedByOthers, actions, dimmed = true)
            group("cart", { stringResource(R.string.shop_in_cart) }, state.inCart, actions)
        }
    }
}

private fun LazyListScope.group(
    key: String,
    title: @Composable () -> String,
    rows: List<ItemRowUi>,
    actions: ShoppingActions,
    dimmed: Boolean = false,
) {
    if (rows.isEmpty()) return
    item(key = "h-$key") {
        Text(
            title(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
        )
    }
    items(rows, key = { "$key-${it.item.id}" }) { ShoppingRow(it, actions, dimmed) }
}

/** One-handed: the whole row (≥ 72 dp) ticks; "couldn't find" is its own large button. */
@Composable
private fun ShoppingRow(row: ItemRowUi, actions: ShoppingActions, dimmed: Boolean) {
    val haptics = LocalHapticFeedback.current
    val inCart = row.item.status == ItemStatus.DONE
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .alpha(if (dimmed) 0.6f else 1f)
            .toggleable(
                value = inCart,
                enabled = actions.canTick(row),
                role = Role.Checkbox,
                onValueChange = {
                    haptics.performHapticFeedback(if (inCart) HapticFeedbackType.LongPress else HapticFeedbackType.Confirm)
                    actions.tick(row)
                },
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = inCart, onCheckedChange = null, modifier = Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleLarge,
                textDecoration = if (inCart) TextDecoration.LineThrough else null,
            )
            val meta = listOfNotNull(quantityText(row.item.qty, row.item.unit), row.original, row.item.notes)
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            row.claimedByOther?.let {
                Text(stringResource(R.string.item_claimed_by, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (row.item.urgent) {
            Text(stringResource(R.string.item_urgent), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
        }
        if (!inCart && row.item.status != ItemStatus.MISSING && actions.canMiss(row)) {
            TextButton(onClick = { actions.miss(row) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_missing))
            }
        }
    }
}

@Composable
private fun Banner(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
fun SummaryContent(summary: ShoppingSummary, listName: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val header = stringResource(R.string.share_rest_header, listName)
    val restLines = summary.rest.map { row ->
        "• " + listOfNotNull(row.title, quantityText(row.item.qty, row.item.unit)).joinToString(" — ")
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraLarge), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_cart), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
        }
        Text(stringResource(R.string.summary_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(pluralStringResource(R.plurals.summary_bought, summary.bought.size, summary.bought.size), style = MaterialTheme.typography.titleMedium)
        if (summary.missing.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.summary_missing, summary.missing.size, summary.missing.size), style = MaterialTheme.typography.titleMedium)
            Text(summary.missing.joinToString("، ".takeIf { isRtl() } ?: ", ") { it.title }, textAlign = TextAlign.Center)
            Text(stringResource(R.string.summary_missing_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (restLines.isNotEmpty()) {
            OutlinedButton(
                onClick = { Share.text(context, (listOf(header) + restLines).joinToString("\n")) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = null)
                Text(stringResource(R.string.summary_send_rest), modifier = Modifier.padding(start = 8.dp))
            }
        }
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.summary_close)) }
    }
}

@Composable
private fun isRtl(): Boolean = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl

@Composable
fun ShoppingRoute(onClose: () -> Unit, vm: ShoppingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // PRODUCT_SPEC F5: the screen stays on while shopping.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val undoLabel = stringResource(R.string.action_undo)
    val undoMessage = undo?.let { stringResource(it.message) }
    LaunchedEffect(undo?.id) {
        if (undo == null || undoMessage == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(undoMessage, actionLabel = undoLabel, duration = SnackbarDuration.Long)
        vm.settle(undone = result == SnackbarResult.ActionPerformed)
    }

    val actions = remember(vm) {
        object : ShoppingActions {
            override fun canTick(row: ItemRowUi) = vm.can(row, if (row.item.status == ItemStatus.DONE) ItemStatus.OPEN else ItemStatus.DONE)
            override fun canMiss(row: ItemRowUi) = vm.can(row, ItemStatus.MISSING)
            override fun tick(row: ItemRowUi) = vm.tick(row)
            override fun miss(row: ItemRowUi) = vm.markMissing(row)
        }
    }
    val tripGone by vm.tripGone.collectAsStateWithLifecycle()
    val summary = state.summary
    if (tripGone) {
        Scaffold { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.shop_trip_gone), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Button(onClick = onClose) { Text(stringResource(R.string.summary_close)) }
            }
        }
    } else if (summary != null) {
        Scaffold { padding -> Box(Modifier.padding(padding)) { SummaryContent(summary, state.listName, onClose) } }
    } else {
        ShoppingScreen(state, actions, snackbar, onDone = vm::finish, onBack = onClose)
    }
}
