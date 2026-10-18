package com.fridgelist.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fridgelist.core.data.firebase.EmulatorConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.devDataStore: DataStore<Preferences> by preferencesDataStore(name = "dev_settings")

/** Developer-only settings (dev flavor). The emulator host applies on the next app start. */
@Singleton
class DevSettings @Inject constructor(@ApplicationContext private val context: Context) {

    val emulatorHost: Flow<String> =
        context.devDataStore.data.map { it[EMULATOR_HOST] ?: EmulatorConfig.DEFAULT_HOST }

    suspend fun currentEmulatorHost(): String = emulatorHost.first()

    suspend fun setEmulatorHost(host: String) {
        context.devDataStore.edit { prefs ->
            val trimmed = host.trim()
            if (trimmed.isEmpty()) prefs.remove(EMULATOR_HOST) else prefs[EMULATOR_HOST] = trimmed
        }
    }

    companion object {
        private val EMULATOR_HOST = stringPreferencesKey("emulator_host")

        /** Accepts an IPv4 address or a hostname such as `localhost` or `my-pc.local`. */
        fun isValidHost(host: String): Boolean = HOST.matches(host.trim())

        private val HOST = Regex("^(?:\\d{1,3}(?:\\.\\d{1,3}){3}|[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9-]{1,63})*)$")
    }
}
