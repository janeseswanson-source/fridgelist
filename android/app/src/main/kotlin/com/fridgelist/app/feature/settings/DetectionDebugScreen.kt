package com.fridgelist.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgelist.app.R
import com.fridgelist.app.location.EmulatorGps

/** Dev flavor only: the registered fences, and Dwell / Exit for any of them (ARCHITECTURE §8.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionDebugRoute(onBack: () -> Unit, viewModel: DetectionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_detect_open)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.debug_detect_summary, state.watched.size, state.refreshPoint ?: "—"))
                    state.debugResult?.let {
                        Text(
                            stringResource(R.string.debug_detect_result, it),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Button(onClick = viewModel::debugRefresh, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.debug_detect_refresh))
                    }
                    Row {
                        TextButton(onClick = { EmulatorGps.start(context) }) { Text(stringResource(R.string.debug_detect_gps_on)) }
                        TextButton(onClick = { EmulatorGps.stop(context) }) { Text(stringResource(R.string.debug_detect_gps_off)) }
                    }
                }
            }
            items(state.watched, key = { it.storeId }) { store ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(store.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${store.storeId} · ${store.distanceM.toInt()} m", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { viewModel.debugDwell(store.storeId) }) { Text(stringResource(R.string.debug_detect_simulate)) }
                    TextButton(onClick = { viewModel.debugExit(store.storeId) }) { Text(stringResource(R.string.debug_detect_exit)) }
                }
            }
        }
    }
}
