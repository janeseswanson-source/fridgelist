package com.fridgelist.core.data.device

import android.util.Log
import com.fridgelist.core.data.firebase.AppEnvironment
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.model.Platform
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Source of the FCM registration token. Null when FCM is unavailable (no Play services, demo project). */
interface PushTokenProvider {
    suspend fun token(): String?
}

@Singleton
class FirebasePushTokenProvider @Inject constructor() : PushTokenProvider {
    override suspend fun token(): String? = withTimeoutOrNull(TIMEOUT_MS) {
        runCatching { FirebaseMessaging.getInstance().token.await() }
            .onFailure { Log.i(TAG, "FCM token unavailable: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    private companion object {
        const val TAG = "PushToken"
        const val TIMEOUT_MS = 10_000L
    }
}

/** Registers this install's push token for a user (seam for tests). */
interface DeviceRegistry {
    /** @param token a fresh token from `onNewToken`; fetched when null. Returns false when there is no token. */
    suspend fun register(uid: String, token: String? = null): Boolean
}

/**
 * Keeps `users/{uid}/devices/{deviceId}` (CONTRACT §3.2) current: written after sign-in, on every
 * app start, and whenever FCM rotates the token. Offline-first: the write is queued, never awaited.
 */
@Singleton
class DeviceRegistrar @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val session: SessionStore,
    private val tokens: PushTokenProvider,
    private val environment: AppEnvironment,
) : DeviceRegistry {
    override suspend fun register(uid: String, token: String?): Boolean {
        val fcmToken = token ?: tokens.token() ?: return false
        val deviceId = session.deviceId()
        val data = fields(fcmToken, environment.appVersion, Locale.getDefault().toLanguageTag()) +
            ("updatedAt" to FieldValue.serverTimestamp())
        firestore.collection("users").document(uid).collection("devices").document(deviceId)
            .set(data, SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "device write rejected: ${it.javaClass.simpleName}") }
        return true
    }

    companion object {
        private const val TAG = "DeviceRegistrar"

        /** The self-written device fields, minus the server timestamp. */
        fun fields(fcmToken: String, appVersion: String, locale: String): Map<String, Any> = mapOf(
            "platform" to Platform.ANDROID.wire,
            "fcmToken" to fcmToken,
            "appVersion" to appVersion,
            "locale" to locale,
        )
    }
}
