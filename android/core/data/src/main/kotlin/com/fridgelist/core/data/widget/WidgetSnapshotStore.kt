package com.fridgelist.core.data.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fridgelist.core.model.WidgetSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** CONTRACT §7 on Android: DataStore key `widget_snapshot`. */
interface WidgetSnapshotStore {
    val snapshot: Flow<WidgetSnapshot?>

    suspend fun current(): WidgetSnapshot? = snapshot.first()

    suspend fun write(snapshot: WidgetSnapshot)

    /** Sign-out: widgets go back to "open the app". */
    suspend fun clear()
}

private val Context.widgetDataStore: DataStore<Preferences> by preferencesDataStore(name = "widget")

@Singleton
class DataStoreWidgetSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : WidgetSnapshotStore {

    override val snapshot: Flow<WidgetSnapshot?> = context.widgetDataStore.data.map { WidgetSnapshot.decode(it[KEY]) }

    override suspend fun write(snapshot: WidgetSnapshot) {
        context.widgetDataStore.edit { it[KEY] = snapshot.encode() }
    }

    override suspend fun clear() {
        context.widgetDataStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("widget_snapshot")
    }
}
