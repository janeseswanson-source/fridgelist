package com.fridgelist.app.feature.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgelist.app.R
import com.fridgelist.app.feature.settings.AppLanguage
import com.fridgelist.app.platform.GoogleIdTokens
import com.fridgelist.app.ui.common.ErrorText
import com.fridgelist.app.ui.common.LanguageOptions
import com.fridgelist.app.ui.common.LanguageRadioList
import com.fridgelist.app.ui.common.PrimaryButton
import com.fridgelist.app.ui.common.SectionLabel
import com.fridgelist.app.ui.common.StepScaffold
import com.fridgelist.core.data.session.OnboardingStep
import com.fridgelist.core.model.Limits
import kotlinx.coroutines.launch

// --- Welcome: UI language first (Arabic first in the list, CLAUDE.md rule 3).

@Composable
fun WelcomeScreen(selected: String, onSelect: (String) -> Unit, onContinue: () -> Unit) {
    StepScaffold(
        title = stringResource(R.string.welcome_title),
        body = stringResource(R.string.welcome_body),
        bottom = { PrimaryButton(stringResource(R.string.action_continue), onContinue) },
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                painterResource(R.drawable.ic_cart),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(20.dp).size(40.dp),
            )
        }
        SectionLabel(stringResource(R.string.welcome_choose_language))
        LanguageRadioList(LanguageOptions.UI, selected, onSelect)
    }
}

@Composable
fun WelcomeRoute(onContinue: () -> Unit) {
    WelcomeScreen(
        selected = LanguageOptions.currentUiLanguage(),
        onSelect = { tag -> AppLanguage.entries.firstOrNull { it.tag == tag }?.let(AppLanguage::apply) },
        onContinue = onContinue,
    )
}

// --- Sign in

@Composable
fun SignInScreen(
    state: SignInUiState,
    onGoogle: () -> Unit,
    onTestUserNameChange: (String) -> Unit,
    onTestUser: () -> Unit,
    onBack: () -> Unit,
) {
    StepScaffold(title = stringResource(R.string.signin_title), body = stringResource(R.string.signin_body), onBack = onBack) {
        PrimaryButton(stringResource(R.string.signin_google), onGoogle, enabled = state.googleAvailable, busy = state.busy)
        if (!state.googleAvailable) {
            Text(stringResource(R.string.signin_google_unavailable), style = MaterialTheme.typography.bodySmall)
        }
        state.error?.let { ErrorText(stringResource(it)) }
        if (state.testUserAvailable) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel(stringResource(R.string.signin_test_user_header))
            OutlinedTextField(
                value = state.testUserName,
                onValueChange = onTestUserNameChange,
                label = { Text(stringResource(R.string.signin_test_user_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = onTestUser,
                enabled = state.testUserName.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.signin_test_user_button)) }
        }
    }
}

@Composable
fun SignInRoute(onNext: (OnboardingStep) -> Unit, onBack: () -> Unit, vm: SignInViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.next) {
        state.next?.let { vm.onNavigated(); onNext(it) }
    }
    SignInScreen(
        state = state,
        onGoogle = {
            vm.onGoogleStarted()
            scope.launch { vm.onGoogleResult(GoogleIdTokens.request(context, vm.webClientId)) }
        },
        onTestUserNameChange = vm::onTestUserNameChange,
        onTestUser = vm::signInTestUser,
        onBack = onBack,
    )
}

// --- Profile

@Composable
fun ProfileScreen(state: ProfileUiState, onNameChange: (String) -> Unit, onLanguage: (String) -> Unit, onSave: () -> Unit) {
    StepScaffold(
        title = stringResource(R.string.profile_title),
        bottom = { PrimaryButton(stringResource(R.string.action_continue), onSave) },
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.profile_name_label)) },
            supportingText = {
                Text(
                    if (state.nameInvalid) {
                        pluralStringResource(R.plurals.name_error, Limits.DISPLAY_NAME_MAX, Limits.DISPLAY_NAME_MAX)
                    } else {
                        stringResource(R.string.profile_name_help)
                    },
                )
            },
            isError = state.nameInvalid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SectionLabel(stringResource(R.string.profile_item_language_header))
        Text(stringResource(R.string.profile_item_language_help), style = MaterialTheme.typography.bodyMedium)
        LanguageRadioList(LanguageOptions.ITEM, state.itemLanguage, onLanguage)
    }
}

@Composable
fun ProfileRoute(onNext: (OnboardingStep) -> Unit, vm: ProfileViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.next) {
        state.next?.let { vm.onNavigated(); onNext(it) }
    }
    ProfileScreen(state, vm::onNameChange, vm::onLanguageChange, vm::save)
}

// --- Create or join

@Composable
fun HouseholdChoiceScreen(onCreate: () -> Unit, onJoin: () -> Unit) {
    StepScaffold(
        title = stringResource(R.string.household_choice_title),
        body = stringResource(R.string.household_choice_body),
        bottom = {
            PrimaryButton(stringResource(R.string.household_create), onCreate)
            OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(R.string.household_join))
            }
        },
    ) {}
}

// --- Notifications: rationale first, then the system prompt (API 33+).

@Composable
fun NotificationsScreen(onAllow: () -> Unit, onSkip: () -> Unit) {
    StepScaffold(
        title = stringResource(R.string.notifications_title),
        body = stringResource(R.string.notifications_body),
        bottom = {
            PrimaryButton(stringResource(R.string.notifications_allow), onAllow)
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.notifications_skip))
            }
        },
    ) {}
}

@Composable
fun NotificationsRoute(onNext: (OnboardingStep) -> Unit, vm: NotificationsViewModel = hiltViewModel()) {
    val next by vm.next.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(next) {
        next?.let { vm.onNavigated(); onNext(it) }
    }
    val needsPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    LaunchedEffect(needsPrompt) {
        if (!needsPrompt) vm.finish()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.finish() }
    if (needsPrompt) {
        Column {
            NotificationsScreen(
                onAllow = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                onSkip = vm::finish,
            )
        }
    }
}
