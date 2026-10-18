package com.fridgelist.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fridgelist.core.data.callable.CallableClient
import com.fridgelist.core.data.firebase.EmulatorConfig
import com.fridgelist.core.data.firestore.ItemDraft
import com.fridgelist.core.data.firestore.ItemPatches
import com.fridgelist.core.data.items.FirestoreItemsData
import com.fridgelist.core.data.items.ListItems
import com.fridgelist.core.model.AddedVia
import com.fridgelist.core.model.ItemStatus
import com.fridgelist.core.model.ItemUnit
import com.fridgelist.core.model.StatusRules
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A3 acceptance (prompt 02): "in airplane mode you can add, tick, undo and delete; back online
 * [it syncs]". Uses the same repository code as the app against the Firestore emulator, with the
 * network switched off in the SDK (the app's offline cache path).
 *
 * The second half of the criterion — "the server fills categories and translations within
 * seconds" — needs backend B4's onItemCreated and is covered once that exists.
 */
@RunWith(AndroidJUnit4::class)
class OfflineItemsTest {
    private val host = EmulatorConfig.DEFAULT_HOST

    @Before
    fun firestoreEmulatorRunning() {
        val up = runCatching { Socket().use { it.connect(InetSocketAddress(host, EmulatorConfig.FIRESTORE_PORT), 1_000) } }.isSuccess
        assumeTrue("Firestore emulator not running on $host:${EmulatorConfig.FIRESTORE_PORT}", up)
    }

    private fun firestore(): FirebaseFirestore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == "offline" } ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId(EmulatorConfig.DEMO_PROJECT_ID)
                .setApplicationId(EmulatorConfig.DEMO_APP_ID)
                .setApiKey(EmulatorConfig.DEMO_API_KEY)
                .build(),
            "offline",
        )
        return FirebaseFirestore.getInstance(app).apply {
            runCatching {
                firestoreSettings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                    .build()
                useEmulator(host, EmulatorConfig.FIRESTORE_PORT)
            }
        }
    }

    private val noCallables = object : CallableClient {
        override suspend fun call(name: String, body: JsonElement): JsonElement = error("not used")
    }

    @Test
    fun addTickUndoDeleteOfflineThenSync() = runBlocking {
        val db = firestore()
        val data = FirestoreItemsData(db, noCallables)
        val hid = "offline-" + UUID.randomUUID()
        val lid = "groceries"
        val uid = "u-offline"

        db.disableNetwork().await()

        // Add two items while offline: the writes queue locally and the list shows them at once.
        val (milk, bread) = data.add(
            hid, lid,
            listOf(
                ItemPatches.create(ItemDraft("حليب", 2.0, ItemUnit.L), ItemStatus.OPEN, uid, AddedVia.APP_TEXT),
                ItemPatches.create(ItemDraft("ekmek"), ItemStatus.OPEN, uid, AddedVia.APP_TEXT),
            ),
        )
        val offline = data.items(hid, lid).awaitUntil { it.items.size == 2 }
        assertEquals(setOf(milk, bread), offline.pendingIds, "both marked as not synced yet")

        // Tick milk, then Undo (done → open per the transition table), and delete bread.
        data.update(hid, lid, milk, ItemPatches.status(ItemStatus.DONE, uid))
        data.items(hid, lid).awaitUntil { li -> li.items.any { it.id == milk && it.status == ItemStatus.DONE } }
        val back = StatusRules.undoTarget(ItemStatus.OPEN, ItemStatus.DONE, com.fridgelist.core.model.Role.HELPER, false)!!
        data.update(hid, lid, milk, ItemPatches.status(back, uid))
        data.delete(hid, lid, bread)
        data.items(hid, lid).awaitUntil { li -> li.items.size == 1 && li.items.single().status == ItemStatus.OPEN }

        // Back online: everything syncs and nothing stays pending.
        db.enableNetwork().await()
        data.items(hid, lid).awaitUntil { it.pendingIds.isEmpty() && it.items.size == 1 }

        val server = db.collection("households").document(hid).collection("lists").document(lid)
            .collection("items").get(Source.SERVER).await()
        assertEquals(listOf(milk), server.documents.map { it.id })
        val doc = server.documents.single()
        assertEquals("open", doc.getString("status"))
        assertEquals(2.0, doc.getDouble("qty"))
        assertEquals("l", doc.getString("unit"))
        assertEquals(uid, doc.getString("addedBy"))
        assertFalse(doc.contains("doneBy"), "Undo cleared the done fields")
        assertTrue(doc.getTimestamp("createdAt") != null, "server timestamp resolved after sync")
    }

    private suspend fun kotlinx.coroutines.flow.Flow<ListItems>.awaitUntil(predicate: (ListItems) -> Boolean): ListItems =
        withTimeout(15_000) { first(predicate) }
}
