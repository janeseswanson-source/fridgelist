package com.fridgelist.core.data.settings

import androidx.test.core.app.ApplicationProvider
import com.fridgelist.core.data.firebase.EmulatorConfig
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DevSettingsTest {
    private val settings = DevSettings(ApplicationProvider.getApplicationContext())

    @Test
    fun defaultsToAndroidEmulatorLoopback() = runTest {
        assertEquals(EmulatorConfig.DEFAULT_HOST, settings.currentEmulatorHost())
    }

    @Test
    fun storesTrimmedHostAndResetsOnBlank() = runTest {
        settings.setEmulatorHost("  192.168.1.20 ")
        assertEquals("192.168.1.20", settings.currentEmulatorHost())
        settings.setEmulatorHost(" ")
        assertEquals(EmulatorConfig.DEFAULT_HOST, settings.currentEmulatorHost())
    }

    @Test
    fun validatesHosts() {
        assertTrue(DevSettings.isValidHost("10.0.2.2"))
        assertTrue(DevSettings.isValidHost("localhost"))
        assertTrue(DevSettings.isValidHost("my-pc.local"))
        assertFalse(DevSettings.isValidHost("http://10.0.2.2"))
        assertFalse(DevSettings.isValidHost("10.0.2.2:8080"))
        assertFalse(DevSettings.isValidHost(""))
    }
}
