package com.fridgelist.core.i18n

import com.fridgelist.core.model.ItemUnit

/** Unit words in Arabic, Turkish and English (ARCHITECTURE §4.1). */
object Units {
    /**
     * @property unit the contract unit.
     * @property container when set, the unit depends on the item: canned food turns a "pack"/"box"
     *   into `can`, eggs turn a "box" into `tray`.
     * @property needsNumber short forms ("g", "l", "lt") only count right after a number, so
     *   "l" or "g" inside an item name stays text.
     */
    data class UnitWord(
        val unit: ItemUnit,
        val container: Container? = null,
        val needsNumber: Boolean = false,
    )

    enum class Container { CAN_IF_CANNED, TRAY_IF_EGGS }

    private val table: Map<String, UnitWord> = buildMap {
        fun add(word: UnitWord, vararg forms: String) = forms.forEach { put(Normalize.matchKey(it), word) }

        // Arabic
        add(UnitWord(ItemUnit.KG), "كيلو", "كيلوغرام", "كيلوجرام", "كغ", "كجم")
        add(UnitWord(ItemUnit.G), "غرام", "جرام", "غم", "جم")
        add(UnitWord(ItemUnit.L), "لتر", "ليتر", "لترات")
        add(UnitWord(ItemUnit.ML), "مل")
        add(UnitWord(ItemUnit.PACK, Container.CAN_IF_CANNED), "علبة", "علب", "علبه")
        add(UnitWord(ItemUnit.PACK), "باكيت", "باكو", "باكيتات")
        add(UnitWord(ItemUnit.BAG), "كيس", "أكياس")
        add(UnitWord(ItemUnit.BOTTLE), "قنينة", "قزازة", "إزازة", "زجاجة", "قناني")
        add(UnitWord(ItemUnit.BUNCH), "ربطة", "ربطات", "حزمة")
        add(UnitWord(ItemUnit.BOX, Container.TRAY_IF_EGGS), "كرتونة", "كرتون", "كراتين")
        add(UnitWord(ItemUnit.PIECE), "حبة", "حبات", "حبه")
        // Turkish
        add(UnitWord(ItemUnit.KG), "kilo", "kg", "kilogram")
        add(UnitWord(ItemUnit.G), "gram", "gr")
        add(UnitWord(ItemUnit.L), "litre", "litrelik")
        add(UnitWord(ItemUnit.PACK), "paket")
        add(UnitWord(ItemUnit.BOX, Container.CAN_IF_CANNED), "kutu")
        add(UnitWord(ItemUnit.BOX), "koli")
        add(UnitWord(ItemUnit.BOTTLE), "şişe")
        add(UnitWord(ItemUnit.BUNCH), "demet")
        add(UnitWord(ItemUnit.PIECE), "tane", "adet")
        add(UnitWord(ItemUnit.BAG), "poşet")
        // English
        add(UnitWord(ItemUnit.KG), "kilos", "kilograms")
        add(UnitWord(ItemUnit.G), "grams")
        add(UnitWord(ItemUnit.L), "liter", "liters", "litres")
        add(UnitWord(ItemUnit.PACK), "pack", "packs", "packet", "packets")
        add(UnitWord(ItemUnit.CAN), "can", "cans", "tin", "tins")
        add(UnitWord(ItemUnit.BOTTLE), "bottle", "bottles")
        add(UnitWord(ItemUnit.BAG), "bag", "bags")
        add(UnitWord(ItemUnit.BOX, Container.TRAY_IF_EGGS), "box", "boxes")
        add(UnitWord(ItemUnit.BUNCH), "bunch", "bunches")
        add(UnitWord(ItemUnit.TRAY), "tray", "trays")
        add(UnitWord(ItemUnit.PIECE), "piece", "pieces", "pcs", "pc")
        // Short forms that are only units after a number.
        add(UnitWord(ItemUnit.G, needsNumber = true), "g")
        add(UnitWord(ItemUnit.L, needsNumber = true), "l", "lt", "ltr")
        add(UnitWord(ItemUnit.ML, needsNumber = true), "ml")
    }

    fun lookup(key: String): UnitWord? = table[key]

    /** Resolves a container word against the item it describes. */
    fun resolve(word: UnitWord, canonicalId: String?, catalog: Catalog): ItemUnit =
        when (word.container) {
            Container.CAN_IF_CANNED -> if (catalog.isCanned(canonicalId)) ItemUnit.CAN else word.unit
            Container.TRAY_IF_EGGS -> if (canonicalId == Catalog.EGGS_ID) ItemUnit.TRAY else word.unit
            null -> word.unit
        }

    /** Short label for a unit, e.g. "2 L" in the widget snapshot subtitle. Localized labels live in app resources. */
    fun symbol(unit: ItemUnit): String = when (unit) {
        ItemUnit.KG -> "kg"
        ItemUnit.G -> "g"
        ItemUnit.L -> "L"
        ItemUnit.ML -> "ml"
        else -> unit.wire
    }
}
