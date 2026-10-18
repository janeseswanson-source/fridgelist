package com.fridgelist.core.i18n

import com.fridgelist.core.model.ItemUnit
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NumbersUnitsTest {

    private fun value(word: String, turkish: Boolean = false) =
        (Numbers.word(Normalize.matchKey(word, turkish), turkish) as Numbers.Word.Value).value

    @Test
    fun arabicNumberWords() {
        assertEquals(3.0, value("ثلاثة"))
        assertEquals(2.0, value("اتنين"))
        assertEquals(12.0, value("اطنعش"))
    }

    @Test
    fun turkishNumberWords() {
        assertEquals(5.0, value("beş"))
        assertEquals(6.0, value("altı"))
    }

    @Test
    fun turkishTenNeedsTurkishHint() {
        assertEquals(10.0, value("on", turkish = true))
        assertNull(Numbers.word("on", turkish = false))
    }

    @Test
    fun englishNumberWords() {
        assertEquals(7.0, value("seven"))
        assertEquals(12.0, value("twelve"))
    }

    @Test
    fun numeralsParse() {
        assertEquals(1.5, Numbers.parseNumeral("1.5"))
        assertNull(Numbers.parseNumeral("1.5kg"))
    }

    @Test
    fun fractionsAndPlus() {
        assertEquals(Numbers.Word.Fraction(0.5), Numbers.word(Normalize.matchKey("نص"), false))
        assertEquals(Numbers.Word.Fraction(0.5), Numbers.word("yarım", true))
        assertEquals(Numbers.Word.Plus(0.5), Numbers.word(Normalize.matchKey("ونص"), false))
        assertEquals(Numbers.Word.Plus(0.5), Numbers.word("buçuk", true))
        assertEquals(Numbers.Word.Fraction(0.25), Numbers.word(Normalize.matchKey("ربع"), false))
    }

    @Test
    fun dozen() {
        assertEquals(Numbers.Word.Dozen, Numbers.word("dozen", false))
    }

    @Test
    fun arabicUnits() {
        assertEquals(ItemUnit.KG, Units.lookup(Normalize.matchKey("كيلو"))?.unit)
        assertEquals(ItemUnit.G, Units.lookup(Normalize.matchKey("جرام"))?.unit)
        assertEquals(ItemUnit.BAG, Units.lookup(Normalize.matchKey("كيس"))?.unit)
        assertEquals(ItemUnit.BOTTLE, Units.lookup(Normalize.matchKey("قزازة"))?.unit)
        assertEquals(ItemUnit.BUNCH, Units.lookup(Normalize.matchKey("ربطة"))?.unit)
        assertEquals(ItemUnit.PIECE, Units.lookup(Normalize.matchKey("حبة"))?.unit)
    }

    @Test
    fun turkishUnits() {
        assertEquals(ItemUnit.PACK, Units.lookup("paket")?.unit)
        assertEquals(ItemUnit.BOTTLE, Units.lookup("şişe")?.unit)
        assertEquals(ItemUnit.BUNCH, Units.lookup("demet")?.unit)
        assertEquals(ItemUnit.PIECE, Units.lookup("adet")?.unit)
    }

    @Test
    fun shortUnitsNeedANumber() {
        assertEquals(true, Units.lookup("l")?.needsNumber)
        assertEquals(false, Units.lookup("kg")?.needsNumber)
    }

    @Test
    fun containerResolvesToCanForCannedFood() {
        val box = Units.lookup(Normalize.matchKey("علبة"))!!
        assertEquals(ItemUnit.CAN, Units.resolve(box, "tuna", Catalog.bundled))
        assertEquals(ItemUnit.PACK, Units.resolve(box, "labneh", Catalog.bundled))
        val kutu = Units.lookup("kutu")!!
        assertEquals(ItemUnit.CAN, Units.resolve(kutu, "tomato_paste", Catalog.bundled))
        assertEquals(ItemUnit.BOX, Units.resolve(kutu, "tea", Catalog.bundled))
    }

    @Test
    fun cartonResolvesToTrayForEggs() {
        val carton = Units.lookup(Normalize.matchKey("كرتونة"))!!
        assertEquals(ItemUnit.TRAY, Units.resolve(carton, "eggs", Catalog.bundled))
        assertEquals(ItemUnit.BOX, Units.resolve(carton, "water", Catalog.bundled))
    }
}
