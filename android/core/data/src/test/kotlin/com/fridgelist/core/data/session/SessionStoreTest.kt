package com.fridgelist.core.data.session

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SessionStoreTest {
    private val store = DataStoreSessionStore(ApplicationProvider.getApplicationContext())

    @Test
    fun deviceIdIsCreatedOnceAndSurvivesSignOut() = runTest {
        val first = store.deviceId()
        assertEquals(first, store.deviceId())
        store.saveProfile("Ali", "ar")
        store.setHousehold("h1")
        store.markNotificationsAsked()
        assertEquals(Session("Ali", "ar", "h1", true), store.current())

        store.clear()
        assertNull(store.current().householdId)
        assertEquals(first, store.deviceId())
        assertTrue(first.length == 36, "UUID")
    }
}
