package com.fridgelist.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON shapes exchanged with callables and widgets (CONTRACT §4 and §7). Enum-typed fields are kept
 * as wire strings so an unknown value never fails decoding; use the typed accessors.
 * Times are Unix seconds (CONTRACT §1).
 */

val ContractJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class ParsedItem(
    val name: String,
    val canonicalId: String? = null,
    val qty: Double? = null,
    val unit: String? = null,
    val notes: String? = null,
    val category: String? = null,
    val names: Map<String, String>? = null,
    val confidence: Double = 0.0,
) {
    val unitEnum: ItemUnit? get() = ItemUnit.fromWire(unit)
    val categoryEnum: Category get() = Category.fromWire(category)
}

@Serializable
data class WidgetSnapshot(
    val v: Int = 1,
    val updatedAt: Long,
    val viewerLang: String,
    val householdId: String,
    val lists: List<SnapshotList> = emptyList(),
    val activeTrip: SnapshotTrip? = null,
) {
    companion object {
        /** CONTRACT §7: at most 12 items per list. */
        const val MAX_ITEMS_PER_LIST = 12
    }
}

@Serializable
data class SnapshotList(
    val id: String,
    val name: String,
    val openCount: Int,
    val items: List<SnapshotItem> = emptyList(),
)

@Serializable
data class SnapshotItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val category: String,
    val status: String,
    val urgent: Boolean = false,
)

@Serializable
data class SnapshotTrip(
    val id: String,
    val storeName: String,
    val remaining: Int,
    val done: Int,
    val lastCallUntil: Long? = null,
)
