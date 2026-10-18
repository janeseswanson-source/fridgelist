package com.fridgelist.core.data.firebase

/**
 * Firebase Emulator Suite wiring for debug/dev builds (CLAUDE.md "Emulator mode").
 * Release builds never use emulators: [AppEnvironment.useEmulators] is false in the `prod` flavor.
 */
object EmulatorConfig {
    /** Android emulator → host machine loopback. A physical phone uses the dev machine's LAN IP. */
    const val DEFAULT_HOST = "10.0.2.2"

    /** `demo-*` projects need no real Firebase project or config file; the emulators accept them. */
    const val DEMO_PROJECT_ID = "demo-fridgelist"
    const val DEMO_APP_ID = "1:000000000000:android:0000000000000000"
    // Shaped like a real key (Firebase Installations checks "AIza" + 35 chars); the emulators never verify it.
    const val DEMO_API_KEY = "AIzaSyDemoKeyForFirebaseEmulatorOnly000"

    const val AUTH_PORT = 9099
    const val FIRESTORE_PORT = 8080
    const val FUNCTIONS_PORT = 5001
}

/**
 * FUNCTIONS_REGION from CLAUDE.md. The backend confirms the final region during B1;
 * callables must use the same value or they 404.
 */
const val FUNCTIONS_REGION = "me-central2"

/** Build-time environment supplied by the app module (flavor-specific BuildConfig). */
data class AppEnvironment(
    val useEmulators: Boolean,
    val appVersion: String = "",
    /** LINK_DOMAIN from CLAUDE.md, e.g. `links.example.com`. */
    val linkDomain: String = "",
    /** OAuth Web client ID for Sign in with Google; empty until the owner supplies it. */
    val googleWebClientId: String = "",
)
