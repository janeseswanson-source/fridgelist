package com.fridgelist.core.i18n

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NormalizeTest {

    @Test
    fun easternArabicDigitsBecomeAscii() {
        assertEquals("0123456789", Normalize.digitsToAscii("٠١٢٣٤٥٦٧٨٩"))
    }

    @Test
    fun persianDigitsBecomeAscii() {
        assertEquals("0123456789", Normalize.digitsToAscii("۰۱۲۳۴۵۶۷۸۹"))
    }

    @Test
    fun arabicDecimalSeparatorBecomesDot() {
        assertEquals("1.5", Normalize.digitsToAscii("١٫٥"))
    }

    @Test
    fun tashkeelIsStripped() {
        assertEquals("خبز", Normalize.arabic("خُبْزٌ"))
    }

    @Test
    fun tatweelIsStripped() {
        assertEquals("حليب", Normalize.arabic("حلـــيب"))
    }

    @Test
    fun alefFormsAreUnified() {
        assertEquals("ارز ارز ارز", Normalize.arabic("أرز إرز آرز"))
    }

    @Test
    fun alefMaksuraBecomesYeh() {
        assertEquals("مربي", Normalize.arabic("مربى"))
    }

    @Test
    fun finalTehMarbutaMatchesHeh() {
        assertEquals(Normalize.matchKey("لبنة"), Normalize.matchKey("لبنه"))
        assertEquals(Normalize.matchKey("طحينة"), Normalize.matchKey("طحينه"))
    }

    @Test
    fun tehMarbutaInsideAWordIsKept() {
        // Only the final letter folds.
        assertEquals("لبنه بلديه", Normalize.matchKey("لبنة بلدية"))
    }

    @Test
    fun turkishDottedCapitalI() {
        assertEquals("islak", Normalize.lowercase("İslak", turkish = true))
    }

    @Test
    fun turkishDotlessCapitalI() {
        assertEquals("ıslak mendil", Normalize.lowercase("ISLAK MENDİL", turkish = true))
    }

    @Test
    fun nonTurkishLowercaseIsLocaleNeutral() {
        assertEquals("milk", Normalize.lowercase("MILK", turkish = false))
    }

    @Test
    fun turkishDecimalCommaBecomesDot() {
        assertEquals("1.5 kg", Normalize.decimalComma("1,5 kg"))
    }

    @Test
    fun listCommaIsNotADecimal() {
        assertEquals("milk, 6 eggs", Normalize.decimalComma("milk, 6 eggs"))
    }

    @Test
    fun matchKeyCollapsesWhitespace() {
        assertEquals("zeytin yağı", Normalize.matchKey("  Zeytin   yağı ", turkish = true))
    }

    @Test
    fun matchKeyCombinesAllRules() {
        assertEquals("2 كيلو بندوره", Normalize.matchKey("٢ كيلـو بَنْدورة"))
    }

    @Test
    fun turkishHintDetection() {
        assertTrue(Normalize.isTurkish(listOf("tr")))
        assertTrue(Normalize.isTurkish(listOf("en", "tr-TR")))
        assertFalse(Normalize.isTurkish(listOf("ar-SA", "en")))
    }

    @Test
    fun invisibleCharactersAreRemovedAndSpacesUnified() {
        assertEquals("حليب", Normalize.invisible("‏‫حليب‬﻿"))
        assertEquals("2 süt", Normalize.invisible("2 süt"))
        assertEquals("نان", Normalize.matchKey("‌نان"), "match keys also drop ZWNJ")
        assertEquals("a b", Normalize.matchKey(" a  b "))
    }
}
