package com.fridgelist.core.data.flags

import com.fridgelist.core.data.firebase.AppEnvironment
import com.fridgelist.core.data.firebase.FirebaseBootstrap
import com.fridgelist.core.model.Flags
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** CONTRACT §10 flags. Never blocks for long: a failed or slow fetch leaves the defaults. */
interface RemoteFlags {
    suspend fun boolean(key: String): Boolean
}

@Singleton
class FirebaseRemoteFlags @Inject constructor(
    private val environment: AppEnvironment,
    private val bootstrap: FirebaseBootstrap,
) : RemoteFlags {

    private val mutex = Mutex()
    @Volatile private var config: FirebaseRemoteConfig? = null

    override suspend fun boolean(key: String): Boolean {
        // Remote Config has no emulator: dev builds use the contract defaults.
        if (environment.useEmulators) return Flags.DEFAULTS[key] as? Boolean ?: false
        return runCatching { ready().getBoolean(key) }.getOrElse { Flags.DEFAULTS[key] as? Boolean ?: false }
    }

    private suspend fun ready(): FirebaseRemoteConfig = mutex.withLock {
        config ?: run {
            bootstrap.ensureInitialized()
            val rc = FirebaseRemoteConfig.getInstance()
            rc.setConfigSettingsAsync(FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(FETCH_INTERVAL_S).build()).await()
            rc.setDefaultsAsync(Flags.DEFAULTS).await()
            withTimeoutOrNull(FETCH_TIMEOUT_MS) { runCatching { rc.fetchAndActivate().await() } }
            rc.also { config = it }
        }
    }

    private companion object {
        const val FETCH_INTERVAL_S = 3_600L
        const val FETCH_TIMEOUT_MS = 3_000L
    }
}
