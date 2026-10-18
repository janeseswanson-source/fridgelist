package com.fridgelist.app.feature.lists

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgelist.app.R
import com.fridgelist.core.data.firestore.ItemEdit
import com.fridgelist.core.data.firestore.ItemPatches
import com.fridgelist.core.data.items.ItemRepository
import com.fridgelist.core.data.items.ListItems
import com.fridgelist.core.data.items.ListRepository
import com.fridgelist.core.model.ItemStatus
import com.fridgelist.core.model.Role
import com.fridgelist.core.model.StatusRules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class ListDetailUiState(
    val listName: String = "",
    val sections: ListSections = ListSections(),
    val role: Role = Role.KID,
    val uid: String = "",
    val loaded: Boolean = false,
)

/** One pending undo; [id] makes repeated identical messages show again. */
data class UndoUi(@param:StringRes val message: Int, val id: Long)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ListDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    contextSource: HouseholdContextSource,
    lists: ListRepository,
    private val items: ItemRepository,
) : ViewModel() {

    val listId: String = checkNotNull(savedState.get<String>("listId"))

    /** Deletes wait for the Undo snackbar to go away before they are written. */
    private val hidden = MutableStateFlow<Set<String>>(emptySet())

    private val context = contextSource.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<ListDetailUiState> = contextSource.context.flatMapLatest { ctx ->
        combine(
            items.items(ctx.householdId, listId).onStart { emit(ListItems()) }.catch { emit(ListItems()) },
            lists.lists(ctx.householdId).map { all -> all.firstOrNull { it.id == listId }?.name.orEmpty() }
                .onStart { emit("") }.catch { emit("") },
            hidden,
        ) { listItems, name, hiddenIds ->
            ListDetailUiState(
                listName = name,
                sections = ListSectionsBuilder.build(listItems, ctx, hiddenIds),
                role = ctx.role,
                uid = ctx.uid,
                loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListDetailUiState())

    private val _undo = MutableStateFlow<UndoUi?>(null)
    val undo: StateFlow<UndoUi?> = _undo.asStateFlow()
    private var onUndo: (() -> Unit)? = null
    private var onCommit: (() -> Unit)? = null

    // --- Status changes (CONTRACT §3.6)

    fun toggleDone(row: ItemRowUi) =
        if (row.item.status == ItemStatus.DONE) move(row, ItemStatus.OPEN, R.string.undo_reopened) else move(row, ItemStatus.DONE, R.string.undo_done)

    fun markMissing(row: ItemRowUi) = move(row, ItemStatus.MISSING, R.string.undo_missing)

    fun reopen(row: ItemRowUi) = move(row, ItemStatus.OPEN, R.string.undo_reopened)

    fun claim(row: ItemRowUi) = move(row, ItemStatus.CLAIMED, undoMessage = null)

    fun unclaim(row: ItemRowUi) = move(row, ItemStatus.OPEN, R.string.undo_unclaimed)

    fun approve(row: ItemRowUi) = move(row, ItemStatus.OPEN, undoMessage = null)

    /** True when [to] is a legal move for me right now; the UI only offers these. */
    fun can(row: ItemRowUi, to: ItemStatus): Boolean {
        val ctx = context.value ?: return false
        return StatusRules.canTransition(row.item.status, to, ctx.role, row.item.claimedBy == ctx.uid)
    }

    fun canDelete(row: ItemRowUi): Boolean = context.value?.let { StatusRules.canDelete(row.item.status, it.role) } ?: false

    fun canRename(row: ItemRowUi): Boolean = row.item.addedBy == context.value?.uid

    private fun move(row: ItemRowUi, to: ItemStatus, @StringRes undoMessage: Int?) {
        val ctx = context.value ?: return
        val from = row.item.status
        val isClaimer = row.item.claimedBy == ctx.uid
        if (!StatusRules.canTransition(from, to, ctx.role, isClaimer)) return
        items.update(ctx.householdId, listId, row.item.id, ItemPatches.status(to, ctx.uid))
        if (undoMessage == null) return
        // After the move I may be the claimer no longer; ask the table where Undo can go.
        val back = StatusRules.undoTarget(previous = from, current = to, role = ctx.role, isClaimer = to == ItemStatus.CLAIMED || isClaimer)
            ?: return
        offerUndo(undoMessage, undo = { items.update(ctx.householdId, listId, row.item.id, ItemPatches.status(back, ctx.uid)) }, commit = {})
    }

    // --- Delete / decline: hidden at once, written when the snackbar goes away without Undo.

    fun delete(row: ItemRowUi) = deferDelete(row, R.string.undo_deleted)

    fun decline(row: ItemRowUi) = deferDelete(row, R.string.undo_declined)

    private fun deferDelete(row: ItemRowUi, @StringRes message: Int) {
        val ctx = context.value ?: return
        if (!StatusRules.canDelete(row.item.status, ctx.role)) return
        hidden.update { it + row.item.id }
        offerUndo(
            message,
            undo = { hidden.update { it - row.item.id } },
            commit = { items.delete(ctx.householdId, listId, row.item.id) },
        )
    }

    fun edit(row: ItemRowUi, edit: ItemEdit) {
        val ctx = context.value ?: return
        items.update(ctx.householdId, listId, row.item.id, ItemPatches.edit(row.item, edit, canRename = row.item.addedBy == ctx.uid))
    }

    // --- Undo plumbing

    private fun offerUndo(@StringRes message: Int, undo: () -> Unit, commit: () -> Unit) {
        settle(undone = false) // a new action settles the previous one
        onUndo = undo
        onCommit = commit
        _undo.value = UndoUi(message, System.nanoTime())
    }

    /** Called when the snackbar closes: [undone] = the user pressed Undo. */
    fun settle(undone: Boolean) {
        val action = if (undone) onUndo else onCommit
        onUndo = null
        onCommit = null
        _undo.value = null
        action?.invoke()
    }

    override fun onCleared() {
        settle(undone = false) // leaving the screen commits a pending delete
    }
}
