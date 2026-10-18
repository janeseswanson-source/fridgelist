package com.fridgelist.core.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this device remembers about the signed-in member, so the app opens on the right screen
 * even offline. The server stays the source of truth for membership.
 */
data class Session(
    val profileName: String? = null,
    val itemLanguage: String? = null,
    val householdId: String? = null,
    val notificationsAsked: Boolean = false,
)

interface SessionStore {
    val session: Flow<Session>

    suspend fun current(): Session = session.first()

    suspend fun saveProfile(name: String, itemLanguage: String)

    suspend fun setHousehold(householdId: String)

    suspend fun markNotificationsAsked()

    /** Forget everything tied to the signed-in user (sign-out). The device ID survives. */
    suspend fun clear()

    /** Per-install UUID for `users/{uid}/devices/{deviceId}` (CONTRACT §3.2). Created once. */
    suspend fun deviceId(): String
}

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

@Singleton
class DataStoreSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SessionStore {

    private val store get() = context.sessionDataStore

    override val session: Flow<Session> = store.data.map { p ->
        Session(
            profileName = p[PROFILE_NAME],
            itemLanguage = p[ITEM_LANGUAGE],
            householdId = p[HOUSEHOLD_ID],
            notificationsAsked = p[NOTIFICATIONS_ASKED] ?: false,
        )
    }

    override suspend fun saveProfile(name: String, itemLanguage: String) {
        store.edit {
            it[PROFILE_NAME] = name
            it[ITEM_LANGUAGE] = itemLanguage
        }
    }

    override suspend fun setHousehold(householdId: String) {
        store.edit { it[HOUSEHOLD_ID] = householdId }
    }

    override suspend fun markNotificationsAsked() {
        store.edit { it[NOTIFICATIONS_ASKED] = true }
    }

    override suspend fun clear() {
        store.edit { prefs ->
            val deviceId = prefs[DEVICE_ID]
            prefs.clear()
            if (deviceId != null) prefs[DEVICE_ID] = deviceId
        }
    }

    override suspend fun deviceId(): String {
        store.data.first()[DEVICE_ID]?.let { return it }
        var id = ""
        store.edit { prefs ->
            id = prefs[DEVICE_ID] ?: UUID.randomUUID().toString().also { prefs[DEVICE_ID] = it }
        }
        return id
    }

    private companion object {
        val PROFILE_NAME = stringPreferencesKey("profile_name")
        val ITEM_LANGUAGE = stringPreferencesKey("item_language")
        val HOUSEHOLD_ID = stringPreferencesKey("household_id")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("notifications_asked")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }
}
