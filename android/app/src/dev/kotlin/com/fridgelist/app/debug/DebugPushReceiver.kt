package com.fridgelist.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fridgelist.app.notifications.PushRouter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** dev only: feeds an adb-supplied FCM data payload (string extras) through the real [PushRouter]. */
@AndroidEntryPoint
class DebugPushReceiver : BroadcastReceiver() {

    @Inject lateinit var router: PushRouter

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        val data = extras.keySet().mapNotNull { key -> extras.getString(key)?.let { key to it } }.toMap()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                router.route(data)
            } finally {
                pending.finish()
            }
        }
    }
}
