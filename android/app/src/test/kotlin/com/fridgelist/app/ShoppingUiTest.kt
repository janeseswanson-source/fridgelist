package com.fridgelist.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.fridgelist.app.fakes.FakeItems
import com.fridgelist.app.fakes.FakeSession
import com.fridgelist.app.fakes.FakeTripEnder
import com.fridgelist.app.fakes.FakeTripNotifier
import com.fridgelist.app.fakes.FakeTrips
import com.fridgelist.app.fakes.householdContext
import com.fridgelist.app.feature.shopping.ShoppingRoute
import com.fridgelist.app.feature.shopping.ShoppingViewModel
import com.fridgelist.app.ui.theme.FridgeListTheme
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.fridgelist.core.data.session.Session
import com.fridgelist.core.model.ItemDoc
import com.fridgelist.core.model.ListDoc
import com.fridgelist.core.model.ListType
import com.fridgelist.core.model.Role
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prompt A5 "done when": UI tests cover tick, undo, missing and finish, including offline. */
@RunWith(RobolectricTestRunner::class)
class ShoppingUiTest {
    @get:Rule val compose = createComposeRule()

    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private fun s(id: Int) = res.getString(id)

    private val items = FakeItems().apply {
        lists.value = listOf(ListDoc("l1", "Market", ListType.GROCERY))
        setItems(
            ItemDoc(id = "milk", name = "süt", addedBy = "mom", createdAt = 1),
            ItemDoc(id = "bread", name = "ekmek", addedBy = "mom", createdAt = 2),
        )
    }
    private val ender = FakeTripEnder()
    private val notifier = FakeTripNotifier()

    private fun show(trips: FakeTrips): ShoppingViewModel {
        val session = FakeSession(Session("Ayşe", "tr", "h1", true))
        val vm = ShoppingViewModel(SavedStateHandle(mapOf("listId" to "l1")), householdContext(Role.HELPER, session), items, items, trips, session, ender, notifier)
        compose.setContent { FridgeListTheme { ShoppingRoute(onClose = {}, vm = vm) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("süt").fetchSemanticsNodes().isNotEmpty() }
        return vm
    }

    private fun row(title: String) = compose.onNode(isToggleable() and hasText(title))

    @Test
    fun tickUndoMissingAndFinish() {
        show(FakeTrips())

        // Tick: into the cart, Undo snackbar offered.
        row("süt").assertIsOff().performClick()
        row("süt").assertIsOn()
        compose.onNodeWithText(s(R.string.shop_in_cart)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.action_undo)).performClick()
        compose.waitUntil(5_000) { items.listItems.value.items.first { it.id == "milk" }.status.wire == "open" }
        row("süt").assertIsOff()

        // Couldn't find: bread's button (bakery comes first in aisle order).
        compose.onAllNodesWithText(s(R.string.action_missing)).onFirst().performClick()
        compose.waitUntil(5_000) { items.listItems.value.items.first { it.id == "bread" }.status.wire == "missing" }

        // Tick milk again and finish.
        row("süt").performClick()
        compose.onNodeWithText(s(R.string.shop_done)).performClick()
        compose.onNodeWithText(s(R.string.summary_title)).assertIsDisplayed()
        compose.onNodeWithText(res.getQuantityString(R.plurals.summary_bought, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText(res.getQuantityString(R.plurals.summary_missing, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.summary_send_rest)).assertIsDisplayed()
        assertEquals(listOf("h1" to "t1"), ender.ended)
    }

    @Test
    fun offlineShoppingStillTicksAndFinishes() {
        show(FakeTrips(failWith = ApiException(ApiError.NETWORK)))
        compose.waitUntil(10_000) { compose.onAllNodesWithText(s(R.string.shop_local_mode)).fetchSemanticsNodes().isNotEmpty() }

        row("ekmek").performClick()
        row("ekmek").assertIsOn()
        compose.onNodeWithText(s(R.string.shop_done)).performClick()
        compose.onNodeWithText(res.getQuantityString(R.plurals.summary_bought, 1, 1)).assertIsDisplayed()
        assertTrue(ender.ended.isEmpty(), "no trip to end offline")
    }
}
