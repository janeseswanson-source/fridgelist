package com.fridgelist.app.feature.home

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgelist.app.R
import com.fridgelist.app.feature.planner.PlanIftarCard
import com.fridgelist.app.feature.planner.PlannerEntryViewModel
import com.fridgelist.app.feature.lists.ListRowUi
import com.fridgelist.app.feature.lists.ListsUiState
import com.fridgelist.app.feature.lists.ListsViewModel
import com.fridgelist.app.feature.lists.label
import com.fridgelist.app.ui.common.LanguageOption
import com.fridgelist.app.ui.common.LanguageRadioList
import com.fridgelist.app.ui.common.PrimaryButton
import com.fridgelist.app.ui.common.SectionLabel
import com.fridgelist.core.model.Limits
import com.fridgelist.core.model.ListType
import com.fridgelist.core.model.clip

/** Home: the household's lists with what is left to buy (PRODUCT_SPEC F2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: ListsUiState,
    onOpenList: (ListRowUi) -> Unit,
    onNewList: () -> Unit,
    onOpenMembers: () -> Unit,
    onOpenSettings: () -> Unit,
    onResumeShopping: (listId: String) -> Unit = {},
    onPlanIftar: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.household?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenMembers) {
                        Icon(painterResource(R.drawable.ic_group), contentDescription = stringResource(R.string.home_members))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.canCreate) {
                val label = stringResource(R.string.lists_new)
            ExtendedFloatingActionButton(
                    onClick = onNewList,
                // This Material version does not expose the FAB text to TalkBack; label it explicitly.
                modifier = Modifier.semantics { contentDescription = label },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.lists_new)) },
                )
            }
        },
    ) { padding ->
        if (state.loaded && state.lists.isEmpty()) {
            EmptyState(Modifier.padding(padding).fillMaxSize())
        } else {
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
                state.shoppingList?.let { list ->
                    item(key = "resume") { ResumeShopping(list.name) { onResumeShopping(list.id) } }
                }
                onPlanIftar?.let { plan -> item(key = "iftar") { PlanIftarCard(plan) } }
                items(state.lists, key = { it.list.id }) { row ->
                    ListRow(row, onClick = { onOpenList(row) })
                    HorizontalDivider(Modifier.padding(start = 72.dp))
                }
            }
        }
    }
}

@Composable
private fun ListRow(row: ListRowUi, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) {
                val emoji = row.list.emoji
                if (emoji != null) {
                    Text(emoji, style = MaterialTheme.typography.titleLarge)
                } else {
                    Icon(painterResource(R.drawable.ic_cart), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(row.list.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(row.list.type.label()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            pluralStringResource(R.plurals.list_open_count, row.openCount, row.openCount),
            style = MaterialTheme.typography.labelLarge,
            color = if (row.openCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResumeShopping(listName: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_cart), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                stringResource(R.string.shop_resume, listName),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    painterResource(R.drawable.ic_cart),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(24.dp).size(48.dp),
                )
            }
            Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.home_empty_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun NewListContent(onCreate: (name: String, type: ListType, emoji: String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(ListType.GROCERY) }
    var emoji by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.lists_new), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.clip(Limits.LIST_NAME_MAX) },
            label = { Text(stringResource(R.string.new_list_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = emoji,
            onValueChange = { emoji = it.take(8) },
            label = { Text(stringResource(R.string.new_list_emoji)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SectionLabel(stringResource(R.string.new_list_type))
        LanguageRadioList(
            options = ListType.entries.map { LanguageOption(it.wire, it.label()) },
            selected = type.wire,
            onSelect = { type = ListType.fromWire(it) },
        )
        PrimaryButton(stringResource(R.string.create_button), onClick = { onCreate(name, type, emoji) }, enabled = name.isNotBlank())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRoute(
    onOpenList: (listId: String) -> Unit,
    onResumeShopping: (listId: String) -> Unit,
    onOpenMembers: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlanIftar: () -> Unit = {},
    vm: ListsViewModel = hiltViewModel(),
    planner: PlannerEntryViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val plannerAvailable by planner.available.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    HomeScreen(
        state, { onOpenList(it.list.id) }, { creating = true }, onOpenMembers, onOpenSettings, onResumeShopping,
        onPlanIftar = if (plannerAvailable && state.canCreate) onPlanIftar else null,
    )
    if (creating) {
        ModalBottomSheet(onDismissRequest = { creating = false }) {
            NewListContent { name, type, emoji ->
                val id = vm.createList(name, type, emoji)
                creating = false
                id?.let(onOpenList)
            }
        }
    }
}
