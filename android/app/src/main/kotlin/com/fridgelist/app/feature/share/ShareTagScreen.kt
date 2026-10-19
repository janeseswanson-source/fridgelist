package com.fridgelist.app.feature.share

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgelist.app.R
import com.fridgelist.app.ui.common.QrImage

@Composable
fun ShareTagRoute(onBack: () -> Unit, vm: ShareTagViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val nfc = NfcTags.adapter(context)
    // Reader mode only while "hold the sticker" shows; Android calls back on a binder thread.
    if (state.tagStep == TagStep.HOLD && activity != null) {
        DisposableEffect(Unit) {
            NfcTags.enableReader(activity) { tag ->
                val url = vm.tagUrl() ?: return@enableReader
                val result = NfcTags.write(tag, url)
                activity.runOnUiThread { vm.onTagWritten(result) }
            }
            onDispose { NfcTags.disableReader(activity) }
        }
    }
    ShareTagScreen(
        state = state,
        hasNfc = nfc != null,
        nfcOn = nfc?.isEnabled == true,
        onBack = onBack,
        onGuestsCanAdd = vm::onGuestsCanAdd,
        onRetry = vm::loadQr,
        onShare = { state.qrUrl?.let { QrExport.share(context, it, state.listName) } },
        onPrint = { state.qrUrl?.let { QrExport.print(context, it, state.listName) } },
        onWriteTag = vm::startTag,
        onCancelTag = vm::cancelTag,
        onNfcSettings = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
    )
}

/** Stateless for screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTagScreen(
    state: ShareTagUiState,
    hasNfc: Boolean,
    nfcOn: Boolean,
    onBack: () -> Unit,
    onGuestsCanAdd: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit,
    onWriteTag: () -> Unit,
    onCancelTag: () -> Unit,
    onNfcSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_title)) },
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
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.share_intro, state.listName), style = MaterialTheme.typography.bodyLarge)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = state.guestsCanAdd, onValueChange = onGuestsCanAdd, role = Role.Switch),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.share_guests_can_add), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = state.guestsCanAdd, onCheckedChange = null)
            }
            state.message?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Text(stringResource(R.string.share_qr_header), style = MaterialTheme.typography.titleMedium)
            when {
                state.qrUrl != null -> {
                    QrImage(
                        content = state.qrUrl,
                        contentDescription = stringResource(R.string.share_qr_desc, state.listName),
                        modifier = Modifier.size(220.dp).align(Alignment.CenterHorizontally),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onShare, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.share_share)) }
                        OutlinedButton(onClick = onPrint, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.share_print)) }
                    }
                }
                state.loadingQr -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                else -> TextButton(onClick = onRetry) { Text(stringResource(R.string.share_retry)) }
            }

            if (hasNfc) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.share_nfc_header), style = MaterialTheme.typography.titleMedium)
                when {
                    !nfcOn -> {
                        Text(stringResource(R.string.share_nfc_off), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onNfcSettings) { Text(stringResource(R.string.share_nfc_settings)) }
                    }
                    state.tagStep == TagStep.HOLD -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text(stringResource(R.string.share_nfc_hold), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                        TextButton(onClick = onCancelTag) { Text(stringResource(R.string.share_cancel)) }
                    }
                    state.tagStep == TagStep.DONE -> {
                        Text(stringResource(R.string.share_nfc_done, state.listName), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = onWriteTag, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.share_nfc_another)) }
                    }
                    else -> {
                        Text(stringResource(R.string.share_nfc_desc), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onWriteTag, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.share_nfc_write)) }
                    }
                }
            }
        }
    }
}
