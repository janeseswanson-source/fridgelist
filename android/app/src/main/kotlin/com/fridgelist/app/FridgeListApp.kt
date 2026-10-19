package com.fridgelist.app

import android.app.Application
import com.fridgelist.app.notifications.Channels
import com.fridgelist.core.data.firebase.FirebaseBootstrap
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FridgeListApp : Application() {

    @Inject lateinit var firebase: FirebaseBootstrap

    override fun onCreate() {
        super.onCreate()
        // Before any screen, widget or worker touches Firebase (emulators in dev).
        firebase.ensureInitialized()
        Channels.create(this)
    }
}
