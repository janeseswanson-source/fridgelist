package com.fridgelist.app

import androidx.test.core.app.ApplicationProvider
import com.fridgelist.app.fakes.FakeFlags
import com.fridgelist.app.fakes.FakeItems
import com.fridgelist.app.fakes.MainDispatcherRule
import com.fridgelist.app.fakes.householdContext
import com.fridgelist.app.feature.planner.PlannerAvailability
import com.fridgelist.app.feature.planner.PlannerStep
import com.fridgelist.app.feature.planner.PlannerViewModel
import com.fridgelist.app.feature.planner.RamadanWindow
import com.fridgelist.app.feature.settings.NumberStyle
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.firestore.FieldPatch
import com.fridgelist.core.data.meals.MealRepository
import com.fridgelist.core.data.settings.DevSettings
import com.fridgelist.core.model.Flags
import com.fridgelist.core.model.ListDoc
import com.fridgelist.core.model.ListType
import com.fridgelist.core.model.MealToItemsRequest
import com.fridgelist.core.model.MealToItemsResponse
import com.fridgelist.core.model.ParsedItem
import com.fridgelist.core.model.Role
import com.fridgelist.core.model.SkippedIngredient
import com.fridgelist.core.model.SuggestMealsRequest
import com.fridgelist.core.model.SuggestMealsResponse
import com.fridgelist.core.model.SuggestedMeal
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PlannerTest {

    @get:Rule val main = MainDispatcherRule()

    private fun day(iso: String) = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("Asia/Riyadh") }.parse(iso)!!

    @Test
    fun windowIsShabanAndRamadanUmmAlQura() {
        // 1448 AH (Umm al-Qura): 1 Sha'ban ≈ 10 Jan 2027, 1 Ramadan ≈ 8 Feb 2027, 1 Shawwal ≈ 10 Mar 2027.
        assertFalse(RamadanWindow.isOpen(day("2026-10-19")), "Rabi' al-Thani")
        assertFalse(RamadanWindow.isOpen(day("2027-01-05")), "late Rajab")
        assertTrue(RamadanWindow.isOpen(day("2027-01-15")), "Sha'ban")
        assertTrue(RamadanWindow.isOpen(day("2027-02-20")), "Ramadan")
        assertTrue(RamadanWindow.isOpen(day("2027-03-07")), "last days of Ramadan")
        assertFalse(RamadanWindow.isOpen(day("2027-03-15")), "Shawwal (Eid)")
    }

    @Test
    fun numbersStyleIsTheLocaleNumberingExtension() {
        assertEquals("ar-u-nu-arab", NumberStyle.tag("ar", NumberStyle.ARABIC_INDIC, Locale.ENGLISH))
        assertEquals("ar-u-nu-latn", NumberStyle.tag("ar", NumberStyle.WESTERN, Locale.ENGLISH))
        assertEquals("ar-SA-u-nu-latn", NumberStyle.tag(null, NumberStyle.WESTERN, Locale.forLanguageTag("ar-SA")), "system language, numbers set")
        assertEquals("tr", NumberStyle.tag("tr", NumberStyle.SYSTEM, Locale.ENGLISH))
        assertNull(NumberStyle.tag(null, NumberStyle.SYSTEM, Locale.ENGLISH), "both from the system")
    }

    @Test
    fun plannerShowsWithTheFlagInTheWindowOrWhenForced() = runTest {
        val dev = DevSettings(ApplicationProvider.getApplicationContext())
        dev.setForcePlanner(false)
        fun availability(flag: Boolean, date: String) =
            PlannerAvailability(FakeFlags(mapOf(Flags.RAMADAN_PLANNER_ENABLED to flag)), dev).apply { now = { day(date) } }
        assertTrue(availability(true, "2027-02-20").available())
        assertFalse(availability(false, "2027-02-20").available(), "flag off (the default)")
        assertFalse(availability(true, "2026-10-19").available(), "outside Sha'ban and Ramadan")
        dev.setForcePlanner(true)
        assertTrue(availability(false, "2026-10-19").available(), "dev override")
        dev.setForcePlanner(false)
    }

    private class FakeMeals(var fail: ApiError? = null) : MealRepository {
        val suggests = mutableListOf<SuggestMealsRequest>()
        val toItems = mutableListOf<MealToItemsRequest>()
        override suspend fun suggest(request: SuggestMealsRequest): SuggestMealsResponse {
            fail?.let { throw ApiException(it, "x") }
            suggests += request
            val n = suggests.size
            return SuggestMealsResponse(
                listOf(
                    SuggestedMeal("m${n}a", "شوربة عدس $n", "يكفي 6", 6, "levantine"),
                    SuggestedMeal("m${n}b", "كبسة $n", "يكفي 6", 6, "gulf"),
                ),
            )
        }
        override suspend fun toItems(request: MealToItemsRequest): MealToItemsResponse {
            fail?.let { throw ApiException(it, "x") }
            toItems += request
            return MealToItemsResponse(
                items = listOf(ParsedItem("عدس أحمر", "lentils_red", 0.45, "kg"), ParsedItem("أرز", "rice", 1.0, "kg"), ParsedItem("كمون", qty = 1.0, unit = "pack")),
                skipped = listOf(SkippedIngredient("ليمون", "on_list")),
            )
        }
    }

    private fun planner(meals: FakeMeals, items: FakeItems = FakeItems(), role: Role = Role.ADULT): PlannerViewModel {
        items.lists.value = listOf(ListDoc("ph", "Eczane", ListType.PHARMACY), ListDoc("gr", "البقالة", ListType.GROCERY))
        return PlannerViewModel(householdContext(role), items, items, meals) { "ar" }
    }

    @Test
    fun sixPeopleThreeDaysToIdeasToTheGroceryList() = runTest(main.dispatcher) {
        val meals = FakeMeals()
        val items = FakeItems()
        val vm = planner(meals, items)
        vm.onPeople(40)
        assertEquals(30, vm.state.value.people, "clamped to 2–30")
        vm.onPeople(6)
        vm.onDays(0)
        assertEquals(1, vm.state.value.days, "clamped to 1–7")
        vm.onDays(3)

        vm.suggest()
        val request = meals.suggests.single()
        assertEquals(SuggestMealsRequest("h1", 6, "iftar", days = 3, lang = "ar"), request)
        assertEquals(PlannerStep.PICK, vm.state.value.step)
        assertEquals(2, vm.state.value.meals.size)

        vm.suggest(more = true)
        assertEquals(listOf("شوربة عدس 1", "كبسة 1"), meals.suggests[1].exclude, "more ideas never repeat")
        assertEquals(4, vm.state.value.meals.size)

        vm.toggleMeal("m1a")
        vm.toggleMeal("m2b")
        vm.toggleMeal("m1a")
        vm.toggleMeal("m1a")
        vm.makeList()
        // The grocery list, the picked meals in display order, the viewer's item language (tr for Ayşe).
        assertEquals(MealToItemsRequest("h1", "gr", listOf("m1a", "m2b"), 6, "tr"), meals.toItems.single())
        assertEquals(PlannerStep.CONFIRM, vm.state.value.step)
        assertEquals("البقالة", vm.state.value.listName)
        assertEquals(1, vm.state.value.skipped.size)

        vm.toggleItem(1) // no rice, we have plenty
        vm.add()
        assertEquals(PlannerStep.DONE, vm.state.value.step)
        assertEquals(2, vm.state.value.added)
        assertEquals(listOf("عدس أحمر", "كمون"), items.added.map { (it["name"] as FieldPatch.Set).value })
        assertTrue(items.added.all { it["addedVia"] == FieldPatch.Set("meal_plan") && it["status"] == FieldPatch.Set("open") })
        assertEquals(FieldPatch.Set(0.45), items.added[0]["qty"])
    }

    @Test
    fun kidsPlansBecomeRequests() = runTest(main.dispatcher) {
        val items = FakeItems()
        val vm = planner(FakeMeals(), items, Role.KID)
        vm.suggest()
        vm.toggleMeal("m1a")
        vm.makeList()
        vm.add()
        assertTrue(items.added.all { it["status"] == FieldPatch.Set("pending") })
    }

    @Test
    fun errorsSayWhatHappened() = runTest(main.dispatcher) {
        val meals = FakeMeals(ApiError.NETWORK)
        val vm = planner(meals)
        vm.suggest()
        assertEquals(R.string.planner_offline, vm.state.value.message)
        assertEquals(PlannerStep.SETUP, vm.state.value.step)
        meals.fail = ApiError.UNAVAILABLE
        vm.suggest()
        assertEquals(R.string.planner_unavailable, vm.state.value.message)
        meals.fail = ApiError.RESOURCE_EXHAUSTED
        vm.suggest()
        assertEquals(R.string.planner_too_many, vm.state.value.message)
        meals.fail = null
        vm.suggest()
        assertNull(vm.state.value.message)
        vm.back()
        assertEquals(PlannerStep.SETUP, vm.state.value.step)
    }
}
