package com.fridgelist.core.i18n

import com.fridgelist.core.model.ItemUnit
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalSplitterTest {
    private val splitter = LocalSplitter()

    private fun names(text: String, vararg hints: String) = splitter.split(text, hints.toList()).map { it.name }

    @Test
    fun nameIsKeptExactlyAsTyped() {
        val item = splitter.split("٢ كيلو بَندورة", listOf("ar")).single()
        assertEquals("بَندورة", item.name)
        assertEquals(2.0, item.qty)
        assertEquals(ItemUnit.KG, item.unit)
    }

    @Test
    fun wawGluedToAKnownWordSplits() {
        assertEquals(listOf("خبز", "حليب"), names("خبز وحليب", "ar"))
    }

    @Test
    fun standaloneWawSplits() {
        assertEquals(listOf("خبز", "بيض"), names("خبز و بيض", "ar"))
    }

    @Test
    fun wawInsideAWordDoesNotSplit() {
        assertEquals(listOf("ورق تواليت"), names("ورق تواليت", "ar"))
    }

    @Test
    fun wawBeforeAnUnknownWordDoesNotSplit() {
        assertEquals(listOf("وافل"), names("وافل", "ar"))
    }

    @Test
    fun wawNusIsAFractionNotASeparator() {
        val item = splitter.split("كيلو ونص لحمة مفرومة", listOf("ar-SY")).single()
        assertEquals(1.5, item.qty)
        assertEquals(ItemUnit.KG, item.unit)
        assertEquals("minced_meat", item.canonicalId)
    }

    @Test
    fun turkishDecimalCommaIsNotASeparator() {
        val item = splitter.split("1,5 kg kıyma", listOf("tr")).single()
        assertEquals(1.5, item.qty)
    }

    @Test
    fun englishFillerIsStripped() {
        assertEquals(listOf("dish soap"), names("we're out of dish soap", "en"))
    }

    @Test
    fun arabicFillerIsStripped() {
        assertEquals("bread", splitter.split("لا تنسوا الخبز", listOf("ar")).single().canonicalId)
    }

    @Test
    fun halfADozenIsSixPieces() {
        val item = splitter.split("half a dozen lemons", listOf("en")).single()
        assertEquals(6.0, item.qty)
        assertEquals(ItemUnit.PIECE, item.unit)
    }

    @Test
    fun aDozenEggs() {
        val item = splitter.split("a dozen eggs", listOf("en")).single()
        assertEquals(12.0, item.qty)
        assertEquals(ItemUnit.PIECE, item.unit)
    }

    @Test
    fun trailingQuantity() {
        val item = splitter.split("yumurta 30 tane", listOf("tr")).single()
        assertEquals(30.0, item.qty)
        assertEquals(ItemUnit.PIECE, item.unit)
        assertEquals("yumurta", item.name)
    }

    @Test
    fun gluedQuantityAndUnit() {
        val item = splitter.split("sugar 1kg", listOf("en")).single()
        assertEquals(1.0, item.qty)
        assertEquals(ItemUnit.KG, item.unit)
    }

    @Test
    fun multiplierAtTheEnd() {
        val item = splitter.split("Pampers size 4 x2", listOf("en")).single()
        assertEquals(2.0, item.qty)
        assertEquals("Pampers size 4", item.name)
        assertEquals("diapers", item.canonicalId)
    }

    @Test
    fun turkishElevenIsTwoWords() {
        assertEquals(11.0, splitter.split("on bir yumurta", listOf("tr")).single().qty)
    }

    @Test
    fun spaceOnlyListOfKnownItemsSplits() {
        assertEquals(listOf("domates", "biber", "patlıcan"), names("domates biber patlıcan", "tr"))
    }

    @Test
    fun longestSynonymWins() {
        assertEquals("arabic_bread", splitter.split("خبز عربي", listOf("ar")).single().canonicalId)
        assertEquals("baby_wipes", splitter.split("ıslak mendil", listOf("tr")).single().canonicalId)
    }

    @Test
    fun unitWithoutNumberMeansOne() {
        val item = splitter.split("لتر زيت زيتون", listOf("ar")).single()
        assertEquals(1.0, item.qty)
        assertEquals(ItemUnit.L, item.unit)
    }

    @Test
    fun shortUnitLetterInsideANameIsText() {
        val item = splitter.split("l carnitine", listOf("en")).single()
        assertNull(item.qty)
        assertEquals("l carnitine", item.name)
    }

    @Test
    fun ofAfterAUnitIsDropped() {
        assertEquals(listOf("rice"), names("2 bags of rice", "en"))
    }

    @Test
    fun dialectWordResolvesByLocaleHint() {
        assertEquals("milk", splitter.split("لبن", listOf("ar-EG")).single().canonicalId)
        assertEquals("yogurt", splitter.split("لبن", listOf("ar-SY")).single().canonicalId)
        assertEquals("laban_ayran", splitter.split("لبن", listOf("ar-SA")).single().canonicalId)
    }

    @Test
    fun dialectWordWithoutHintStaysAmbiguous() {
        assertNull(splitter.split("عيش", listOf("ar")).single().canonicalId)
    }

    @Test
    fun emptyAndSeparatorOnlyInputGivesNothing() {
        assertEquals(emptyList(), names("   "))
        assertEquals(emptyList(), names(" , ، و and "))
    }

    // --- QA edge cases (2026-10-19)

    @Test
    fun invisibleMarksAndNoBreakSpacesAreIgnored() {
        val rtl = splitter.split("‏حليب‏", listOf("ar")).single()
        assertEquals("حليب", rtl.name, "no bidi mark left in the name")
        assertEquals("milk", rtl.canonicalId)
        assertEquals("milk", splitter.split("milk​", listOf("en")).single().name)
        val nbsp = splitter.split("2 süt", listOf("tr")).single()
        assertEquals("süt", nbsp.name)
        assertEquals(2.0, nbsp.qty)
    }

    @Test
    fun numbersAloneOrOnlyFillerGiveNothing() {
        assertEquals(emptyList(), names("2"))
        assertEquals(emptyList(), names("2 kg"))
        assertEquals(emptyList(), names("x2"))
        assertEquals(emptyList(), names("we need", "en"))
        assertEquals(emptyList(), names("جيب", "ar"))
    }

    @Test
    fun oddNumbersStayInTheNameOrAreLeftToValidation() {
        assertEquals(listOf("-2 milk"), names("-2 milk", "en"))
        assertEquals(listOf("1.5.2 milk"), names("1.5.2 milk", "en"))
        // Out-of-range quantities are parsed; ItemPatches.validQty drops them before display and write.
        assertEquals(0.0, splitter.split("0 milk").single().qty)
    }

    @Test
    fun hugeInputDoesNotBlowUp() {
        assertEquals(1, splitter.split("a".repeat(5_000)).size)
        assertEquals(listOf("milk", "bread"), names("milk " + "and ".repeat(200) + "bread", "en"))
        assertEquals(listOf("🍎🍎"), names("🍎🍎"))
    }

    @Test
    fun arabicDecimalAndPersianDigits() {
        val rice = splitter.split("٢٫٥ كيلو رز", listOf("ar")).single()
        assertEquals(2.5, rice.qty)
        assertEquals(ItemUnit.KG, rice.unit)
        assertEquals(3.0, splitter.split("۳ نان", listOf("ar")).single().qty)
    }

    @Test
    fun commaWithSpaceIsASeparatorNotADecimal() {
        assertEquals(1.5, splitter.split("1,5 kg peynir", listOf("tr")).single().qty)
        assertEquals(listOf("süt", "ekmek"), names("süt,ekmek", "tr"))
    }
}
