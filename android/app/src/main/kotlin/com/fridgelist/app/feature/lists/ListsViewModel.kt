package com.fridgelist.app.feature.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgelist.core.data.household.HouseholdRepository
import com.fridgelist.core.data.items.ListRepository
import com.fridgelist.core.model.HouseholdDoc
import com.fridgelist.core.model.Limits
import com.fridgelist.core.model.ListDoc
import com.fridgelist.core.model.ListType
import com.fridgelist.core.model.StatusRules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ListRowUi(val list: ListDoc, val openCount: Int)

data class ListsUiState(
    val household: HouseholdDoc? = null,
    val lists: List<ListRowUi> = emptyList(),
    val canCreate: Boolean = false,
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ListsViewModel @Inject constructor(
    private val contextSource: HouseholdContextSource,
    private val households: HouseholdRepository,
    private val listsRepo: ListRepository,
) : ViewModel() {

    /** Latest context, not the first: the first emission comes before members load (least privilege). */
    private val latest = contextSource.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<ListsUiState> = contextSource.context.flatMapLatest { ctx ->
        val rows = listsRepo.lists(ctx.householdId).catch { emit(emptyList()) }.flatMapLatest { lists ->
            if (lists.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(lists.map { list -> listsRepo.openCount(ctx.householdId, list.id).onStart { emit(0) }.catch { emit(0) } }) { counts ->
                    lists.mapIndexed { i, list -> ListRowUi(list, counts[i]) }
                }
            }
        }
        combine(rows, households.household(ctx.householdId).onStart { emit(null) }.catch { emit(null) }) { r, h ->
            ListsUiState(household = h, lists = r, canCreate = StatusRules.canApprove(ctx.role), loaded = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListsUiState())

    /** Offline-first; returns the new list's ID so the caller can open it. */
    fun createList(name: String, type: ListType, emoji: String?): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > Limits.LIST_NAME_MAX) return null
        val ctx = latest.value ?: return null
        if (!StatusRules.canApprove(ctx.role)) return null
        val order = (state.value.lists.maxOfOrNull { it.list.order } ?: 0.0) + 1
        return listsRepo.createList(ctx.householdId, trimmed, type, emoji?.trim()?.takeIf { it.isNotEmpty() }?.let(::firstGrapheme), order)
    }

    companion object {
        /** CONTRACT §3.5: `emoji` is one grapheme. */
        fun firstGrapheme(text: String): String {
            val it = java.text.BreakIterator.getCharacterInstance()
            it.setText(text)
            val end = it.next()
            return if (end == java.text.BreakIterator.DONE) text else text.substring(0, end)
        }
    }
}
