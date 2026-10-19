package com.fridgelist.app.feature.lists

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.os.ConfigurationCompat
import com.fridgelist.app.R
import com.fridgelist.core.model.Category
import com.fridgelist.core.model.ItemUnit
import com.fridgelist.core.model.ListType
import java.text.NumberFormat
import java.util.Locale

@StringRes
fun Category.label(): Int = when (this) {
    Category.PRODUCE -> R.string.cat_produce
    Category.BAKERY -> R.string.cat_bakery
    Category.DAIRY_EGGS -> R.string.cat_dairy_eggs
    Category.MEAT_POULTRY -> R.string.cat_meat_poultry
    Category.SEAFOOD -> R.string.cat_seafood
    Category.PANTRY -> R.string.cat_pantry
    Category.SPICES_CONDIMENTS -> R.string.cat_spices_condiments
    Category.BREAKFAST -> R.string.cat_breakfast
    Category.FROZEN -> R.string.cat_frozen
    Category.BEVERAGES -> R.string.cat_beverages
    Category.SNACKS_SWEETS -> R.string.cat_snacks_sweets
    Category.BABY -> R.string.cat_baby
    Category.HOUSEHOLD_CLEANING -> R.string.cat_household_cleaning
    Category.PERSONAL_CARE -> R.string.cat_personal_care
    Category.PHARMACY -> R.string.cat_pharmacy
    Category.PETS -> R.string.cat_pets
    Category.OTHER -> R.string.cat_other
}

@StringRes
fun ListType.label(): Int = when (this) {
    ListType.GROCERY -> R.string.list_type_grocery
    ListType.PHARMACY -> R.string.list_type_pharmacy
    ListType.HOUSEHOLD -> R.string.list_type_household
    ListType.OTHER -> R.string.list_type_other
}

@StringRes
fun ItemUnit.label(): Int = when (this) {
    ItemUnit.PIECE -> R.string.unit_name_piece
    ItemUnit.KG -> R.string.unit_name_kg
    ItemUnit.G -> R.string.unit_name_g
    ItemUnit.L -> R.string.unit_name_l
    ItemUnit.ML -> R.string.unit_name_ml
    ItemUnit.PACK -> R.string.unit_name_pack
    ItemUnit.CAN -> R.string.unit_name_can
    ItemUnit.BOTTLE -> R.string.unit_name_bottle
    ItemUnit.BAG -> R.string.unit_name_bag
    ItemUnit.BOX -> R.string.unit_name_box
    ItemUnit.BUNCH -> R.string.unit_name_bunch
    ItemUnit.TRAY -> R.string.unit_name_tray
}

/** Counted units take plurals; measured units take a symbol. */
private sealed interface UnitFormat {
    data class Counted(@param:PluralsRes val res: Int) : UnitFormat
    data class Measured(@param:StringRes val res: Int) : UnitFormat
}

private fun ItemUnit?.format(): UnitFormat = when (this) {
    null -> UnitFormat.Measured(R.string.unit_none)
    ItemUnit.KG -> UnitFormat.Measured(R.string.unit_kg)
    ItemUnit.G -> UnitFormat.Measured(R.string.unit_g)
    ItemUnit.L -> UnitFormat.Measured(R.string.unit_l)
    ItemUnit.ML -> UnitFormat.Measured(R.string.unit_ml)
    // Pieces read as a count: "×12 eggs", not "12 pieces eggs" (dozens become pieces).
    ItemUnit.PIECE -> UnitFormat.Measured(R.string.unit_none)
    ItemUnit.PACK -> UnitFormat.Counted(R.plurals.unit_pack)
    ItemUnit.CAN -> UnitFormat.Counted(R.plurals.unit_can)
    ItemUnit.BOTTLE -> UnitFormat.Counted(R.plurals.unit_bottle)
    ItemUnit.BAG -> UnitFormat.Counted(R.plurals.unit_bag)
    ItemUnit.BOX -> UnitFormat.Counted(R.plurals.unit_box)
    ItemUnit.BUNCH -> UnitFormat.Counted(R.plurals.unit_bunch)
    ItemUnit.TRAY -> UnitFormat.Counted(R.plurals.unit_tray)
}

/** "2 kg", "٣ علب", "1,5 kg" in the UI locale (CLAUDE.md rule 3). Null when there is nothing to show. */
@Composable
fun quantityText(qty: Double?, unit: ItemUnit?): String? {
    if (qty == null && unit == null) return null
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    val amount = qty ?: 1.0
    val number = Quantities.format(amount, locale)
    return when (val f = unit.format()) {
        is UnitFormat.Measured -> stringResource(f.res, number)
        is UnitFormat.Counted -> pluralStringResource(f.res, Quantities.pluralCount(amount), number)
    }
}

object Quantities {
    fun format(amount: Double, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(amount)

    /** Plural rules take whole numbers; fractions use the "other"-style form via a large count. */
    fun pluralCount(amount: Double): Int = if (amount % 1.0 == 0.0) amount.toInt() else 100
}
