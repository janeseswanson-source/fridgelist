package com.fridgelist.app

import com.fridgelist.app.feature.lists.AddItemsUiState
import com.fridgelist.app.feature.lists.Chip
import com.fridgelist.app.feature.lists.Choice
import com.fridgelist.app.feature.lists.HouseholdContext
import com.fridgelist.app.feature.lists.ItemActions
import com.fridgelist.app.feature.lists.ItemRowUi
import com.fridgelist.app.feature.lists.ListDetailUiState
import com.fridgelist.app.feature.lists.ListRowUi
import com.fridgelist.app.feature.lists.ListSectionsBuilder
import com.fridgelist.app.feature.lists.ListsUiState
import com.fridgelist.core.data.items.ListItems
import com.fridgelist.app.feature.shopping.ShoppingActions
import com.fridgelist.app.feature.shopping.ShoppingSummary
import com.fridgelist.app.feature.shopping.ShoppingUiState
import com.fridgelist.core.model.Category
import com.fridgelist.core.model.HouseholdDoc
import com.fridgelist.core.model.ItemDoc
import com.fridgelist.core.model.ItemStatus
import com.fridgelist.core.model.ItemUnit
import com.fridgelist.core.model.ListDoc
import com.fridgelist.core.model.ListType
import com.fridgelist.core.model.Role
import com.fridgelist.core.model.StatusRules

/** Realistic, mixed-language sample data for screenshots (names are as members typed them). */
object Samples {
    val lists = ListsUiState(
        household = HouseholdDoc("h1", "بيت العائلة", "u1", "ar"),
        lists = listOf(
            ListRowUi(ListDoc("l1", "البقالة", ListType.GROCERY, order = 0.0), openCount = 9),
            ListRowUi(ListDoc("l2", "Eczane", ListType.PHARMACY, emoji = "💊", order = 1.0), openCount = 1),
            ListRowUi(ListDoc("l3", "Cleaning", ListType.HOUSEHOLD, emoji = "🧽", order = 2.0), openCount = 0),
        ),
        canCreate = true,
        loaded = true,
    )

    private val ctx = HouseholdContext(
        "h1", "u1", Role.ADULT, viewerLang = "ar", defaultLanguage = "ar",
        memberNames = mapOf("u1" to "Yasser", "u2" to "Ayşe", "u3" to "Maria", "u4" to "Lina"),
    )

    private fun item(
        id: String,
        name: String,
        lang: String,
        names: Map<String, String>? = null,
        category: Category? = null,
        qty: Double? = null,
        unit: ItemUnit? = null,
        status: ItemStatus = ItemStatus.OPEN,
        by: String = "u2",
        urgent: Boolean = false,
        claimedBy: String? = null,
    ) = ItemDoc(
        id = id, name = name, lang = lang, names = names, category = category, qty = qty, unit = unit,
        status = status, addedBy = by, urgent = urgent, claimedBy = claimedBy, createdAt = id.toLong(),
    )

    val detail = ListDetailUiState(
        listName = "البقالة",
        sections = ListSectionsBuilder.build(
            ListItems(
                listOf(
                    item("1", "Domates", "tr", mapOf("ar" to "طماطم", "en" to "Tomatoes"), Category.PRODUCE, 2.0, ItemUnit.KG),
                    item("2", "بقدونس", "ar", category = Category.PRODUCE, qty = 1.0, unit = ItemUnit.BUNCH, by = "u1"),
                    item("3", "لبنة", "ar", category = Category.DAIRY_EGGS, qty = 3.0, unit = ItemUnit.PACK, by = "u1", urgent = true),
                    item("4", "milk", "en", mapOf("ar" to "حليب"), Category.DAIRY_EGGS, 1.5, ItemUnit.L, by = "u3", status = ItemStatus.CLAIMED, claimedBy = "u2"),
                    item("5", "خبز عربي", "ar", category = Category.BAKERY, by = "guest:abc"),
                    item("6", "شيبس", "ar", status = ItemStatus.PENDING, by = "u4"),
                    item("7", "Hellim", "tr", mapOf("ar" to "جبنة حلوم"), Category.DAIRY_EGGS, status = ItemStatus.MISSING),
                    item("8", "سكر", "ar", category = Category.PANTRY, status = ItemStatus.DONE),
                ),
                pendingIds = setOf("2"),
            ),
            ctx,
        ),
        role = Role.ADULT,
        uid = "u1",
        loaded = true,
    )

    val add = AddItemsUiState(
        text = "٢ كيلو بندورة، عيش وحليب",
        chips = listOf(
            Chip(0, "بندورة", 2.0, ItemUnit.KG, "tomato"),
            Chip(1, "عيش", null, null, null, listOf(Choice("bread", "خبز"), Choice("rice", "رز")), "عيش"),
            Chip(2, "حليب", null, null, "milk"),
        ),
    )

    val shopping: ShoppingUiState
        get() {
            val sec = detail.sections
            val rows = sec.groups.flatMap { it.second }
            val others = rows.filter { it.claimedByOther != null && it.item.status == ItemStatus.CLAIMED }
            return ShoppingUiState(
                listName = "البقالة",
                toBuy = sec.groups.map { (c, r) -> c to r.filterNot { it in others } }.filter { it.second.isNotEmpty() },
                claimedByOthers = others,
                inCart = sec.done,
                missing = sec.missing,
                localMode = true,
                loaded = true,
            )
        }

    val summary: ShoppingSummary
        get() = ShoppingSummary(bought = detail.sections.done, missing = detail.sections.missing, rest = detail.sections.groups.flatMap { it.second })

    val shoppingActions = object : ShoppingActions {
        override fun canTick(row: ItemRowUi) = true
        override fun canMiss(row: ItemRowUi) = row.item.status != ItemStatus.DONE
        override fun tick(row: ItemRowUi) = Unit
        override fun miss(row: ItemRowUi) = Unit
    }

    val editRow: ItemRowUi get() = detail.sections.groups.first { it.first == Category.DAIRY_EGGS }.second.first()

    /** Offers what an adult may do, so menus and checkboxes render as for a real adult. */
    val adultActions = object : ItemActions {
        override fun can(row: ItemRowUi, to: ItemStatus) = StatusRules.canTransition(row.item.status, to, Role.ADULT, row.item.claimedBy == "u1")
        override fun canDelete(row: ItemRowUi) = StatusRules.canDelete(row.item.status, Role.ADULT)
        override fun toggleDone(row: ItemRowUi) = Unit
        override fun markMissing(row: ItemRowUi) = Unit
        override fun reopen(row: ItemRowUi) = Unit
        override fun claim(row: ItemRowUi) = Unit
        override fun unclaim(row: ItemRowUi) = Unit
        override fun approve(row: ItemRowUi) = Unit
        override fun decline(row: ItemRowUi) = Unit
        override fun delete(row: ItemRowUi) = Unit
        override fun edit(row: ItemRowUi) = Unit
    }
}
