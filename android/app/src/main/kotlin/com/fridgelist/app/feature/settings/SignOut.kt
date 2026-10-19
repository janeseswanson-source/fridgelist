package com.fridgelist.app.feature.settings

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.fridgelist.core.data.auth.AuthRepository
import com.fridgelist.core.data.device.DeviceRegistry
import com.fridgelist.app.location.StoreFences
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.data.stores.DetectionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Sign-out leaves nothing of the household on screen: the device doc goes first (while the user
 * can still write it) so pushes stop, then the session, then every notification we posted (trip
 * card, last call), which may show item and member names. The device ID stays.
 */
class SignOut @Inject constructor(
    private val auth: AuthRepository,
    private val session: SessionStore,
    private val devices: DeviceRegistry,
    private val detection: DetectionStore,
    private val fences: StoreFences,
    @param:ApplicationContext private val context: Context,
) {
    suspend operator fun invoke() {
        auth.currentUser()?.let { devices.unregister(it.uid) }
        auth.signOut()
        session.clear()
        // Store detection belongs to the signed-in member: fences and state go too.
        fences.clear()
        detection.clear()
        NotificationManagerCompat.from(context).cancelAll()
    }
}
