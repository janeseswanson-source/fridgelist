package com.fridgelist.core.data.firestore

import com.fridgelist.core.model.AddedVia
import com.fridgelist.core.model.Category
import com.fridgelist.core.model.CategorySource
import com.fridgelist.core.model.ItemDoc
import com.fridgelist.core.model.ItemStatus
import com.fridgelist.core.model.ItemUnit
import com.fridgelist.core.model.Limits

/** A field write, independent of the Firestore SDK so the rules below are plain unit tests. */
sealed interface FieldPatch {
    data class Set(val value: Any) : FieldPatch
    data object Delete : FieldPatch
    data object ServerTime : FieldPatch
}

typealias Patch = Map<String, FieldPatch>

/** What the user confirmed in the add sheet. */
data class ItemDraft(
    val name: String,
    val qty: Double? = null,
    val unit: ItemUnit? = null,
    val notes: String? = null,
    val urgent: Boolean = false,
)

/** Edits from the edit sheet; null means "clear". */
data class ItemEdit(
    val name: String,
    val qty: Double?,
    val unit: ItemUnit?,
    val notes: String?,
    val urgent: Boolean,
    val category: Category?,
)

/**
 * Item writes that match CONTRACT §3.6: only client-writable fields, server timestamps, and the
 * side fields each status needs. Server-owned fields (`lang`, `names`, `canonicalId`,
 * `requestedBy`, `recipeRef`) are never written here.
 */
object ItemPatches {

    fun create(draft: ItemDraft, status: ItemStatus, addedBy: String, via: AddedVia): Patch = buildMap {
        put("name", FieldPatch.Set(draft.name.trim().take(Limits.ITEM_NAME_MAX)))
        validQty(draft.qty)?.let { put("qty", FieldPatch.Set(it)) }
        draft.unit?.let { put("unit", FieldPatch.Set(it.wire)) }
        cleanNotes(draft.notes)?.let { put("notes", FieldPatch.Set(it)) }
        put("urgent", FieldPatch.Set(draft.urgent))
        put("status", FieldPatch.Set(status.wire))
        put("addedBy", FieldPatch.Set(addedBy))
        put("addedVia", FieldPatch.Set(via.wire))
        put("createdAt", FieldPatch.ServerTime)
        put("updatedAt", FieldPatch.ServerTime)
    }

    /** Moves an item to [to]; the caller checks `StatusRules.canTransition` first. */
    fun status(to: ItemStatus, actorUid: String): Patch = buildMap {
        put("status", FieldPatch.Set(to.wire))
        put("updatedAt", FieldPatch.ServerTime)
        when (to) {
            ItemStatus.CLAIMED -> put("claimedBy", FieldPatch.Set(actorUid))
            ItemStatus.DONE -> {
                put("doneBy", FieldPatch.Set(actorUid))
                put("doneAt", FieldPatch.ServerTime)
            }
            ItemStatus.OPEN -> {
                put("claimedBy", FieldPatch.Delete)
                put("doneBy", FieldPatch.Delete)
                put("doneAt", FieldPatch.Delete)
                put("doneTripId", FieldPatch.Delete)
            }
            ItemStatus.MISSING, ItemStatus.PENDING -> Unit
        }
    }

    /** Only changed fields; `name` only when [canRename] (creator only, CONTRACT §3.6). */
    fun edit(item: ItemDoc, edit: ItemEdit, canRename: Boolean): Patch = buildMap {
        val name = edit.name.trim().take(Limits.ITEM_NAME_MAX)
        if (canRename && name.isNotEmpty() && name != item.name) put("name", FieldPatch.Set(name))
        val qty = validQty(edit.qty)
        if (qty != item.qty) put("qty", qty?.let { FieldPatch.Set(it) } ?: FieldPatch.Delete)
        if (edit.unit != item.unit) put("unit", edit.unit?.let { FieldPatch.Set(it.wire) } ?: FieldPatch.Delete)
        val notes = cleanNotes(edit.notes)
        if (notes != item.notes) put("notes", notes?.let { FieldPatch.Set(it) } ?: FieldPatch.Delete)
        if (edit.urgent != item.urgent) put("urgent", FieldPatch.Set(edit.urgent))
        if (edit.category != null && edit.category != item.category) {
            // A member's category always comes with categorySource=user; the server never overwrites it.
            put("category", FieldPatch.Set(edit.category.wire))
            put("categorySource", FieldPatch.Set(CategorySource.USER.wire))
        }
        if (isNotEmpty()) put("updatedAt", FieldPatch.ServerTime)
    }

    /** 0 < qty ≤ 999 (CONTRACT §3.6); anything else is dropped. */
    fun validQty(qty: Double?): Double? = qty?.takeIf { it > 0 && it <= Limits.QTY_MAX }

    private fun cleanNotes(notes: String?): String? = notes?.trim()?.take(Limits.ITEM_NOTES_MAX)?.ifEmpty { null }
}
