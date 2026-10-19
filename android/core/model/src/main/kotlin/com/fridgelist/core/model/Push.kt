package com.fridgelist.core.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * FCM data payloads (CONTRACT §5.1, §5.3). All values arrive as strings. Unknown types and
 * malformed values never crash: they parse to [PushMessage.Unknown] or drop the bad field.
 */
sealed interface PushMessage {
    val householdId: String

    /** "Ali is heading to Migros": other members, with an inline reply (Android data-only, v2). */
    data class TripHeading(
        override val householdId: String,
        val tripId: String,
        val listId: String?,
        val lastCallUntil: Long?,
        val title: String,
        val body: String,
    ) : PushMessage

    data class TripArrived(
        override val householdId: String,
        val tripId: String,
        val listId: String?,
        val title: String,
        val body: String,
    ) : PushMessage

    data class ItemMissing(
        override val householdId: String,
        val itemId: String?,
        val listId: String?,
        val tripId: String?,
        val title: String,
        val body: String,
    ) : PushMessage

    /** §5.3: the Android shopper's own ongoing notification, while the app process is gone. */
    data class TripUpdate(
        override val householdId: String,
        val tripId: String,
        val status: TripStatus?,
        val storeName: String,
        val remaining: Int,
        val done: Int,
        val nextItems: List<String>,
        val lastCallUntil: Long?,
        val latestAddBy: String?,
        val latestAddName: String?,
    ) : PushMessage

    data class ListChanged(override val householdId: String, val listId: String) : PushMessage

    data class Unknown(override val householdId: String, val type: String?) : PushMessage

    companion object {
        fun parse(data: Map<String, String>): PushMessage? {
            val hid = data["householdId"]?.takeIf { it.isNotBlank() } ?: return null
            val title = data["title"].orEmpty()
            val body = data["body"].orEmpty()
            return when (data["type"]) {
                "trip_heading" -> data["tripId"]?.let {
                    TripHeading(hid, it, data["listId"], data["lastCallUntil"]?.toLongOrNull(), title, body)
                }
                "trip_arrived" -> data["tripId"]?.let { TripArrived(hid, it, data["listId"], title, body) }
                "item_missing" -> ItemMissing(hid, data["itemId"], data["listId"], data["tripId"], title, body)
                "trip_update" -> data["tripId"]?.let { tripId ->
                    TripUpdate(
                        householdId = hid,
                        tripId = tripId,
                        status = TripStatus.fromWire(data["status"]),
                        storeName = data["storeName"].orEmpty(),
                        remaining = count(data["remaining"]),
                        done = count(data["done"]),
                        nextItems = parseNextItems(data["nextItems"]),
                        lastCallUntil = data["lastCallUntil"]?.toLongOrNull(),
                        latestAddBy = data["latestAddBy"],
                        latestAddName = data["latestAddName"],
                    )
                }
                "list_changed" -> data["listId"]?.let { ListChanged(hid, it) }
                else -> Unknown(hid, data["type"])
            }
        }

        /** Item counts: never negative, and small enough that `done + remaining` cannot overflow. */
        private fun count(value: String?): Int = value?.toIntOrNull()?.coerceIn(0, MAX_COUNT) ?: 0

        private const val MAX_COUNT = 9_999

        /** `nextItems` is a JSON-encoded array of strings (§5.3). */
        private fun parseNextItems(json: String?): List<String> =
            json?.let { runCatching { ContractJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()
    }
}
