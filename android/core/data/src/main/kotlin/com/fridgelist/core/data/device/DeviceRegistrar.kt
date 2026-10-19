package com.fridgelist.core.data.device

import android.util.Log
import com.fridgelist.core.data.firebase.AppEnvironment
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.model.Platform
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Source of the FCM registration token. Null when FCM is unavailable (no Play services, demo project). */
interface PushTokenProvider {
    suspend fun token(): String?
}

/**
 * firebase-messaging 26: `register()` asks FCM for a token and delivers it to
 * `FirebaseMessagingService.onRegistered` (which saves it); `getToken()`/`onNewToken` are
 * deprecated. This returns the last saved token and asks for a fresh registration.
 */
@Singleton
class FirebasePushTokenProvider @Inject constructor(
    private val session: SessionStore,
) : PushTokenProvider {
    override suspend fun token(): String? {
        runCatching { FirebaseMessaging.getInstance().register() }
            .onFailure { Log.i(TAG, "FCM register unavailable: ${it.javaClass.simpleName}") }
        return session.pushToken()
    }

    private companion object {
        const val TAG = "PushToken"
    }
}

/** Registers this install's push token for a user (seam for tests). */
interface DeviceRegistry {
    /** @param token a fresh token from `onNewToken`; fetched when null. Returns false when there is no token. */
    suspend fun register(uid: String, token: String? = null): Boolean

    /** Sign-out: removes this install's device doc so the server stops pushing here. Call while still signed in. */
    suspend fun unregister(uid: String)
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

    override suspend fun unregister(uid: String) {
        firestore.collection("users").document(uid).collection("devices").document(session.deviceId())
            .delete()
            .addOnFailureListener { Log.w(TAG, "device delete rejected: ${it.javaClass.simpleName}") }
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
