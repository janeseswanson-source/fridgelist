package com.fridgelist.core.data.firestore

import com.fridgelist.core.model.AddedVia
import com.fridgelist.core.model.Category
import com.fridgelist.core.model.ItemDoc
import com.fridgelist.core.model.ItemStatus
import com.fridgelist.core.model.ItemUnit
import com.google.firebase.Timestamp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemPatchesTest {
    private val set = FieldPatch::Set

    @Test
    fun createWritesOnlyClientFields() {
        val patch = ItemPatches.create(
            ItemDraft(name = "  بندورة ", qty = 2.0, unit = ItemUnit.KG, notes = " ", urgent = true),
            ItemStatus.OPEN,
            addedBy = "u1",
            via = AddedVia.APP_TEXT,
        )
        assertEquals(
            mapOf(
                "name" to set("بندورة"),
                "qty" to set(2.0),
                "unit" to set("kg"),
                "urgent" to set(true),
                "status" to set("open"),
                "addedBy" to set("u1"),
                "addedVia" to set("app_text"),
                "createdAt" to FieldPatch.ServerTime,
                "updatedAt" to FieldPatch.ServerTime,
            ),
            patch,
        )
        for (serverOwned in listOf("lang", "names", "canonicalId", "requestedBy", "recipeRef", "category", "notes")) {
            assertFalse(serverOwned in patch, serverOwned)
        }
    }

    @Test
    fun kidItemsAreCreatedPending() {
        assertEquals(set("pending"), ItemPatches.create(ItemDraft("x"), ItemStatus.PENDING, "kid", AddedVia.APP_TEXT)["status"])
    }

    @Test
    fun qtyOutsideContractRangeIsDropped() {
        assertNull(ItemPatches.validQty(0.0))
        assertNull(ItemPatches.validQty(1000.0))
        assertEquals(0.5, ItemPatches.validQty(0.5))
        assertFalse("qty" in ItemPatches.create(ItemDraft("x", qty = -1.0), ItemStatus.OPEN, "u", AddedVia.APP_TEXT))
    }

    @Test
    fun statusPatchesSetSideFields() {
        assertEquals(set("u2"), ItemPatches.status(ItemStatus.CLAIMED, "u2")["claimedBy"])
        val done = ItemPatches.status(ItemStatus.DONE, "u2")
        assertEquals(set("u2"), done["doneBy"])
        assertEquals(FieldPatch.ServerTime, done["doneAt"])
        val reopened = ItemPatches.status(ItemStatus.OPEN, "u2")
        for (f in listOf("claimedBy", "doneBy", "doneAt", "doneTripId")) assertEquals(FieldPatch.Delete, reopened[f], f)
        assertEquals(FieldPatch.ServerTime, reopened["updatedAt"])
    }

    private val item = ItemDoc(id = "i1", name = "milk", qty = 2.0, unit = ItemUnit.L, addedBy = "u1", category = Category.DAIRY_EGGS)

    @Test
    fun editWritesOnlyChanges() {
        val unchanged = ItemEdit("milk", 2.0, ItemUnit.L, null, false, Category.DAIRY_EGGS)
        assertTrue(ItemPatches.edit(item, unchanged, canRename = true).isEmpty())

        val patch = ItemPatches.edit(item, unchanged.copy(qty = null, notes = "low fat", urgent = true), canRename = true)
        assertEquals(FieldPatch.Delete, patch["qty"])
        assertEquals(set("low fat"), patch["notes"])
        assertEquals(set(true), patch["urgent"])
        assertEquals(FieldPatch.ServerTime, patch["updatedAt"])
    }

    @Test
    fun categoryEditMarksUserSource() {
        val patch = ItemPatches.edit(item, ItemEdit("milk", 2.0, ItemUnit.L, null, false, Category.BEVERAGES), canRename = true)
        assertEquals(set("beverages"), patch["category"])
        assertEquals(set("user"), patch["categorySource"])
    }

    @Test
    fun onlyTheCreatorRenames() {
        val edit = ItemEdit("oat milk", 2.0, ItemUnit.L, null, false, null)
        assertFalse("name" in ItemPatches.edit(item, edit, canRename = false))
        assertEquals(set("oat milk"), ItemPatches.edit(item, edit, canRename = true)["name"])
    }

    @Test
    fun itemMapping() {
        val doc = Mappers.item(
            "i1",
            mapOf(
                "name" to "بندورة", "lang" to "ar", "names" to mapOf("tr" to "Domates", "x" to 3),
                "canonicalId" to "tomato", "qty" to 2L, "unit" to "kg", "status" to "claimed",
                "claimedBy" to "u2", "addedBy" to "u1", "addedVia" to "app_voice", "requestedBy" to listOf("u3"),
                "createdAt" to Timestamp(1760780000, 0),
            ),
        )
        assertEquals(mapOf("tr" to "Domates"), doc.names)
        assertEquals(2.0, doc.qty)
        assertEquals(ItemUnit.KG, doc.unit)
        assertEquals(ItemStatus.CLAIMED, doc.status)
        assertEquals(AddedVia.APP_VOICE, doc.addedVia)
        assertNull(doc.category, "not classified yet")
        assertEquals(1760780000L, doc.createdAt)
    }

    @Test
    fun listMapping() {
        val list = Mappers.list("l1", mapOf("name" to "البقالة", "type" to "grocery", "order" to 1L, "emoji" to "🛒"))
        assertEquals("البقالة", list.name)
        assertEquals(1.0, list.order)
        assertFalse(list.archived)
    }
}
