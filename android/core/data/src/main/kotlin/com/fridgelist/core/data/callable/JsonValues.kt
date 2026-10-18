package com.fridgelist.core.data.callable

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Bridges kotlinx JSON and the plain Map/List/primitive values the Firebase Functions SDK sends
 * and returns.
 */
object JsonValues {

    fun toPlain(element: JsonElement): Any? = when (element) {
        JsonNull -> null
        is JsonObject -> element.mapValues { (_, v) -> toPlain(v) }
        is JsonArray -> element.map(::toPlain)
        is JsonPrimitive -> when {
            element.isString -> element.content
            else -> element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.content
        }
    }

    fun fromPlain(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to fromPlain(v) })
        is Iterable<*> -> JsonArray(value.map(::fromPlain))
        is Array<*> -> JsonArray(value.map(::fromPlain))
        else -> JsonPrimitive(value.toString())
    }
}
