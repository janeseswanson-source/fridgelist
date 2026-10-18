package com.fridgelist.core.data.firebase

import android.content.Context
import android.util.Log
import com.fridgelist.core.data.settings.DevSettings
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.functions.FirebaseFunctions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Initializes Firebase exactly once, before anything touches Auth, Firestore or Functions.
 *
 * - dev flavor: a `demo-fridgelist` app pointed at the Emulator Suite on the host from [DevSettings].
 * - prod flavor: the default app from `google-services.json` (wired when the owner supplies it).
 *
 * Firestore always uses the persistent offline cache (CLAUDE.md rule 2, offline-first).
 */
@Singleton
class FirebaseBootstrap @Inject constructor(
    @ApplicationContext private val context: Context,
    private val environment: AppEnvironment,
    private val devSettings: DevSettings,
) {
    @Volatile private var initialized = false

    /** Host the emulators were wired to, or null in prod. */
    @Volatile var emulatorHost: String? = null
        private set

    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        if (environment.useEmulators) {
            initDemoApp()
            configureFirestoreCache()
            connectEmulators()
        } else {
            checkNotNull(FirebaseApp.initializeApp(context)) {
                "prod flavor needs app/src/prod/google-services.json (see docs/PROGRESS.md open questions)"
            }
            configureFirestoreCache()
        }
        initialized = true
    }

    private fun initDemoApp() {
        if (FirebaseApp.getApps(context).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setProjectId(EmulatorConfig.DEMO_PROJECT_ID)
            .setApplicationId(EmulatorConfig.DEMO_APP_ID)
            .setApiKey(EmulatorConfig.DEMO_API_KEY)
            .build()
        FirebaseApp.initializeApp(context, options)
    }

    private fun connectEmulators() {
        // Read once at start-up; changing the host takes effect on the next launch.
        val host = runBlocking { devSettings.currentEmulatorHost() }
        FirebaseAuth.getInstance().useEmulator(host, EmulatorConfig.AUTH_PORT)
        FirebaseFirestore.getInstance().useEmulator(host, EmulatorConfig.FIRESTORE_PORT)
        FirebaseFunctions.getInstance(FUNCTIONS_REGION).useEmulator(host, EmulatorConfig.FUNCTIONS_PORT)
        emulatorHost = host
        // Host only: never log user data (CLAUDE.md rule 4).
        Log.i(TAG, "Firebase emulators at $host")
    }

    private fun configureFirestoreCache() {
        FirebaseFirestore.getInstance().firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
            .build()
    }

    private companion object {
        const val TAG = "FirebaseBootstrap"
    }
}
