package com.fridgelist.app.feature.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgelist.app.R
import com.fridgelist.app.ui.common.PrimaryButton
import com.fridgelist.app.ui.common.SectionLabel
import com.fridgelist.core.data.firestore.ItemEdit
import com.fridgelist.core.data.firestore.ItemPatches
import com.fridgelist.core.i18n.CategoryOrder
import com.fridgelist.core.i18n.Normalize
import com.fridgelist.core.model.ItemUnit
import com.fridgelist.core.model.Limits
import java.util.Locale

// --- Add sheet

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddItemsContent(
    state: AddItemsUiState,
    onText: (String) -> Unit,
    onChoose: (Chip, Choice) -> Unit,
    onRemove: (Chip) -> Unit,
    onConfirm: () -> Unit,
    focus: FocusRequester = remember { FocusRequester() },
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.add_title), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = state.text,
            onValueChange = onText,
            placeholder = { Text(stringResource(R.string.add_hint)) },
            supportingText = if (state.refining) ({ Text(stringResource(R.string.add_checking)) }) else null,
            minLines = 2,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.chips.forEach { chip ->
                val label = listOfNotNull(quantityText(chip.qty, chip.unit), chip.name).joinToString(" ")
                val removeLabel = stringResource(R.string.add_remove_chip, chip.name)
                InputChip(
                    selected = chip.choices.isEmpty(),
                    onClick = { onRemove(chip) },
                    label = { Text(label) },
                    trailingIcon = { Text("✕", modifier = Modifier.padding(start = 2.dp)) },
                    modifier = Modifier.semantics { contentDescription = removeLabel },
                )
            }
        }
        state.chips.filter { it.choices.isNotEmpty() }.forEach { chip ->
            Text(chip.name + " — " + stringResource(R.string.add_which_meaning), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chip.choices.forEach { choice -> AssistChip(onClick = { onChoose(chip, choice) }, label = { Text(choice.label) }) }
            }
        }
        if (state.asRequest) Text(stringResource(R.string.add_as_request), style = MaterialTheme.typography.bodySmall)
        PrimaryButton(
            text = pluralStringResource(R.plurals.add_confirm, state.chips.size, state.chips.size),
            onClick = onConfirm,
            enabled = state.chips.isNotEmpty(),
        )
    }
}

@Composable
fun AddItemsRoute(onDone: () -> Unit, vm: AddItemsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(state.added) {
        if (state.added) {
            vm.onAddedHandled()
            onDone()
        }
    }
    AddItemsContent(state, vm::onTextChange, vm::choose, vm::remove, vm::confirm, focus)
}

// --- Edit sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditItemContent(row: ItemRowUi, canRename: Boolean, onSave: (ItemEdit) -> Unit) {
    val item = row.item
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    var name by rememberSaveable(item.id) { mutableStateOf(item.name) }
    var qtyText by rememberSaveable(item.id) { mutableStateOf(item.qty?.let { Quantities.format(it, locale) }.orEmpty()) }
    var unit by rememberSaveable(item.id) { mutableStateOf(item.unit) }
    var notes by rememberSaveable(item.id) { mutableStateOf(item.notes.orEmpty()) }
    var urgent by rememberSaveable(item.id) { mutableStateOf(item.urgent) }
    var category by rememberSaveable(item.id) { mutableStateOf(row.category) }

    // Accept ١٫٥ / 1,5 / 1.5 (CONTRACT §1 quantities).
    val qty = qtyText.takeIf { it.isNotBlank() }?.let { Normalize.decimalComma(Normalize.digitsToAscii(it.trim())).replace(',', '.').toDoubleOrNull() }
    val qtyInvalid = qtyText.isNotBlank() && ItemPatches.validQty(qty) == null

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.edit_title), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(Limits.ITEM_NAME_MAX) },
            enabled = canRename,
            label = { Text(stringResource(R.string.edit_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = qtyText,
                onValueChange = { qtyText = it.take(8) },
                label = { Text(stringResource(R.string.edit_qty)) },
                isError = qtyInvalid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            Picker(
                label = stringResource(R.string.edit_unit),
                value = unit?.let { stringResource(it.label()) } ?: stringResource(R.string.edit_unit_none),
                options = listOf<ItemUnit?>(null) + ItemUnit.entries,
                optionLabel = { it?.let { u -> stringResource(u.label()) } ?: stringResource(R.string.edit_unit_none) },
                onPick = { unit = it },
                modifier = Modifier.weight(1f),
            )
        }
        if (qtyInvalid) {
            Text(
                stringResource(R.string.edit_qty_error, Quantities.format(Limits.QTY_MAX, locale)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Picker(
            label = stringResource(R.string.edit_category),
            value = stringResource(category.label()),
            options = CategoryOrder.DEFAULT,
            optionLabel = { stringResource(it.label()) },
            onPick = { category = it },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it.take(Limits.ITEM_NOTES_MAX) },
            label = { Text(stringResource(R.string.edit_notes)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.item_urgent))
            Row(Modifier.weight(1f)) {}
            Switch(checked = urgent, onCheckedChange = { urgent = it })
        }
        PrimaryButton(
            text = stringResource(R.string.settings_save),
            enabled = !qtyInvalid,
            onClick = {
                onSave(ItemEdit(name, qty, unit, notes, urgent, category.takeIf { it != row.category }))
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Picker(
    label: String,
    value: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onPick(option); open = false })
            }
        }
    }
}
