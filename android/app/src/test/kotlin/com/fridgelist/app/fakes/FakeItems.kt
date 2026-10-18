package com.fridgelist.app.fakes

import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.firestore.Patch
import com.fridgelist.core.data.items.AliasRepository
import com.fridgelist.core.data.items.ItemRepository
import com.fridgelist.core.data.items.ListItems
import com.fridgelist.core.data.items.ListRepository
import com.fridgelist.core.data.items.ParseRepository
import com.fridgelist.core.model.ItemDoc
import com.fridgelist.core.model.ListDoc
import com.fridgelist.core.model.ListType
import com.fridgelist.core.model.ParseItemsRequest
import com.fridgelist.core.model.ParseItemsResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory lists, items, aliases and parser that record every write. */
class FakeItems : ListRepository, ItemRepository, AliasRepository, ParseRepository {
    val lists = MutableStateFlow<List<ListDoc>>(emptyList())
    val listItems = MutableStateFlow(ListItems())
    val added = mutableListOf<Patch>()
    val updates = mutableListOf<Pair<String, Patch>>()
    val deleted = mutableListOf<String>()
    val createdLists = mutableListOf<Triple<String, ListType, String?>>()
    val savedAliases = mutableListOf<List<String>>()
    val aliasMap = MutableStateFlow<Map<String, String>>(emptyMap())
    var parseResponse: ParseItemsResponse? = null
    val parseRequests = mutableListOf<ParseItemsRequest>()

    override fun lists(householdId: String): Flow<List<ListDoc>> = lists
    override fun openCount(householdId: String, listId: String): Flow<Int> = listItems.map { li -> li.items.count { it.status.wire in setOf("open", "claimed") } }
    override fun createList(householdId: String, name: String, type: ListType, emoji: String?, order: Double): String {
        createdLists += Triple(name, type, emoji)
        return "l-new"
    }

    override fun items(householdId: String, listId: String): Flow<ListItems> = listItems
    override fun add(householdId: String, listId: String, items: List<Patch>): List<String> {
        added += items
        return items.indices.map { "new-$it" }
    }
    override fun update(householdId: String, listId: String, itemId: String, patch: Patch) {
        updates += itemId to patch
    }
    override fun delete(householdId: String, listId: String, itemId: String) {
        deleted += itemId
    }

    override fun aliases(householdId: String): Flow<Map<String, String>> = aliasMap
    override fun save(householdId: String, aliasId: String, normalizedText: String, canonicalId: String, uid: String) {
        savedAliases += listOf(aliasId, normalizedText, canonicalId, uid)
    }

    override suspend fun parse(request: ParseItemsRequest): ParseItemsResponse {
        parseRequests += request
        return parseResponse ?: throw ApiException(ApiError.NETWORK)
    }

    fun setItems(vararg items: ItemDoc, pending: Set<String> = emptySet()) {
        listItems.value = ListItems(items.toList(), pending)
    }
}
