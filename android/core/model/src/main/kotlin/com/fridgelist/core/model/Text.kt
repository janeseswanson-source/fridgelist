package com.fridgelist.core.model

/**
 * Cuts to at most [max] UTF-16 units (how CONTRACT limits are counted, as in JavaScript) without
 * splitting a surrogate pair: "…🍎" cut through the emoji drops the emoji instead of leaving half.
 */
fun String.clip(max: Int): String {
    if (length <= max) return this
    val end = if (max > 0 && this[max - 1].isHighSurrogate()) max - 1 else max
    return substring(0, end)
}
