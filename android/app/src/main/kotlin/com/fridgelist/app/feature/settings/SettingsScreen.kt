package com.fridgelist.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgelist.app.R

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    onOpenDetectionDebug: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val dev by viewModel.dev.collectAsStateWithLifecycle()
    val signedOut by viewModel.signedOut.collectAsStateWithLifecycle()
    LaunchedEffect(signedOut) { if (signedOut) onSignedOut() }
    val language = remember { AppLanguage.current() }
    SettingsScreen(
        language = language,
        onLanguageSelected = AppLanguage::apply,
        dev = if (viewModel.showDeveloper) dev else null,
        onHostChange = viewModel::onHostChange,
        onSaveHost = viewModel::saveHost,
        onBack = onBack,
        onSignOut = viewModel::signOut,
        numbers = remember { NumberStyle.current() },
        onNumbers = viewModel::setNumbers,
        onForcePlanner = viewModel::setForcePlanner,
        detection = { DetectionRoute() },
        onOpenDetectionDebug = if (viewModel.showDeveloper) onOpenDetectionDebug else null,
    )
}

/** Stateless so screenshot tests can render it in every language and theme. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    dev: DevUiState?,
    onHostChange: (String) -> Unit,
    onSaveHost: () -> Unit,
    onBack: () -> Unit,
    onSignOut: (() -> Unit)? = null,
    numbers: NumberStyle = NumberStyle.SYSTEM,
    onNumbers: (NumberStyle) -> Unit = {},
    onForcePlanner: (Boolean) -> Unit = {},
    /** Store detection (A7); a slot so screenshot tests can leave it out. */
    detection: (@Composable () -> Unit)? = null,
    onOpenDetectionDebug: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_language_header))
            Column(Modifier.selectableGroup()) {
                AppLanguage.entries.forEach { option ->
                    LanguageRow(option, selected = option == language, onClick = { onLanguageSelected(option) })
                }
            }
            SectionHeader(stringResource(R.string.settings_numbers_header))
            Column(Modifier.selectableGroup()) {
                NumberStyle.entries.forEach { option ->
                    OptionRow(stringResource(option.label), selected = option == numbers, onClick = { onNumbers(option) })
                }
            }
            if (detection != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionHeader(stringResource(R.string.detect_header))
                detection()
            }
            if (onSignOut != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionHeader(stringResource(R.string.settings_account_header))
                TextButton(onClick = onSignOut, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.settings_sign_out))
                }
            }
            if (dev != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionHeader(stringResource(R.string.settings_dev_header))
                DeveloperSection(dev, onHostChange, onSaveHost)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(value = dev.forcePlanner, onValueChange = onForcePlanner, role = Role.Switch)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_force_planner), modifier = Modifier.weight(1f))
                    Switch(checked = dev.forcePlanner, onCheckedChange = null)
                }
                if (onOpenDetectionDebug != null) {
                    TextButton(onClick = onOpenDetectionDebug, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.debug_detect_open))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun LanguageRow(option: AppLanguage, selected: Boolean, onClick: () -> Unit) =
    OptionRow(stringResource(option.label), selected, onClick)

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun DeveloperSection(dev: DevUiState, onHostChange: (String) -> Unit, onSaveHost: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = dev.hostInput,
            onValueChange = onHostChange,
            label = { Text(stringResource(R.string.settings_emulator_host_label)) },
            supportingText = {
                Text(
                    stringResource(
                        if (dev.hostInvalid) R.string.settings_emulator_host_invalid else R.string.settings_emulator_host_help,
                    ),
                )
            },
            isError = dev.hostInvalid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onSaveHost, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.settings_save))
        }
        if (dev.saved) {
            Text(
                stringResource(R.string.settings_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        dev.connectedHost?.let { host ->
            Text(
                stringResource(R.string.settings_emulator_connected, host),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
