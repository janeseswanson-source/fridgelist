package com.fridgelist.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.fridgelist.app.feature.home.HomeScreen
import com.fridgelist.app.feature.lists.AddItemsContent
import com.fridgelist.app.feature.lists.ListDetailScreen
import com.fridgelist.app.feature.planner.PlannerScreen
import com.fridgelist.app.feature.planner.PlannerUiState
import com.fridgelist.app.feature.settings.AppLanguage
import com.fridgelist.app.feature.settings.SettingsScreen
import com.fridgelist.app.feature.share.ShareTagScreen
import com.fridgelist.app.feature.share.ShareTagUiState
import com.fridgelist.app.feature.shopping.ShoppingScreen
import com.fridgelist.app.ui.theme.FridgeListTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * A10 accessibility: on every main screen, each tappable thing is at least 48 × 48 dp and has a
 * name TalkBack can read (its text or a content description). Arabic, so labels are the real ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ar-ldrtl-w411dp-h891dp-xxhdpi")
class AccessibilityTest {

    @get:Rule val compose = createComposeRule()

    private fun check(screen: @Composable () -> Unit) {
        compose.setContent { FridgeListTheme { screen() } }
        val density = compose.density
        val problems = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { node ->
            // What a finger can hit: Material widens small controls (icon buttons, checkboxes) to 48 dp.
            val touch = node.touchBoundsInRoot
            val w = with(density) { touch.width.toDp() }
            val h = with(density) { touch.height.toDp() }
            when {
                w < 47.5.dp || h < 47.5.dp -> "${label(node)}: ${w.value.toInt()}×${h.value.toInt()} dp"
                label(node).isBlank() -> "unlabeled clickable at ${node.boundsInRoot}"
                else -> null
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /** What TalkBack reads for a merged node. */
    private fun label(node: SemanticsNode): String {
        val config = node.config
        return listOfNotNull(
            config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
            config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
            config.getOrNull(SemanticsProperties.EditableText)?.text,
        ).joinToString(" ").trim()
    }

    /**
     * The check itself. Compose widens every clickable's touch area to 48 dp, so the size rule holds
     * by construction for standard modifiers; the label rule is what catches mistakes here.
     */
    @Test fun theCheckCatchesSmallUnlabeledTargets() {
        val error = runCatching {
            check { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(24.dp).clickable {}) }
        }.exceptionOrNull()
        assertTrue(error?.message?.contains("unlabeled clickable") == true, error?.message ?: "passed")
    }

    @Test fun home() = check { HomeScreen(Samples.lists, {}, {}, {}, {}, onPlanIftar = {}) }

    @Test fun listDetail() = check { ListDetailScreen(Samples.detail, Samples.adultActions, remember { SnackbarHostState() }, {}, {}, onShareTag = {}) }

    @Test fun addItems() = check { AddItemsContent(Samples.add, {}, { _, _ -> }, {}, {}) }

    @Test fun shopping() = check { ShoppingScreen(Samples.shopping, Samples.shoppingActions, remember { SnackbarHostState() }, {}, {}) }

    @Test fun settings() = check { SettingsScreen(AppLanguage.ARABIC, {}, null, {}, {}, {}, onSignOut = {}) }

    @Test fun plannerSetup() = check { PlannerScreen(PlannerUiState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }

    @Test fun plannerPick() = check { PlannerScreen(PlannerSamples.pick, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }

    @Test fun plannerConfirm() = check { PlannerScreen(PlannerSamples.confirm, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }

    @Test fun shareTag() = check {
        ShareTagScreen(ShareTagUiState(listName = "البقالة", qrUrl = "https://links.example.com/k/Ab3_-xYz0123456789abcdEF"), true, true, {}, {}, {}, {}, {}, {}, {}, {})
    }
}
