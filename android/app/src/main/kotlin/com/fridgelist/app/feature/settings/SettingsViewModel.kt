package com.fridgelist.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fridgelist.core.data.firebase.AppEnvironment
import com.fridgelist.core.data.firebase.FirebaseBootstrap
import com.fridgelist.core.data.settings.DevSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DevUiState(
    val hostInput: String = "",
    val hostInvalid: Boolean = false,
    val connectedHost: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    environment: AppEnvironment,
    private val devSettings: DevSettings,
    firebase: FirebaseBootstrap,
) : ViewModel() {

    /** Developer options exist only in the dev flavor. */
    val showDeveloper: Boolean = environment.useEmulators

    private val _dev = MutableStateFlow(DevUiState(connectedHost = firebase.emulatorHost))
    val dev: StateFlow<DevUiState> = _dev.asStateFlow()

    init {
        if (showDeveloper) {
            viewModelScope.launch {
                val host = devSettings.currentEmulatorHost()
                _dev.update { it.copy(hostInput = host) }
            }
        }
    }

    fun onHostChange(value: String) {
        _dev.update { it.copy(hostInput = value, hostInvalid = false, saved = false) }
    }

    fun saveHost() {
        val host = _dev.value.hostInput.trim()
        if (!DevSettings.isValidHost(host)) {
            _dev.update { it.copy(hostInvalid = true, saved = false) }
            return
        }
        viewModelScope.launch {
            devSettings.setEmulatorHost(host)
            _dev.update { it.copy(saved = true) }
        }
    }
}
