package com.fridgelist.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContractModelTest {

    @Test
    fun unknownEnumValuesNeverCrash() {
        assertEquals(Category.OTHER, Category.fromWire("space_food"))
        assertEquals(ListType.OTHER, ListType.fromWire(null))
        assertEquals(ItemStatus.OPEN, ItemStatus.fromWire("archived"))
        assertEquals(ShareScope.VIEW, ShareScope.fromWire("admin"))
        assertNull(ItemUnit.fromWire("cup"))
        assertNull(AddedVia.fromWire("telepathy"))
    }

    @Test
    fun wireValuesRoundTrip() {
        for (c in Category.entries) assertEquals(c, Category.fromWire(c.wire))
        for (u in ItemUnit.entries) assertEquals(u, ItemUnit.fromWire(u.wire))
        for (v in AddedVia.entries) assertEquals(v, AddedVia.fromWire(v.wire))
        assertEquals("dairy_eggs", Category.DAIRY_EGGS.wire)
        assertEquals("notification_reply", AddedVia.NOTIFICATION_REPLY.wire)
    }

    @Test
    fun enumSizesMatchContract() {
        assertEquals(4, Role.entries.size)
        assertEquals(5, ItemStatus.entries.size)
        assertEquals(13, AddedVia.entries.size)
        assertEquals(12, ItemUnit.entries.size)
        assertEquals(17, Category.entries.size)
        assertEquals(9, EventType.entries.size)
    }

    @Test
    fun widgetSnapshotDecodesContractExample() {
        val json = """
            {
              "v": 1, "updatedAt": 1760780000, "viewerLang": "ar", "householdId": "h_1",
              "lists": [ { "id": "l_1", "name": "البقالة", "openCount": 9, "items": [
                { "id": "i_1", "title": "حليب", "subtitle": "2 L", "category": "dairy_eggs", "status": "open", "urgent": false }
              ] } ],
              "activeTrip": { "id": "t_123", "storeName": "Migros", "remaining": 7, "done": 5, "lastCallUntil": 1760780600 }
            }
        """.trimIndent()
        val snapshot = ContractJson.decodeFromString(WidgetSnapshot.serializer(), json)
        assertEquals(1760780000L, snapshot.updatedAt)
        assertEquals("حليب", snapshot.lists.single().items.single().title)
        assertEquals(1760780600L, snapshot.activeTrip?.lastCallUntil)
        val again = ContractJson.decodeFromString(
            WidgetSnapshot.serializer(),
            ContractJson.encodeToString(WidgetSnapshot.serializer(), snapshot),
        )
        assertEquals(snapshot, again)
    }

    @Test
    fun parsedItemToleratesUnknownFieldsAndValues() {
        val json = """{ "name": "بندورة", "canonicalId": "tomato", "qty": 2, "unit": "kg", "category": "produce",
            "names": { "tr": "Domates" }, "confidence": 0.93, "futureField": true }"""
        val item = ContractJson.decodeFromString(ParsedItem.serializer(), json)
        assertEquals(ItemUnit.KG, item.unitEnum)
        assertEquals(Category.PRODUCE, item.categoryEnum)
        val odd = ContractJson.decodeFromString(ParsedItem.serializer(), """{ "name": "x", "unit": "cup", "category": "moon" }""")
        assertNull(odd.unitEnum)
        assertEquals(Category.OTHER, odd.categoryEnum)
    }
}
