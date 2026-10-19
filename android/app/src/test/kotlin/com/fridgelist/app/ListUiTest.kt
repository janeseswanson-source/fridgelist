package com.fridgelist.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fridgelist.app.feature.lists.ListDetailScreen
import com.fridgelist.app.ui.theme.FridgeListTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

/** What TalkBack and a tap see on the list screen. */
@RunWith(RobolectricTestRunner::class)
class ListUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun addButtonIsLabelledAndClickable() {
        var added = false
        compose.setContent {
            FridgeListTheme { ListDetailScreen(Samples.detail, Samples.adultActions, remember { SnackbarHostState() }, { added = true }, {}) }
        }
        compose.onNode(hasContentDescription("Add")).assertIsDisplayed().assertHasClickAction().performClick()
        assertTrue(added)
    }

    @Test
    fun rowCheckboxesCarryTheItemTitle() {
        compose.setContent {
            FridgeListTheme { ListDetailScreen(Samples.detail, Samples.adultActions, remember { SnackbarHostState() }, {}, {}) }
        }
        // Display rule title for an Arabic viewer (Samples use viewerLang = ar).
        compose.onNode(hasContentDescription("طماطم") and isToggleable()).assertIsOff()
    }

    @Test
    fun sectionsAreLabelled() {
        compose.setContent {
            FridgeListTheme { ListDetailScreen(Samples.detail, Samples.adultActions, remember { SnackbarHostState() }, {}, {}) }
        }
        compose.onNodeWithText("Requests").assertIsDisplayed()
        compose.onNodeWithText("Fruit & vegetables").assertIsDisplayed()
        compose.onNodeWithText("Approve").assertHasClickAction()
    }

    @Test
    fun kidsGetNoShoppingOrImGoing() {
        val res = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().resources
        compose.setContent {
            FridgeListTheme {
                ListDetailScreen(Samples.detail.copy(role = com.fridgelist.core.model.Role.KID), Samples.adultActions, remember { SnackbarHostState() }, {}, {})
            }
        }
        compose.onNodeWithText(res.getString(R.string.going_title)).assertDoesNotExist()
        compose.onNode(hasContentDescription(res.getString(R.string.shop_start))).assertDoesNotExist()
    }

    @Test
    fun adultsGetBoth() {
        val res = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().resources
        compose.setContent {
            FridgeListTheme { ListDetailScreen(Samples.detail.copy(role = com.fridgelist.core.model.Role.ADULT), Samples.adultActions, remember { SnackbarHostState() }, {}, {}) }
        }
        compose.onNodeWithText(res.getString(R.string.going_title)).assertIsDisplayed()
        compose.onNode(hasContentDescription(res.getString(R.string.shop_start))).assertIsDisplayed()
    }
}
