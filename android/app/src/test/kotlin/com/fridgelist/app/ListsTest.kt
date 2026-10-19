package com.fridgelist.app

import androidx.lifecycle.SavedStateHandle
import com.fridgelist.app.fakes.FakeAuth
import com.fridgelist.app.fakes.FakeHouseholds
import com.fridgelist.app.fakes.FakeItems
import com.fridgelist.app.fakes.FakeSession
import com.fridgelist.app.fakes.MainDispatcherRule
import com.fridgelist.app.feature.lists.AddItemsViewModel
import com.fridgelist.app.feature.lists.HouseholdContext
import com.fridgelist.app.feature.lists.HouseholdContextSource
import com.fridgelist.app.feature.lists.ListDetailViewModel
import com.fridgelist.app.feature.lists.ListSectionsBuilder
import com.fridgelist.app.feature.lists.ListsViewModel
import com.fridgelist.core.data.auth.AuthUser
import com.fridgelist.core.data.firestore.FieldPatch
import com.fridgelist.core.data.items.ListItems
import com.fridgelist.core.data.session.Session
import com.fridgelist.core.i18n.AliasId
import com.fridgelist.core.model.Category
import com.fridgelist.core.model.HouseholdDoc
import com.fridgelist.core.model.ItemDoc
import com.fridgelist.core.model.ItemStatus
import com.fridgelist.core.model.ItemUnit
import com.fridgelist.core.model.ListType
import com.fridgelist.core.model.MemberDoc
import com.fridgelist.core.model.ParseItemsResponse
import com.fridgelist.core.model.ParsedItem
import com.fridgelist.core.model.Role
import com.fridgelist.core.model.TranscribeAudioResponse
import com.fridgelist.app.voice.SpeechEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ListsTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    private val ctx = HouseholdContext("h1", "me", Role.ADULT, viewerLang = "tr", defaultLanguage = "ar", memberNames = mapOf("me" to "Ayşe", "mom" to "Mama"))

    private fun item(id: String, name: String, status: ItemStatus = ItemStatus.OPEN, by: String = "mom", vararg extra: Pair<String, Any?>) =
        ItemDoc(id = id, name = name, status = status, addedBy = by, createdAt = id.hashCode().toLong()).let { base ->
            extra.fold(base) { acc, (k, v) ->
                when (k) {
                    "category" -> acc.copy(category = v as Category)
                    "names" -> @Suppress("UNCHECKED_CAST") acc.copy(names = v as Map<String, String>, lang = "ar")
                    "claimedBy" -> acc.copy(claimedBy = v as String)
                    "urgent" -> acc.copy(urgent = true)
                    "doneAt" -> acc.copy(doneAt = v as Long)
                    else -> acc
                }
            }
        }

    // --- Sections (pure)

    @Test
    fun sectionsFollowContractCategoryOrderAndDisplayRule() {
        val sections = ListSectionsBuilder.build(
            ListItems(
                listOf(
                    item("1", "حليب", extra = arrayOf("category" to Category.DAIRY_EGGS)),
                    item("2", "بندورة", extra = arrayOf("names" to mapOf("tr" to "Domates"))), // no category yet → local guess: produce
                    item("3", "عصير", ItemStatus.PENDING, by = "kid"),
                    item("4", "خبز", ItemStatus.DONE, extra = arrayOf("doneAt" to 5L)),
                    item("5", "زبادي", ItemStatus.MISSING),
                    item("6", "guest thing", by = "guest:tok123"),
                    item("7", "لبنة", extra = arrayOf("urgent" to true, "category" to Category.DAIRY_EGGS)),
                ),
                pendingIds = setOf("1"),
            ),
            ctx,
        )
        assertEquals(listOf(Category.PRODUCE, Category.DAIRY_EGGS, Category.OTHER), sections.groups.map { it.first })
        val tomato = sections.groups.first().second.single()
        assertEquals("Domates", tomato.title)
        assertEquals("بندورة", tomato.original)
        assertEquals(listOf("7", "1"), sections.groups[1].second.map { it.item.id }, "urgent first")
        assertTrue(sections.groups[1].second.last().pendingSync)
        assertEquals(listOf("3"), sections.requests.map { it.item.id })
        assertEquals(listOf("5"), sections.missing.map { it.item.id })
        assertEquals(listOf("4"), sections.done.map { it.item.id })
        assertTrue(sections.groups.last().second.single().askedBy.single().guest)
        assertEquals("Mama", tomato.askedBy.single().name)
    }

    @Test
    fun hiddenItemsDisappear() {
        val sections = ListSectionsBuilder.build(ListItems(listOf(item("1", "milk"))), ctx, hidden = setOf("1"))
        assertTrue(sections.isEmpty)
    }

    // --- List detail

    private fun contextSource(role: Role, items: FakeItems): HouseholdContextSource {
        val households = FakeHouseholds().apply {
            members.value = mapOf("h1" to listOf(MemberDoc("me", role, "Ayşe", "tr"), MemberDoc("mom", Role.OWNER, "Mama", "ar")))
            this.households.value = mapOf("h1" to HouseholdDoc("h1", "Ev", "mom", "ar"))
        }
        return HouseholdContextSource(FakeSession(Session("Ayşe", "tr", "h1", true)), FakeAuth(AuthUser("me", "Ayşe")), households)
    }

    private fun TestScope.detail(role: Role, items: FakeItems): ListDetailViewModel {
        val vm = ListDetailViewModel(SavedStateHandle(mapOf("listId" to "l1")), contextSource(role, items), items, items)
        vm.state.launchIn(backgroundScope)
        runCurrent()
        return vm
    }

    @Test
    fun doneWithUndoGoesBackToOpen() = runTest(main.dispatcher) {
        val items = FakeItems().apply { setItems(item("1", "milk", ItemStatus.CLAIMED, extra = arrayOf("claimedBy" to "me"))) }
        val vm = detail(Role.HELPER, items)
        val row = vm.state.value.sections.groups.single().second.single()

        vm.toggleDone(row)
        assertEquals("done" , (items.updates.last().second["status"] as FieldPatch.Set).value)
        assertEquals(R.string.undo_done, vm.undo.value?.message)

        vm.settle(undone = true)
        // No done → claimed in the table: Undo returns the item to open.
        assertEquals("open", (items.updates.last().second["status"] as FieldPatch.Set).value)
        assertNull(vm.undo.value)
    }

    @Test
    fun helperCannotReleaseSomeoneElsesClaim() = runTest(main.dispatcher) {
        val items = FakeItems().apply { setItems(item("1", "milk", ItemStatus.CLAIMED, extra = arrayOf("claimedBy" to "mom"))) }
        val vm = detail(Role.HELPER, items)
        val row = vm.state.value.sections.groups.single().second.single()
        assertFalse(vm.can(row, ItemStatus.OPEN))
        vm.unclaim(row)
        assertTrue(items.updates.isEmpty())
        assertEquals("Mama", row.claimedByOther)
    }

    @Test
    fun deleteWaitsForTheSnackbarAndCanBeUndone() = runTest(main.dispatcher) {
        val items = FakeItems().apply { setItems(item("1", "milk"), item("2", "bread")) }
        val vm = detail(Role.ADULT, items)
        val milk = vm.state.value.sections.groups.flatMap { it.second }.first { it.item.id == "1" }

        vm.delete(milk)
        runCurrent()
        assertTrue(items.deleted.isEmpty(), "not written yet")
        assertFalse(vm.state.value.sections.groups.flatMap { it.second }.any { it.item.id == "1" }, "hidden at once")

        vm.settle(undone = true)
        runCurrent()
        assertTrue(items.deleted.isEmpty())
        assertTrue(vm.state.value.sections.groups.flatMap { it.second }.any { it.item.id == "1" })

        vm.delete(milk)
        vm.settle(undone = false)
        assertEquals(listOf("1"), items.deleted)
    }

    @Test
    fun onlyAdultsApproveKidRequests() = runTest(main.dispatcher) {
        val items = FakeItems().apply { setItems(item("1", "candy", ItemStatus.PENDING, by = "kid")) }
        val helper = detail(Role.HELPER, items)
        val request = helper.state.value.sections.requests.single()
        helper.approve(request)
        assertTrue(items.updates.isEmpty())
        assertFalse(helper.canDelete(request))

        val adult = detail(Role.ADULT, items)
        adult.approve(adult.state.value.sections.requests.single())
        assertEquals("open", (items.updates.single().second["status"] as FieldPatch.Set).value)
    }

    // --- Add sheet

    private val engine = FakeEngine(onDevice = true)

    private fun TestScope.adder(role: Role, items: FakeItems): AddItemsViewModel {
        val vm = AddItemsViewModel(
            SavedStateHandle(mapOf("listId" to "l1")), contextSource(role, items), items, items, items,
            speechEngines = { listOf(engine) }, recorder = FakeRecorder(), transcriber = FakeTranscriber { TranscribeAudioResponse("") },
        )
        runCurrent()
        return vm
    }

    @Test
    fun localChipsAppearAtOnceAndOfflineStillAdds() = runTest(main.dispatcher) {
        val items = FakeItems() // parse throws NETWORK
        val vm = adder(Role.ADULT, items)
        vm.onTextChange("2 süt ve labne")
        assertEquals(listOf("süt", "labne"), vm.state.value.chips.map { it.name })
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("süt", "labne"), vm.state.value.chips.map { it.name }, "kept after the server failed")

        vm.confirm()
        assertEquals(2, items.added.size)
        assertEquals(FieldPatch.Set("app_text"), items.added[0]["addedVia"])
        assertEquals(FieldPatch.Set(2.0), items.added[0]["qty"])
        assertTrue(vm.state.value.added)
    }

    @Test
    fun serverRefinementReplacesChips() = runTest(main.dispatcher) {
        val items = FakeItems().apply {
            parseResponse = ParseItemsResponse(listOf(ParsedItem("بندورة", "tomato", 2.0, "kg", confidence = 0.95)))
        }
        val vm = adder(Role.ADULT, items)
        vm.onTextChange("جيب ٢ كيلو بندورة")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("جيب ٢ كيلو بندورة", items.parseRequests.single().text)
        val chip = vm.state.value.chips.single()
        assertEquals(ItemUnit.KG, chip.unit)
        assertEquals("tomato", chip.canonicalId)
    }

    @Test
    fun ambiguousWordAsksOnceAndSavesAlias() = runTest(main.dispatcher) {
        val items = FakeItems()
        val vm = adder(Role.ADULT, items)
        vm.onTextChange("عيش")
        val chip = vm.state.value.chips.single()
        assertEquals(listOf("bread", "rice"), chip.choices.map { it.canonicalId })
        assertEquals("Ekmek", chip.choices.first().label, "labels in the viewer's item language")

        vm.choose(chip, chip.choices.last())
        assertEquals(listOf(AliasId.of("عيش"), "عيش", "rice", "me"), items.savedAliases.single())
        assertTrue(vm.state.value.chips.single().choices.isEmpty())
    }

    @Test
    fun kidsAddRequests() = runTest(main.dispatcher) {
        val items = FakeItems()
        val vm = adder(Role.KID, items)
        assertTrue(vm.state.value.asRequest)
        vm.onTextChange("chips")
        vm.confirm()
        assertEquals(FieldPatch.Set("pending"), items.added.single()["status"])
    }

    @Test
    fun voiceTranscriptBecomesChipsAndItemsAreMarkedVoice() = runTest(main.dispatcher) {
        val items = FakeItems()
        val vm = adder(Role.ADULT, items)
        vm.startVoice()
        runCurrent()
        assertEquals(listOf("tr-TR"), engine.languages, "member's item language tr, region default")
        engine.say(SpeechEvent.Partial("yarım kilo"), SpeechEvent.Final("yarım kilo beyaz peynir ve süt"))
        runCurrent()
        assertEquals(listOf("beyaz peynir", "süt"), vm.state.value.chips.map { it.name })
        assertEquals(0.5, vm.state.value.chips.first().qty)
        vm.confirm()
        assertTrue(items.added.all { it["addedVia"] == FieldPatch.Set("app_voice") })
    }

    @Test
    fun voiceAppendsToTypedTextWithTheRightComma() = runTest(main.dispatcher) {
        val vm = adder(Role.ADULT, FakeItems())
        vm.onTextChange("خبز")
        vm.onTranscript("حليب وبيض")
        assertEquals("خبز، حليب وبيض", vm.state.value.text)
        assertEquals(3, vm.state.value.chips.size)
    }

    @Test
    fun typedOnlyStaysAppText() = runTest(main.dispatcher) {
        val items = FakeItems()
        val vm = adder(Role.ADULT, items)
        vm.onTextChange("milk")
        vm.confirm()
        assertEquals(FieldPatch.Set("app_text"), items.added.single()["addedVia"])
    }

    // --- Lists

    @Test
    fun listsWithCountsAndCreate() = runTest(main.dispatcher) {
        val items = FakeItems().apply {
            lists.value = listOf(com.fridgelist.core.model.ListDoc("l1", "البقالة", ListType.GROCERY, order = 0.0))
            setItems(item("1", "milk"), item("2", "bread", ItemStatus.DONE))
        }
        val vm = ListsViewModel(contextSource(Role.ADULT, items), FakeHouseholds().apply {
            households.value = mapOf("h1" to HouseholdDoc("h1", "Ev", "mom", "ar"))
        }, items, FakeSession(Session("Ayşe", "tr", "h1", true, com.fridgelist.core.data.session.ActiveShopping("l1"))))
        vm.state.launchIn(backgroundScope)
        runCurrent()
        assertEquals(1, vm.state.value.lists.single().openCount)
        assertEquals("l1", vm.state.value.shoppingList?.id, "Home offers to continue shopping")
        assertTrue(vm.state.value.canCreate)
        assertEquals("l-new", vm.createList("  Eczane ", ListType.PHARMACY, "💊🧴"))
        assertEquals(Triple("Eczane", ListType.PHARMACY, "💊"), items.createdLists.single())
    }
}
