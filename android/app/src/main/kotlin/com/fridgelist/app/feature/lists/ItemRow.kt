package com.fridgelist.app.feature.lists

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fridgelist.app.R
import com.fridgelist.core.model.ItemStatus
import kotlinx.coroutines.launch

/** Everything a row can do. The screen wires these to [ListDetailViewModel]. */
interface ItemActions {
    fun can(row: ItemRowUi, to: ItemStatus): Boolean
    fun canDelete(row: ItemRowUi): Boolean
    fun toggleDone(row: ItemRowUi)
    fun markMissing(row: ItemRowUi)
    fun reopen(row: ItemRowUi)
    fun claim(row: ItemRowUi)
    fun unclaim(row: ItemRowUi)
    fun approve(row: ItemRowUi)
    fun decline(row: ItemRowUi)
    fun delete(row: ItemRowUi)
    fun edit(row: ItemRowUi)
}

@Composable
fun ItemRow(row: ItemRowUi, actions: ItemActions) {
    val canDone = actions.can(row, ItemStatus.DONE)
    val canMissing = actions.can(row, ItemStatus.MISSING)
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = canDone,
        enableDismissFromEndToStart = canMissing,
        // Swipes act and spring back; the list itself moves the item to its new section.
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> actions.toggleDone(row)
                SwipeToDismissBoxValue.EndToStart -> actions.markMissing(row)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            scope.launch { swipe.snapTo(SwipeToDismissBoxValue.Settled) }
        },
        backgroundContent = { SwipeBackground(swipe.dismissDirection) },
    ) {
        ItemRowContent(row, actions)
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val (color, label, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Triple(MaterialTheme.colorScheme.primaryContainer, R.string.action_done, Alignment.CenterStart)
        SwipeToDismissBoxValue.EndToStart -> Triple(MaterialTheme.colorScheme.secondaryContainer, R.string.action_missing, Alignment.CenterEnd)
        SwipeToDismissBoxValue.Settled -> Triple(MaterialTheme.colorScheme.surface, null, Alignment.Center)
    }
    Box(Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp), contentAlignment = alignment) {
        label?.let { Text(stringResource(it), style = MaterialTheme.typography.labelLarge) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRowContent(row: ItemRowUi, actions: ItemActions) {
    var menu by remember { mutableStateOf(false) }
    val done = row.item.status == ItemStatus.DONE
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = { actions.edit(row) }, onLongClick = { menu = true })
            .heightIn(min = 56.dp)
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.item.status == ItemStatus.PENDING) {
            Box(Modifier.size(48.dp))
        } else {
            Checkbox(
                checked = done,
                onCheckedChange = { actions.toggleDone(row) },
                enabled = actions.can(row, if (done) ItemStatus.OPEN else ItemStatus.DONE),
                // TalkBack reads "<item>, checkbox, checked/not checked".
                modifier = Modifier.semantics { contentDescription = row.title },
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            row.original?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MetaLine(row)
        }
        if (row.item.urgent) {
            Text(
                stringResource(R.string.item_urgent),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (row.pendingSync) {
            val notSynced = stringResource(R.string.item_not_synced)
            Icon(
                painterResource(R.drawable.ic_sync_pending),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp).semantics { contentDescription = notSynced },
            )
        }
        if (row.item.status == ItemStatus.PENDING) {
            RequestButtons(row, actions)
        } else {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.action_more))
                }
                ItemMenu(row, actions, expanded = menu, onDismiss = { menu = false })
            }
        }
    }
}

@Composable
private fun MetaLine(row: ItemRowUi) {
    val parts = buildList {
        quantityText(row.item.qty, row.item.unit)?.let(::add)
        row.item.notes?.let(::add)
        val askers = row.askedBy.map { it.name ?: if (it.guest) stringResource(R.string.guest_name) else "" }.filter { it.isNotEmpty() }
        if (askers.isNotEmpty()) add(stringResource(R.string.item_asked_by, askers.joinToString("، ".takeIf { isRtl() } ?: ", ")))
        when {
            row.claimedByMe -> add(stringResource(R.string.item_claimed_by_you))
            row.item.status == ItemStatus.CLAIMED && row.claimedByOther != null -> add(stringResource(R.string.item_claimed_by, row.claimedByOther))
        }
    }
    if (parts.isNotEmpty()) {
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun isRtl(): Boolean = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl

@Composable
private fun RequestButtons(row: ItemRowUi, actions: ItemActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (actions.canDelete(row)) TextButton(onClick = { actions.decline(row) }) { Text(stringResource(R.string.action_reject)) }
        if (actions.can(row, ItemStatus.OPEN)) OutlinedButton(onClick = { actions.approve(row) }) { Text(stringResource(R.string.action_approve)) }
    }
}

@Composable
private fun ItemMenu(row: ItemRowUi, actions: ItemActions, expanded: Boolean, onDismiss: () -> Unit) {
    val status = row.item.status
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (status == ItemStatus.OPEN && actions.can(row, ItemStatus.CLAIMED)) {
            MenuItem(R.string.action_claim, onDismiss) { actions.claim(row) }
        }
        if (status == ItemStatus.CLAIMED && actions.can(row, ItemStatus.OPEN)) {
            MenuItem(R.string.action_unclaim, onDismiss) { actions.unclaim(row) }
        }
        if (status != ItemStatus.DONE && actions.can(row, ItemStatus.DONE)) {
            MenuItem(R.string.action_done, onDismiss) { actions.toggleDone(row) }
        }
        if (actions.can(row, ItemStatus.MISSING)) {
            MenuItem(R.string.action_missing, onDismiss) { actions.markMissing(row) }
        }
        if ((status == ItemStatus.DONE || status == ItemStatus.MISSING) && actions.can(row, ItemStatus.OPEN)) {
            MenuItem(R.string.action_reopen, onDismiss) { actions.reopen(row) }
        }
        MenuItem(R.string.action_edit, onDismiss) { actions.edit(row) }
        if (actions.canDelete(row)) {
            MenuItem(R.string.action_delete, onDismiss) { actions.delete(row) }
        }
    }
}

@Composable
private fun MenuItem(@StringRes label: Int, onDismiss: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onDismiss(); action() })
}
