package com.fridgelist.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.fridgelist.app.feature.home.HomeScreen
import com.fridgelist.app.feature.lists.AddItemsContent
import com.fridgelist.app.feature.lists.ListDetailScreen
import com.fridgelist.app.feature.planner.PlannedItem
import com.fridgelist.app.feature.planner.PlannerScreen
import com.fridgelist.app.feature.planner.PlannerStep
import com.fridgelist.app.feature.planner.PlannerUiState
import com.fridgelist.app.feature.settings.AppLanguage
import com.fridgelist.app.feature.settings.NumberStyle
import com.fridgelist.app.feature.settings.SettingsScreen
import com.fridgelist.app.feature.shopping.ShoppingScreen
import com.fridgelist.app.ui.theme.FridgeListTheme
import com.fridgelist.core.model.ParsedItem
import com.fridgelist.core.model.SkippedIngredient
import com.fridgelist.core.model.SuggestedMeal
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

internal object PlannerSamples {
    val meals = listOf(
        SuggestedMeal("m1", "شوربة عدس", "يكفي 6 أشخاص", 6, "levantine"),
        SuggestedMeal("m2", "كبسة دجاج", "يكفي 6 أشخاص", 6, "gulf"),
        SuggestedMeal("m3", "Ramazan pidesi", "يكفي 6 أشخاص", 6, "turkish"),
        SuggestedMeal("m4", "قطايف", "يكفي 6 أشخاص", 6, "levantine"),
    )
    val pick = PlannerUiState(step = PlannerStep.PICK, people = 6, days = 3, meals = meals, selected = setOf("m1", "m2"))
    val confirm = PlannerUiState(
        step = PlannerStep.CONFIRM,
        people = 6,
        listName = "البقالة",
        items = listOf(
            PlannedItem(ParsedItem("عدس أحمر", "lentils_red", 0.5, "kg")),
            PlannedItem(ParsedItem("دجاج", "chicken", 1.5, "kg")),
            PlannedItem(ParsedItem("أرز", "rice", 1.0, "kg"), keep = false),
            PlannedItem(ParsedItem("كمون", qty = 1.0, unit = "pack")),
        ),
        skipped = listOf(SkippedIngredient("ليمون", "on_list"), SkippedIngredient("زيت زيتون", "recently_bought")),
    )
}

/** A10 planner screens in ar (reviewed first), tr, en × light/dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class A10ScreenshotTest(private val config: String, private val qualifiers: String) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<String>> = ScreenshotTest.configs()
    }

    @Before
    fun setUp() = RuntimeEnvironment.setQualifiers(qualifiers)

    private fun capture(screen: String, content: @Composable () -> Unit) =
        captureRoboImage("src/test/screenshots/${screen}_$config.png") { FridgeListTheme { content() } }

    private fun planner(state: PlannerUiState) = capture("planner_${state.step.name.lowercase()}") {
        PlannerScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun planner_setup() = planner(PlannerUiState(people = 6, days = 3))

    @Test fun planner_pick() = planner(PlannerSamples.pick)

    @Test fun planner_confirm() = planner(PlannerSamples.confirm)

    @Test fun planner_done() = planner(PlannerUiState(step = PlannerStep.DONE, added = 9, listName = "البقالة"))

    @Test fun home_with_planner() = capture("home_with_planner") { HomeScreen(Samples.lists, {}, {}, {}, {}, onPlanIftar = {}) }
}

/**
 * Font scale 200% (A10), Arabic: every main screen must still fit and read. Long words wrap
 * instead of being cut; nothing overlaps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FontScaleScreenshotTest {

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers("ar-ldrtl-w411dp-h891dp-notnight-xxhdpi")
        RuntimeEnvironment.setFontScale(2f)
    }

    private fun capture(screen: String, content: @Composable () -> Unit) =
        captureRoboImage("src/test/screenshots/fontscale200_${screen}_ar.png") { FridgeListTheme { content() } }

    @Test fun home() = capture("home") { HomeScreen(Samples.lists, {}, {}, {}, {}, onPlanIftar = {}) }

    @Test fun list_detail() = capture("list_detail") {
        ListDetailScreen(Samples.detail, Samples.adultActions, remember { SnackbarHostState() }, {}, {})
    }

    @Test fun add_items() = capture("add_items") { AddItemsContent(Samples.add, {}, { _, _ -> }, {}, {}) }

    @Test fun shopping() = capture("shopping") {
        ShoppingScreen(Samples.shopping, Samples.shoppingActions, remember { SnackbarHostState() }, {}, {})
    }

    @Test fun settings() = capture("settings") {
        SettingsScreen(AppLanguage.ARABIC, {}, null, {}, {}, {}, onSignOut = {}, numbers = NumberStyle.ARABIC_INDIC)
    }

    @Test fun planner_confirm() = capture("planner_confirm") { PlannerScreen(PlannerSamples.confirm, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
}
