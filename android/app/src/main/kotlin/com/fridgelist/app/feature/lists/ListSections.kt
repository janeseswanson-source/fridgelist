package com.fridgelist.app.feature.lists

import com.fridgelist.core.data.items.ListItems
import com.fridgelist.core.i18n.Catalog
import com.fridgelist.core.i18n.CategoryOrder
import com.fridgelist.core.i18n.DisplayRule
import com.fridgelist.core.i18n.Normalize
import com.fridgelist.core.model.Category
import com.fridgelist.core.model.ItemDoc
import com.fridgelist.core.model.ItemStatus

/** Someone who asked for an item: a member by name, or a guest from the fridge-tag page. */
data class Asker(val name: String?, val guest: Boolean = false)

data class ItemRowUi(
    val item: ItemDoc,
    /** ARCHITECTURE §4.3 display rule. */
    val title: String,
    val original: String?,
    /** Server category, else the local catalog's guess, else other. Display only; never written. */
    val category: Category,
    val askedBy: List<Asker>,
    /** Claimer's name when someone else claimed it. */
    val claimedByOther: String?,
    val claimedByMe: Boolean,
    val pendingSync: Boolean,
)

data class ListSections(
    val requests: List<ItemRowUi> = emptyList(),
    val groups: List<Pair<Category, List<ItemRowUi>>> = emptyList(),
    val missing: List<ItemRowUi> = emptyList(),
    val done: List<ItemRowUi> = emptyList(),
) {
    val toBuyCount: Int get() = groups.sumOf { it.second.size }
    val isEmpty: Boolean get() = requests.isEmpty() && groups.isEmpty() && missing.isEmpty() && done.isEmpty()
}

object ListSectionsBuilder {

    fun build(listItems: ListItems, ctx: HouseholdContext, hidden: Set<String> = emptySet(), catalog: Catalog = Catalog.bundled): ListSections {
        val rows = listItems.items
            .filterNot { it.id in hidden }
            .map { row(it, ctx, it.id in listItems.pendingIds, catalog) }
        val byStatus = rows.groupBy { it.item.status }
        val toBuy = byStatus[ItemStatus.OPEN].orEmpty() + byStatus[ItemStatus.CLAIMED].orEmpty()
        return ListSections(
            requests = byStatus[ItemStatus.PENDING].orEmpty().sortedBy { it.item.createdAt ?: Long.MAX_VALUE },
            groups = toBuy.groupBy { it.category }
                .toSortedMap(CategoryOrder.comparator)
                .map { (category, items) -> category to items.sortedWith(inCategory) },
            missing = byStatus[ItemStatus.MISSING].orEmpty().sortedWith(inCategory),
            done = byStatus[ItemStatus.DONE].orEmpty().sortedByDescending { it.item.doneAt ?: Long.MAX_VALUE },
        )
    }

    /** Urgent first, then the manual order, then oldest first. */
    private val inCategory: Comparator<ItemRowUi> = compareBy<ItemRowUi>(
        { !it.item.urgent },
        { it.item.sortKey ?: Double.MAX_VALUE },
        { it.item.createdAt ?: Long.MAX_VALUE },
    )

    private fun row(item: ItemDoc, ctx: HouseholdContext, pending: Boolean, catalog: Catalog): ItemRowUi {
        val display = DisplayRule.display(item.name, item.lang, item.names, ctx.viewerLang, ctx.defaultLanguage)
        val askers = (listOf(item.addedBy) + item.requestedBy).filter { it.isNotEmpty() }.distinct().map { uid ->
            if (uid.startsWith(GUEST_PREFIX)) Asker(null, guest = true) else Asker(ctx.memberNames[uid])
        }
        return ItemRowUi(
            item = item,
            title = display.title,
            original = display.original,
            category = item.category ?: guessCategory(item, ctx, catalog),
            askedBy = askers,
            claimedByOther = item.claimedBy?.takeIf { it != ctx.uid }?.let { ctx.memberNames[it] ?: "" },
            claimedByMe = item.claimedBy == ctx.uid && item.status == ItemStatus.CLAIMED,
            pendingSync = pending,
        )
    }

    private fun guessCategory(item: ItemDoc, ctx: HouseholdContext, catalog: Catalog): Category {
        val id = item.canonicalId
            ?: catalog.guessCanonical(Normalize.matchKey(item.name).split(' '), ctx.localeHints)
        return catalog.item(id)?.category?.let(Category::fromWire) ?: Category.OTHER
    }

    /** CONTRACT §3.6: functions write `addedBy` as `guest:<token>` for guest-page adds. */
    private const val GUEST_PREFIX = "guest:"
}
