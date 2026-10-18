package com.fridgelist.app.feature.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgelist.app.R
import com.fridgelist.app.platform.QrScanner
import com.fridgelist.app.ui.common.ErrorText
import com.fridgelist.app.ui.common.LanguageOptions
import com.fridgelist.app.ui.common.LanguageRadioList
import com.fridgelist.app.ui.common.PrimaryButton
import com.fridgelist.app.ui.common.SectionLabel
import com.fridgelist.app.ui.common.StepScaffold
import com.fridgelist.core.data.session.OnboardingStep
import com.fridgelist.core.model.InviteCode
import com.fridgelist.core.model.Limits
import kotlinx.coroutines.launch

@Composable
fun CreateHouseholdScreen(
    state: CreateHouseholdUiState,
    onNameChange: (String) -> Unit,
    onLanguage: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
) {
    StepScaffold(
        title = stringResource(R.string.household_create),
        onBack = onBack,
        bottom = {
            state.error?.let { ErrorText(stringResource(it)) }
            PrimaryButton(stringResource(R.string.create_button), onCreate, busy = state.busy)
        },
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.create_name_label)) },
            supportingText = if (state.nameInvalid) {
                { Text(pluralStringResource(R.plurals.name_error, Limits.HOUSEHOLD_NAME_MAX, Limits.HOUSEHOLD_NAME_MAX)) }
            } else {
                null
            },
            isError = state.nameInvalid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        SectionLabel(stringResource(R.string.create_language_header))
        Text(stringResource(R.string.create_language_help), style = MaterialTheme.typography.bodyMedium)
        LanguageRadioList(LanguageOptions.UI, state.defaultLanguage, onLanguage)
    }
}

@Composable
fun CreateHouseholdRoute(onNext: (OnboardingStep) -> Unit, onBack: () -> Unit, vm: CreateHouseholdViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.next) {
        state.next?.let { vm.onNavigated(); onNext(it) }
    }
    CreateHouseholdScreen(state, vm::onNameChange, vm::onLanguageChange, vm::create, onBack)
}

@Composable
fun JoinHouseholdScreen(
    state: JoinHouseholdUiState,
    onCodeChange: (String) -> Unit,
    onScan: () -> Unit,
    onJoin: () -> Unit,
    onBack: () -> Unit,
) {
    StepScaffold(
        title = stringResource(R.string.join_title),
        onBack = onBack,
        bottom = {
            state.error?.let { ErrorText(stringResource(it)) }
            PrimaryButton(stringResource(R.string.join_button), onJoin, busy = state.busy)
        },
    ) {
        OutlinedTextField(
            value = state.code,
            onValueChange = onCodeChange,
            label = { Text(stringResource(R.string.join_code_label)) },
            supportingText = {
                Text(
                    pluralStringResource(
                        if (state.codeInvalid) R.plurals.join_code_error else R.plurals.join_code_help,
                        InviteCode.LENGTH,
                        InviteCode.LENGTH,
                    ),
                )
            },
            isError = state.codeInvalid,
            singleLine = true,
            // Codes are Latin letters and digits: keep them left-to-right inside Arabic UI.
            textStyle = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Go,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_qr), contentDescription = null)
            Text(stringResource(R.string.join_scan), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
fun JoinHouseholdRoute(onNext: (OnboardingStep) -> Unit, onBack: () -> Unit, vm: JoinHouseholdViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.next) {
        state.next?.let { vm.onNavigated(); onNext(it) }
    }
    JoinHouseholdScreen(
        state = state,
        onCodeChange = vm::onCodeChange,
        onScan = {
            scope.launch {
                when (val result = QrScanner.scan(context)) {
                    is QrScanner.Result.Scanned -> vm.onScanned(result.text)
                    QrScanner.Result.Cancelled -> Unit
                    is QrScanner.Result.Failed -> vm.onScanned("")
                }
            }
        },
        onJoin = vm::join,
        onBack = onBack,
    )
}

