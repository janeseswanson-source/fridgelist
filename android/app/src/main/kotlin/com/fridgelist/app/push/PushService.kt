package com.fridgelist.app.push

import com.fridgelist.core.data.auth.AuthRepository
import com.fridgelist.core.data.device.DeviceRegistry
import com.google.firebase.messaging.FirebaseMessagingService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * FCM entry point. A2: keeps the device token current (CONTRACT §3.2 "refresh on every token
 * change"). Message handling for CONTRACT §5.1 arrives in A6.
 */
@AndroidEntryPoint
class PushService : FirebaseMessagingService() {

    @Inject lateinit var auth: AuthRepository

    @Inject lateinit var registrar: DeviceRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val uid = auth.currentUser()?.uid ?: return
        scope.launch { registrar.register(uid, token) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
