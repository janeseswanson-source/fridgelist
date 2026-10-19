package com.fridgelist.app.feature.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgelist.app.BuildConfig
import com.fridgelist.app.R
import com.fridgelist.core.model.NearbyStore

@Composable
fun DetectionRoute(viewModel: DetectionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    val fine = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), viewModel::onFineResult)
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), viewModel::onBackgroundResult)
    LaunchedEffect(state.step) {
        when (state.step) {
            PermissionStep.ASK_FINE -> fine.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            // Android 11+ shows the app's location settings for this; the disclosure explained the steps.
            PermissionStep.ASK_BACKGROUND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            else -> Unit
        }
    }
    DetectionSection(
        state = state,
        onToggle = viewModel::onToggle,
        onRationaleContinue = viewModel::onRationaleContinue,
        onDisclosureContinue = viewModel::onDisclosureContinue,
        onDismiss = viewModel::onDismissStep,
        onOpenAppSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", BuildConfig.APPLICATION_ID, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        onMute = viewModel::mute,
        onUnmute = viewModel::unmute,
        onStoreName = viewModel::onStoreName,
        onAddStore = viewModel::addStore,
    )
}

/** Stateless for screenshot tests. */
@Composable
fun DetectionSection(
    state: DetectionUi,
    onToggle: (Boolean) -> Unit,
    onRationaleContinue: () -> Unit,
    onDisclosureContinue: () -> Unit,
    onDismiss: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onMute: (NearbyStore) -> Unit,
    onUnmute: (String) -> Unit,
    onStoreName: (String) -> Unit,
    onAddStore: () -> Unit,
) {
    if (!state.canShop) return
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = state.enabled, onValueChange = onToggle, role = Role.Switch)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.detect_toggle), style = MaterialTheme.typography.bodyLarge)
                Text(
                    statusText(state) ?: stringResource(R.string.detect_toggle_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.status == DetectionStatus.NEEDS_BACKGROUND) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.enabled, onCheckedChange = null)
        }
        if (state.status == DetectionStatus.NEEDS_BACKGROUND) {
            TextButton(onClick = onOpenAppSettings, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.detect_open_settings))
            }
        }
        if (state.enabled && state.nearby.isNotEmpty()) {
            SubHeader(stringResource(R.string.detect_nearby_header))
            state.nearby.forEach { store ->
                StoreRow(store.name, stringResource(R.string.detect_mute)) { onMute(store) }
            }
        }
        if (state.muted.isNotEmpty() || state.enabled) {
            SubHeader(stringResource(R.string.detect_muted_header))
            if (state.muted.isEmpty()) {
                Text(
                    stringResource(R.string.detect_muted_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            state.muted.forEach { store ->
                StoreRow(store.name, stringResource(R.string.detect_unmute)) { onUnmute(store.storeId) }
            }
        }
        SubHeader(stringResource(R.string.detect_add_store))
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = state.storeName,
                onValueChange = onStoreName,
                label = { Text(stringResource(R.string.detect_add_store_name)) },
                supportingText = { Text(stringResource(state.message ?: R.string.detect_add_store_hint)) },
                isError = state.message == R.string.detect_add_store_failed || state.message == R.string.detect_add_store_no_location,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAddStore() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onAddStore,
                enabled = state.storeName.isNotBlank() && !state.addingStore,
                modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.detect_add_store_save))
            }
            Text(
                stringResource(R.string.osm_attribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
    when (state.step) {
        PermissionStep.RATIONALE -> StepDialog(R.string.detect_rationale_title, stringResource(R.string.detect_rationale_text), onRationaleContinue, onDismiss)
        PermissionStep.DISCLOSURE -> StepDialog(
            R.string.detect_disclosure_title,
            stringResource(R.string.detect_disclosure_text) + "\n\n" + stringResource(R.string.detect_background_steps),
            onDisclosureContinue,
            onDismiss,
        )
        else -> Unit
    }
}

@Composable
private fun statusText(state: DetectionUi): String? = when (state.status) {
    DetectionStatus.OFF -> null
    DetectionStatus.PAUSED -> stringResource(R.string.detect_status_paused)
    DetectionStatus.NEEDS_BACKGROUND -> stringResource(R.string.detect_status_needs_background)
    DetectionStatus.WAITING -> stringResource(R.string.detect_status_waiting)
    DetectionStatus.WATCHING -> pluralStringResource(R.plurals.detect_status_watching, state.watchedCount, state.watchedCount)
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun StoreRow(name: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

@Composable
private fun StepDialog(title: Int, text: String, onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.detect_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.detect_not_now)) } },
    )
}
