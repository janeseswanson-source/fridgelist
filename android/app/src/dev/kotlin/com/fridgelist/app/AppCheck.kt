package com.fridgelist.app

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * dev: the debug provider. The emulators don't enforce App Check; against a real dev project,
 * register the debug token it logs (Firebase console > App Check > Manage debug tokens).
 */
object AppCheck {
    fun install() {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
