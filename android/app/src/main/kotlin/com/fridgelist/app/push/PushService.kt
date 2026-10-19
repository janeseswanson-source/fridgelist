package com.fridgelist.app.push

import com.fridgelist.core.data.auth.AuthRepository
import com.fridgelist.core.data.device.DeviceRegistry
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.app.notifications.PushRouter
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * FCM entry point. Keeps the device token current (CONTRACT §3.2 "refresh on every token
 * change"): firebase-messaging 26 delivers tokens to [onRegistered] (replacing `onNewToken`).
 * Every CONTRACT §5.1 message goes through [PushRouter].
 */
@AndroidEntryPoint
class PushService : FirebaseMessagingService() {

    @Inject lateinit var auth: AuthRepository

    @Inject lateinit var registrar: DeviceRegistry

    @Inject lateinit var session: SessionStore

    @Inject lateinit var router: PushRouter

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        // FCM gives ~10 s here; routing only builds notifications.
        scope.launch { router.route(data) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onRegistered(token: String) = saveToken(token)

    /**
     * Deprecated in firebase-messaging 26 in favour of [onRegistered], but kept until token
     * rotation is confirmed to reach [onRegistered] on a real project: a duplicate write is a
     * harmless merge, a missed rotation would silently stop notifications.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) = saveToken(token)

    private fun saveToken(token: String) {
        scope.launch {
            session.savePushToken(token)
            auth.currentUser()?.uid?.let { registrar.register(it, token) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
