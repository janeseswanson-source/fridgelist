package com.fridgelist.app.feature.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgelist.app.R
import com.fridgelist.core.model.ItemStatus
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailScreen(
    state: ListDetailUiState,
    actions: ItemActions,
    snackbar: SnackbarHostState,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.listName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            val label = stringResource(R.string.action_add)
            ExtendedFloatingActionButton(
                onClick = onAdd,
                // This Material version does not expose the FAB text to TalkBack; label it explicitly.
                modifier = Modifier.semantics { contentDescription = label },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.action_add)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val sections = state.sections
        if (state.loaded && sections.isEmpty) {
            EmptyList(Modifier.padding(padding).fillMaxSize())
            return@Scaffold
        }
        var showDone by rememberSaveable { mutableStateOf(false) }
        val requestsTitle = stringResource(R.string.section_requests)
        val missingTitle = stringResource(R.string.section_missing)
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            section(requestsTitle, sections.requests, actions)
            sections.groups.forEach { (category, rows) -> section(category, rows, actions) }
            section(missingTitle, sections.missing, actions)
            if (sections.done.isNotEmpty()) {
                item(key = "done-header") {
                    DoneHeader(count = sections.done.size, expanded = showDone, onToggle = { showDone = !showDone })
                }
                if (showDone) items(sections.done, key = { it.item.id }) { ItemRow(it, actions) }
            }
        }
    }
}

private fun LazyListScope.section(title: String, rows: List<ItemRowUi>, actions: ItemActions) {
    if (rows.isEmpty()) return
    item(key = "header-$title") { SectionHeader(title) }
    items(rows, key = { it.item.id }) { ItemRow(it, actions) }
}

private fun LazyListScope.section(category: com.fridgelist.core.model.Category, rows: List<ItemRowUi>, actions: ItemActions) {
    if (rows.isEmpty()) return
    item(key = "cat-${category.wire}") { SectionHeader(stringResource(category.label())) }
    items(rows, key = { it.item.id }) { ItemRow(it, actions) }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun DoneHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.section_done),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Text(Quantities.format(count.toDouble(), locale), style = MaterialTheme.typography.labelLarge)
        Text(if (expanded) "▴" else "▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyList(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.list_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.list_empty_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailRoute(onBack: () -> Unit, vm: ListDetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<ItemRowUi?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    val undoLabel = stringResource(R.string.action_undo)
    val undoMessage = undo?.let { stringResource(it.message) }
    LaunchedEffect(undo?.id) {
        if (undo == null || undoMessage == null) return@LaunchedEffect
        // Long: Undo must stay reachable for TalkBack users and slower hands.
        val result = snackbar.showSnackbar(undoMessage, actionLabel = undoLabel, duration = SnackbarDuration.Long)
        vm.settle(undone = result == SnackbarResult.ActionPerformed)
    }

    val actions = remember(vm) {
        object : ItemActions {
            override fun can(row: ItemRowUi, to: ItemStatus) = vm.can(row, to)
            override fun canDelete(row: ItemRowUi) = vm.canDelete(row)
            override fun toggleDone(row: ItemRowUi) = vm.toggleDone(row)
            override fun markMissing(row: ItemRowUi) = vm.markMissing(row)
            override fun reopen(row: ItemRowUi) = vm.reopen(row)
            override fun claim(row: ItemRowUi) = vm.claim(row)
            override fun unclaim(row: ItemRowUi) = vm.unclaim(row)
            override fun approve(row: ItemRowUi) = vm.approve(row)
            override fun decline(row: ItemRowUi) = vm.decline(row)
            override fun delete(row: ItemRowUi) = vm.delete(row)
            override fun edit(row: ItemRowUi) {
                editing = row
            }
        }
    }

    ListDetailScreen(state, actions, snackbar, onAdd = { adding = true }, onBack = onBack)

    editing?.let { row ->
        ModalBottomSheet(onDismissRequest = { editing = null }) {
            EditItemContent(row, canRename = vm.canRename(row), onSave = { edit -> vm.edit(row, edit); editing = null })
        }
    }
    if (adding) {
        ModalBottomSheet(onDismissRequest = { adding = false }) {
            AddItemsRoute(onDone = { adding = false })
        }
    }
}
