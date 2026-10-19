package com.fridgelist.core.model

import org.junit.Test
import kotlin.test.assertEquals

class TextTest {
    @Test
    fun clipNeverSplitsAnEmoji() {
        val name = "a".repeat(Limits.ITEM_NAME_MAX - 1) + "🍎"
        assertEquals("a".repeat(Limits.ITEM_NAME_MAX - 1), name.clip(Limits.ITEM_NAME_MAX), "half an emoji is dropped")
        assertEquals("ab🍎", "ab🍎".clip(4))
        assertEquals("ab", "ab🍎".clip(3))
        assertEquals("", "🍎".clip(1))
        assertEquals("حليب", "حليب".clip(40))
        assertEquals("", "abc".clip(0))
    }
}
