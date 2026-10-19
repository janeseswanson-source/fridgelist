package com.fridgelist.app.feature.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fridgelist.app.R
import com.fridgelist.app.feature.lists.quantityText
import com.fridgelist.core.model.Cuisine
import com.fridgelist.core.model.PlannerLimits
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Whether Home shows "Plan iftar". */
@HiltViewModel
class PlannerEntryViewModel @Inject constructor(availability: PlannerAvailability) : ViewModel() {
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    init {
        viewModelScope.launch { _available.value = availability.available() }
    }
}

@Composable
fun PlannerRoute(onBack: () -> Unit, vm: PlannerViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    PlannerScreen(
        state = state,
        onBack = { if (state.step == PlannerStep.PICK || state.step == PlannerStep.CONFIRM) vm.back() else onBack() },
        onPeople = vm::onPeople,
        onDays = vm::onDays,
        onSuggest = { vm.suggest() },
        onMore = { vm.suggest(more = true) },
        onToggleMeal = vm::toggleMeal,
        onMakeList = vm::makeList,
        onToggleItem = vm::toggleItem,
        onAdd = vm::add,
        onDone = onBack,
    )
}

/** Stateless for screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(
    state: PlannerUiState,
    onBack: () -> Unit,
    onPeople: (Int) -> Unit,
    onDays: (Int) -> Unit,
    onSuggest: () -> Unit,
    onMore: () -> Unit,
    onToggleMeal: (String) -> Unit,
    onMakeList: () -> Unit,
    onToggleItem: (Int) -> Unit,
    onAdd: () -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.planner_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            state.message?.let { item { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp)) } }
            when (state.step) {
                PlannerStep.SETUP -> item { Setup(state, onPeople, onDays, onSuggest) }
                PlannerStep.PICK -> {
                    item { Text(stringResource(R.string.planner_pick), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp)) }
                    itemsIndexed(state.meals, key = { _, m -> m.mealId }) { _, meal ->
                        CheckRow(
                            checked = meal.mealId in state.selected,
                            title = meal.title,
                            subtitle = listOfNotNull(cuisineLabel(meal.cuisine), meal.description.takeIf { it.isNotBlank() }).joinToString(" · "),
                            onToggle = { onToggleMeal(meal.mealId) },
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                            Button(onClick = onMakeList, enabled = state.selected.isNotEmpty() && !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(pluralStringResource(R.plurals.planner_make_list, state.selected.size, state.selected.size))
                            }
                            OutlinedButton(onClick = onMore, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.planner_more))
                            }
                        }
                    }
                }
                PlannerStep.CONFIRM -> {
                    item {
                        Text(stringResource(R.string.planner_confirm, state.listName), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    itemsIndexed(state.items, key = { i, p -> "$i:${p.item.name}" }) { index, planned ->
                        CheckRow(
                            checked = planned.keep,
                            title = planned.item.name,
                            subtitle = quantityText(planned.item.qty, planned.item.unitEnum),
                            onToggle = { onToggleItem(index) },
                        )
                    }
                    if (state.skipped.isNotEmpty()) {
                        item {
                            Text(stringResource(R.string.planner_skipped), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                        }
                        itemsIndexed(state.skipped) { _, s ->
                            Text(
                                stringResource(if (s.reason == "on_list") R.string.planner_skipped_on_list else R.string.planner_skipped_bought, s.name),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                    item {
                        val count = state.items.count { it.keep }
                        Button(onClick = onAdd, enabled = count > 0, modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) {
                            Text(pluralStringResource(R.plurals.planner_add, count, count))
                        }
                    }
                }
                PlannerStep.DONE -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(pluralStringResource(R.plurals.planner_added, state.added, state.added, state.listName), style = MaterialTheme.typography.titleMedium)
                        Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.planner_done)) }
                    }
                }
            }
            if (state.busy) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun Setup(state: PlannerUiState, onPeople: (Int) -> Unit, onDays: (Int) -> Unit, onSuggest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.planner_intro), style = MaterialTheme.typography.bodyLarge)
        Stepper(
            label = stringResource(R.string.planner_people),
            value = pluralStringResource(R.plurals.planner_people_value, state.people, state.people),
            onMinus = { onPeople(state.people - 1) },
            onPlus = { onPeople(state.people + 1) },
            canMinus = state.people > PlannerLimits.PEOPLE_MIN,
            canPlus = state.people < PlannerLimits.PEOPLE_MAX,
        )
        Stepper(
            label = stringResource(R.string.planner_days),
            value = pluralStringResource(R.plurals.planner_days_value, state.days, state.days),
            onMinus = { onDays(state.days - 1) },
            onPlus = { onDays(state.days + 1) },
            canMinus = state.days > PlannerLimits.DAYS_MIN,
            canPlus = state.days < PlannerLimits.DAYS_MAX,
        )
        Button(onClick = onSuggest, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.planner_suggest))
        }
    }
}

/** − value +, read by TalkBack as "People: 4 people" with labelled buttons. */
@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, canMinus: Boolean, canPlus: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        val less = stringResource(R.string.planner_less, label)
        val more = stringResource(R.string.planner_more_of, label)
        OutlinedButton(onClick = onMinus, enabled = canMinus, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = less }) { Text("−") }
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 96.dp).padding(horizontal = 8.dp))
        OutlinedButton(onClick = onPlus, enabled = canPlus, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = more }) { Text("+") }
    }
}

@Composable
private fun CheckRow(checked: Boolean, title: String, subtitle: String?, onToggle: () -> Unit) {
    val state = stringResource(if (checked) R.string.planner_selected else R.string.planner_not_selected)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, onValueChange = { onToggle() }, role = Role.Checkbox)
            .semantics { stateDescription = state }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun cuisineLabel(wire: String): String = stringResource(
    when (Cuisine.fromWire(wire)) {
        Cuisine.LEVANTINE -> R.string.cuisine_levantine
        Cuisine.GULF -> R.string.cuisine_gulf
        Cuisine.TURKISH -> R.string.cuisine_turkish
        Cuisine.EGYPTIAN -> R.string.cuisine_egyptian
        Cuisine.INTERNATIONAL -> R.string.cuisine_international
    },
)

/** Home card: "Plan iftar". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanIftarCard(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 56.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🌙")
            Text(stringResource(R.string.planner_card))
        }
    }
}
