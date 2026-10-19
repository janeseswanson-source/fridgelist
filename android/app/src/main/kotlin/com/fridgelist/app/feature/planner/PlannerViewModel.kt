package com.fridgelist.app.feature.planner

import android.icu.util.IslamicCalendar
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgelist.app.R
import com.fridgelist.app.feature.lists.HouseholdContextSource
import com.fridgelist.app.feature.onboarding.UiLanguageProvider
import com.fridgelist.app.ui.common.ErrorMessages
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.firestore.ItemDraft
import com.fridgelist.core.data.firestore.ItemPatches
import com.fridgelist.core.data.flags.RemoteFlags
import com.fridgelist.core.data.items.ItemRepository
import com.fridgelist.core.data.items.ListRepository
import com.fridgelist.core.data.meals.MealRepository
import com.fridgelist.core.data.settings.DevSettings
import com.fridgelist.core.model.AddedVia
import com.fridgelist.core.model.Flags
import com.fridgelist.core.model.ListType
import com.fridgelist.core.model.MealToItemsRequest
import com.fridgelist.core.model.Occasion
import com.fridgelist.core.model.ParsedItem
import com.fridgelist.core.model.PlannerLimits
import com.fridgelist.core.model.SkippedIngredient
import com.fridgelist.core.model.StatusRules
import com.fridgelist.core.model.SuggestMealsRequest
import com.fridgelist.core.model.SuggestedMeal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import javax.inject.Inject

/** PRODUCT_SPEC F11: from 1 Sha'ban to the last day of Ramadan, Umm al-Qura calendar. */
object RamadanWindow {
    fun isOpen(date: Date): Boolean {
        val calendar = IslamicCalendar().apply {
            calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
            time = date
        }
        val month = calendar.get(IslamicCalendar.MONTH)
        return month == IslamicCalendar.SHABAN || month == IslamicCalendar.RAMADAN
    }
}

/** The planner shows when `ramadan_planner_enabled` is on and it is Sha'ban or Ramadan; dev builds can force it. */
class PlannerAvailability @Inject constructor(
    private val flags: RemoteFlags,
    private val dev: DevSettings,
) {
    var now: () -> Date = { Date() }

    suspend fun available(): Boolean =
        dev.currentForcePlanner() || (flags.boolean(Flags.RAMADAN_PLANNER_ENABLED) && RamadanWindow.isOpen(now()))
}

enum class PlannerStep { SETUP, PICK, CONFIRM, DONE }

/** An ingredient to add; [keep] is the chip's state on the confirm step. */
data class PlannedItem(val item: ParsedItem, val keep: Boolean = true)

data class PlannerUiState(
    val step: PlannerStep = PlannerStep.SETUP,
    val people: Int = 4,
    val days: Int = 3,
    val busy: Boolean = false,
    val meals: List<SuggestedMeal> = emptyList(),
    val selected: Set<String> = emptySet(),
    val items: List<PlannedItem> = emptyList(),
    val skipped: List<SkippedIngredient> = emptyList(),
    val listName: String = "",
    val added: Int = 0,
    @param:StringRes val message: Int? = null,
)

/** "Plan iftar" (F11): people and days → ideas → pick → ingredients minus what we have → add. */
@HiltViewModel
class PlannerViewModel @Inject constructor(
    private val contextSource: HouseholdContextSource,
    private val lists: ListRepository,
    private val items: ItemRepository,
    private val meals: MealRepository,
    private val uiLanguage: UiLanguageProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(PlannerUiState())
    val state: StateFlow<PlannerUiState> = _state.asStateFlow()

    /** Everything already suggested, so "More ideas" brings new dishes. */
    private val shown = mutableSetOf<String>()
    private var listId: String? = null

    fun onPeople(value: Int) = _state.update { it.copy(people = value.coerceIn(PlannerLimits.PEOPLE_MIN, PlannerLimits.PEOPLE_MAX)) }

    fun onDays(value: Int) = _state.update { it.copy(days = value.coerceIn(PlannerLimits.DAYS_MIN, PlannerLimits.DAYS_MAX)) }

    fun suggest(more: Boolean = false) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val s = _state.value
            try {
                val ctx = contextSource.context.first()
                val response = meals.suggest(
                    SuggestMealsRequest(
                        householdId = ctx.householdId,
                        people = s.people,
                        occasion = Occasion.IFTAR.wire,
                        days = s.days,
                        exclude = if (more) shown.toList() else null,
                        lang = uiLanguage.current(),
                    ),
                )
                shown += response.meals.map { it.title }
                _state.update {
                    it.copy(
                        busy = false,
                        step = PlannerStep.PICK,
                        meals = if (more) it.meals + response.meals else response.meals,
                        selected = if (more) it.selected else emptySet(),
                        message = if (response.meals.isEmpty()) R.string.planner_no_ideas else null,
                    )
                }
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, message = errorFor(e)) }
            }
        }
    }

    fun toggleMeal(mealId: String) = _state.update {
        it.copy(selected = if (mealId in it.selected) it.selected - mealId else it.selected + mealId)
    }

    /** `mealToItems` in the viewer's item language, for the household's grocery list. */
    fun makeList() {
        val s = _state.value
        if (s.busy || s.selected.isEmpty()) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val ctx = contextSource.context.first()
                val target = withTimeoutOrNull(3_000) {
                    val all = lists.lists(ctx.householdId).first()
                    all.firstOrNull { it.type == ListType.GROCERY } ?: all.firstOrNull()
                }
                if (target == null) {
                    _state.update { it.copy(busy = false, message = R.string.planner_no_list) }
                    return@launch
                }
                listId = target.id
                val response = meals.toItems(
                    MealToItemsRequest(ctx.householdId, target.id, s.meals.map { it.mealId }.filter { it in s.selected }, s.people, ctx.viewerLang),
                )
                _state.update {
                    it.copy(
                        busy = false,
                        step = PlannerStep.CONFIRM,
                        items = response.items.map(::PlannedItem),
                        skipped = response.skipped,
                        listName = target.name,
                    )
                }
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, message = errorFor(e)) }
            }
        }
    }

    fun toggleItem(index: Int) = _state.update { s ->
        s.copy(items = s.items.mapIndexed { i, p -> if (i == index) p.copy(keep = !p.keep) else p })
    }

    /** Offline-first like any add; kids' adds become requests (StatusRules). */
    fun add() {
        val s = _state.value
        val target = listId ?: return
        val kept = s.items.filter { it.keep }.map { it.item }
        if (kept.isEmpty()) return
        viewModelScope.launch {
            val ctx = contextSource.context.first()
            val status = StatusRules.createStatus(ctx.role)
            items.add(
                ctx.householdId,
                target,
                kept.map { ItemPatches.create(ItemDraft(it.name, it.qty, it.unitEnum), status, ctx.uid, AddedVia.MEAL_PLAN) },
            )
            _state.update { it.copy(step = PlannerStep.DONE, added = kept.size) }
        }
    }

    fun back() = _state.update {
        when (it.step) {
            PlannerStep.CONFIRM -> it.copy(step = PlannerStep.PICK, message = null)
            PlannerStep.PICK -> it.copy(step = PlannerStep.SETUP, message = null)
            else -> it
        }
    }

    private fun errorFor(e: ApiException): Int = when (e.error) {
        ApiError.NETWORK -> R.string.planner_offline
        ApiError.UNAVAILABLE -> R.string.planner_unavailable
        ApiError.RESOURCE_EXHAUSTED -> R.string.planner_too_many
        else -> ErrorMessages.general(e)
    }
}
