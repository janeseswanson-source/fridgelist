package com.fridgelist.core.data.items

import android.util.Log
import com.fridgelist.core.data.callable.CallableClient
import com.fridgelist.core.data.callable.call
import com.fridgelist.core.data.firestore.FieldPatch
import com.fridgelist.core.data.firestore.Mappers
import com.fridgelist.core.data.firestore.Patch
import com.fridgelist.core.model.ItemDoc
import com.fridgelist.core.model.ItemStatus
import com.fridgelist.core.model.ListDoc
import com.fridgelist.core.model.ListType
import com.fridgelist.core.model.ParseItemsRequest
import com.fridgelist.core.model.ParseItemsResponse
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Items of one list plus which of them still have local writes waiting to sync. */
data class ListItems(val items: List<ItemDoc> = emptyList(), val pendingIds: Set<String> = emptySet())

interface ListRepository {
    /** Non-archived lists in `order`. Served from the offline cache when offline. */
    fun lists(householdId: String): Flow<List<ListDoc>>

    /** Open + claimed items in a list (the badge on the lists screen). */
    fun openCount(householdId: String, listId: String): Flow<Int>

    /** Owner/adult only (CONTRACT §3.5). Offline-first; returns the new list ID at once. */
    fun createList(householdId: String, name: String, type: ListType, emoji: String?, order: Double): String
}

interface ItemRepository {
    fun items(householdId: String, listId: String): Flow<ListItems>

    /** Creates items in one batch. Offline-first; returns the new IDs at once. */
    fun add(householdId: String, listId: String, items: List<Patch>): List<String>

    fun update(householdId: String, listId: String, itemId: String, patch: Patch)

    fun delete(householdId: String, listId: String, itemId: String)

    /** Waits (briefly) until local writes reached the server: widget taps run in a short-lived process. */
    suspend fun flush() = Unit
}

interface AliasRepository {
    /** Household aliases as normalized text → canonicalId (CONTRACT §3.16). */
    fun aliases(householdId: String): Flow<Map<String, String>>

    fun save(householdId: String, aliasId: String, normalizedText: String, canonicalId: String, uid: String)
}

interface ParseRepository {
    /** CONTRACT §4 `parseItems`. Throws ApiException; callers fall back to the local splitter. */
    suspend fun parse(request: ParseItemsRequest): ParseItemsResponse
}

@Singleton
class FirestoreItemsData @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val callables: CallableClient,
) : ListRepository, ItemRepository, AliasRepository, ParseRepository {

    private fun household(hid: String) = firestore.collection("households").document(hid)

    override suspend fun flush() {
        withTimeoutOrNull(FLUSH_TIMEOUT_MS) { runCatching { firestore.waitForPendingWrites().await() } }
    }
    private fun listsRef(hid: String): CollectionReference = household(hid).collection("lists")
    private fun itemsRef(hid: String, lid: String): CollectionReference = listsRef(hid).document(lid).collection("items")

    override fun lists(householdId: String): Flow<List<ListDoc>> = callbackFlow {
        val reg = listsRef(householdId).addSnapshotListener { snap, error ->
            if (error != null) return@addSnapshotListener close(error).let { }
            val lists = snap?.documents.orEmpty()
                .mapNotNull { d -> d.data?.let { Mappers.list(d.id, it) } }
                .filterNot { it.archived }
                .sortedWith(compareBy<ListDoc>({ it.order }, { it.createdAt ?: Long.MAX_VALUE }))
            trySend(lists)
        }
        awaitClose { reg.remove() }
    }

    override fun openCount(householdId: String, listId: String): Flow<Int> = callbackFlow {
        val reg = itemsRef(householdId, listId)
            .whereIn("status", listOf(ItemStatus.OPEN.wire, ItemStatus.CLAIMED.wire))
            .addSnapshotListener { snap, error ->
                if (error != null) return@addSnapshotListener close(error).let { }
                trySend(snap?.size() ?: 0)
            }
        awaitClose { reg.remove() }
    }

    override fun createList(householdId: String, name: String, type: ListType, emoji: String?, order: Double): String {
        val ref = listsRef(householdId).document()
        val data = buildMap<String, Any> {
            put("name", name)
            put("type", type.wire)
            emoji?.let { put("emoji", it) }
            put("order", order)
            put("archived", false)
            put("createdAt", FieldValue.serverTimestamp())
        }
        ref.set(data).logFailure("list create")
        return ref.id
    }

    override fun items(householdId: String, listId: String): Flow<ListItems> = callbackFlow {
        // INCLUDE metadata so the "not synced yet" marker clears when the server confirms.
        val reg = itemsRef(householdId, listId).addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
            if (error != null) return@addSnapshotListener close(error).let { }
            val docs = snap?.documents.orEmpty()
            trySend(
                ListItems(
                    items = docs.mapNotNull { d -> d.data?.let { Mappers.item(d.id, it) } },
                    pendingIds = docs.filter { it.metadata.hasPendingWrites() }.map { it.id }.toSet(),
                ),
            )
        }
        awaitClose { reg.remove() }
    }

    override fun add(householdId: String, listId: String, items: List<Patch>): List<String> {
        if (items.isEmpty()) return emptyList()
        val batch = firestore.batch()
        val ids = items.map { patch ->
            val ref = itemsRef(householdId, listId).document()
            batch.set(ref, patch.toFirestore())
            ref.id
        }
        batch.commit().logFailure("item create")
        return ids
    }

    override fun update(householdId: String, listId: String, itemId: String, patch: Patch) {
        if (patch.isEmpty()) return
        itemsRef(householdId, listId).document(itemId).update(patch.toFirestore()).logFailure("item update")
    }

    override fun delete(householdId: String, listId: String, itemId: String) {
        itemsRef(householdId, listId).document(itemId).delete().logFailure("item delete")
    }

    override fun aliases(householdId: String): Flow<Map<String, String>> = callbackFlow {
        val reg = household(householdId).collection("aliases").addSnapshotListener { snap, error ->
            if (error != null) return@addSnapshotListener close(error).let { }
            trySend(
                snap?.documents.orEmpty().mapNotNull { d ->
                    val text = d.getString("text") ?: return@mapNotNull null
                    val id = d.getString("canonicalId") ?: return@mapNotNull null
                    text to id
                }.toMap(),
            )
        }
        awaitClose { reg.remove() }
    }

    override fun save(householdId: String, aliasId: String, normalizedText: String, canonicalId: String, uid: String) {
        household(householdId).collection("aliases").document(aliasId).set(
            mapOf(
                "text" to normalizedText,
                "canonicalId" to canonicalId,
                "createdBy" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).logFailure("alias save")
    }

    override suspend fun parse(request: ParseItemsRequest): ParseItemsResponse =
        callables.call("parseItems", request, ParseItemsRequest.serializer(), ParseItemsResponse.serializer())

    private companion object {
        const val FLUSH_TIMEOUT_MS = 5_000L
        const val TAG = "ItemsData"

        fun Patch.toFirestore(): Map<String, Any> = mapValues { (_, v) ->
            when (v) {
                is FieldPatch.Set -> v.value
                FieldPatch.Delete -> FieldValue.delete()
                FieldPatch.ServerTime -> FieldValue.serverTimestamp()
            }
        }

        /** Operation name and error class only: never item text (CLAUDE.md rule 4). */
        fun <T> Task<T>.logFailure(what: String) {
            addOnFailureListener { Log.w(TAG, "$what rejected: ${it.javaClass.simpleName}") }
        }
    }
}
