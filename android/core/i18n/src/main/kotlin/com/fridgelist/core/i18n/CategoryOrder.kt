package com.fridgelist.core.i18n

import com.fridgelist.core.model.Category

/** Default category display order (CONTRACT §2), used until a store has a learned aisle order. */
object CategoryOrder {
    val DEFAULT: List<Category> = listOf(
        Category.PRODUCE,
        Category.BAKERY,
        Category.DAIRY_EGGS,
        Category.MEAT_POULTRY,
        Category.SEAFOOD,
        Category.BREAKFAST,
        Category.PANTRY,
        Category.SPICES_CONDIMENTS,
        Category.SNACKS_SWEETS,
        Category.BEVERAGES,
        Category.FROZEN,
        Category.BABY,
        Category.HOUSEHOLD_CLEANING,
        Category.PERSONAL_CARE,
        Category.PHARMACY,
        Category.PETS,
        Category.OTHER,
    )

    private val rank: Map<Category, Int> = DEFAULT.withIndex().associate { it.value to it.index }

    fun rankOf(category: Category): Int = rank.getValue(category)

    val comparator: Comparator<Category> = compareBy(::rankOf)
}
