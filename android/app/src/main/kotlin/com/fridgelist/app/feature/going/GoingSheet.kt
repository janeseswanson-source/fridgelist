package com.fridgelist.app.feature.going

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgelist.app.R
import com.fridgelist.app.feature.lists.Quantities
import com.fridgelist.app.ui.common.ErrorText
import com.fridgelist.app.ui.common.PrimaryButton
import com.fridgelist.app.ui.common.SectionLabel
import com.fridgelist.core.model.LAST_CALL_MINUTES
import com.fridgelist.core.model.NearbyStore
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoingContent(
    state: GoingUiState,
    onUseLocation: () -> Unit,
    onStore: (NearbyStore) -> Unit,
    onChain: (String) -> Unit,
    onStoreText: (String) -> Unit,
    onLastCall: (Int) -> Unit,
    onSend: () -> Unit,
) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.going_title), style = MaterialTheme.typography.titleLarge)
        SectionLabel(stringResource(R.string.going_where))
        if (state.nearby.isEmpty()) {
            OutlinedButton(onClick = onUseLocation, enabled = !state.loadingNearby, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (state.loadingNearby) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.going_use_location))
            }
            Text(stringResource(R.string.going_location_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.nearby.forEach { store ->
                FilterChip(
                    selected = state.storeId == store.storeId,
                    onClick = { onStore(store) },
                    label = { Text("${store.name} · ${distance(store.distanceM, locale)}") },
                )
            }
            if (state.nearby.isEmpty()) {
                state.chains.forEach { chain ->
                    FilterChip(selected = state.storeId == null && state.storeName == chain, onClick = { onChain(chain) }, label = { Text(chain) })
                }
            }
        }
        OutlinedTextField(
            value = state.storeName,
            onValueChange = onStoreText,
            label = { Text(stringResource(R.string.going_store_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SectionLabel(stringResource(R.string.going_last_call))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LAST_CALL_MINUTES.forEach { minutes ->
                FilterChip(
                    selected = state.lastCallMinutes == minutes,
                    onClick = { onLastCall(minutes) },
                    label = {
                        Text(
                            if (minutes == 0) {
                                stringResource(R.string.going_last_call_none)
                            } else {
                                stringResource(R.string.going_minutes, Quantities.format(minutes.toDouble(), locale))
                            },
                        )
                    },
                )
            }
        }
        state.message?.let { ErrorText(stringResource(it)) }
        PrimaryButton(stringResource(R.string.going_send), onSend, busy = state.sending)
    }
}

/** "350 m" / "1.2 km" in the UI language and digits. */
@Composable
private fun distance(meters: Double, locale: Locale): String =
    if (meters < 1_000) {
        stringResource(R.string.going_distance_m, Quantities.format(Math.round(meters / 10.0) * 10.0, locale))
    } else {
        stringResource(R.string.going_distance_km, java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(meters / 1_000))
    }

@Composable
fun GoingRoute(onDone: () -> Unit, vm: GoingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.loadNearby()
    }
    LaunchedEffect(state.sent) { if (state.sent) onDone() }
    GoingContent(
        state = state,
        onUseLocation = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                vm.loadNearby()
            } else {
                permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) // the note above the button explains why
            }
        },
        onStore = vm::pickStore,
        onChain = vm::pickChain,
        onStoreText = vm::onStoreText,
        onLastCall = vm::onLastCall,
        onSend = vm::send,
    )
}
