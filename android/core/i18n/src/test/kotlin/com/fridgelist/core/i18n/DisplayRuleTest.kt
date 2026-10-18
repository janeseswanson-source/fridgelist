package com.fridgelist.core.i18n

import com.fridgelist.core.model.Category
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayRuleTest {
    private val names = mapOf("ar" to "طماطم", "tr" to "Domates", "en" to "Tomatoes")

    @Test
    fun viewerLanguageTranslationWins() {
        val d = DisplayRule.display("بندورة", "ar", names, viewerLang = "tr", householdDefaultLanguage = "ar")
        assertEquals("Domates", d.title)
        assertEquals("بندورة", d.original)
    }

    @Test
    fun sameLanguageWithoutTranslationShowsNameAndNoOriginal() {
        val d = DisplayRule.display("بندورة", "ar", null, viewerLang = "ar", householdDefaultLanguage = "tr")
        assertEquals("بندورة", d.title)
        assertNull(d.original)
    }

    @Test
    fun fallsBackToHouseholdDefaultLanguage() {
        val d = DisplayRule.display("süt", "tr", mapOf("ar" to "حليب"), viewerLang = "fil", householdDefaultLanguage = "ar")
        assertEquals("حليب", d.title)
        assertEquals("süt", d.original)
    }

    @Test
    fun fallsBackToNameWhenNothingElse() {
        val d = DisplayRule.display("labne", null, null, viewerLang = "hi", householdDefaultLanguage = "en")
        assertEquals("labne", d.title)
        assertNull(d.original)
    }

    @Test
    fun translationEqualToNameHasNoOriginal() {
        val d = DisplayRule.display("Bulgur", "tr", mapOf("en" to "Bulgur"), viewerLang = "en", householdDefaultLanguage = "tr")
        assertNull(d.original)
    }

    @Test
    fun categoryOrderMatchesContract() {
        assertEquals(17, CategoryOrder.DEFAULT.size)
        assertEquals(Category.entries.toSet(), CategoryOrder.DEFAULT.toSet())
        assertEquals(Category.PRODUCE, CategoryOrder.DEFAULT.first())
        assertEquals(Category.OTHER, CategoryOrder.DEFAULT.last())
        // breakfast sorts before pantry, frozen after beverages (CONTRACT §2).
        val sorted = listOf(Category.FROZEN, Category.PANTRY, Category.BEVERAGES, Category.BREAKFAST)
            .sortedWith(CategoryOrder.comparator)
        assertEquals(listOf(Category.BREAKFAST, Category.PANTRY, Category.BEVERAGES, Category.FROZEN), sorted)
    }
}
