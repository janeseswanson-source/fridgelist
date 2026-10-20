package com.fridgelist.app

import androidx.lifecycle.SavedStateHandle
import com.fridgelist.app.fakes.FakeItems
import com.fridgelist.app.fakes.FakeSession
import com.fridgelist.app.fakes.FakeTripEnder
import com.fridgelist.app.fakes.FakeTripNotifier
import com.fridgelist.app.fakes.FakeTrips
import com.fridgelist.app.fakes.MainDispatcherRule
import com.fridgelist.app.fakes.householdContext
import com.fridgelist.app.feature.lists.ListDetailViewModel
import com.fridgelist.app.feature.shopping.ShoppingViewModel
import com.fridgelist.core.data.items.ItemRepository
import com.fridgelist.core.data.items.ListItems
import com.fridgelist.core.data.session.Session
import com.fridgelist.core.model.ItemDoc
import com.fridgelist.core.model.ItemStatus
import com.fridgelist.core.model.ListDoc
import com.fridgelist.core.model.ListType
import com.fridgelist.core.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * QA: on a slow first load the list screen said "nothing to buy" (and Shopping "everything is in
 * the cart") before Firestore had answered. Screens are only `loaded` once the items arrived.
 */
class LoadingStateTest {

    @get:Rule val main = MainDispatcherRule()

    /** Items that arrive only when the test says so (a slow first snapshot). */
    private class SlowItems(private val delegate: FakeItems) : ItemRepository by delegate {
        val gate = MutableSharedFlow<ListItems>(replay = 1)
        override fun items(householdId: String, listId: String): Flow<ListItems> = gate
    }

    private val milk = ItemDoc(id = "1", name = "milk", status = ItemStatus.OPEN, addedBy = "mom")

    @Test
    fun listScreenWaitsForTheFirstSnapshot() = runTest(main.dispatcher) {
        val fake = FakeItems().apply { lists.value = listOf(ListDoc("l1", "Market", ListType.GROCERY)) }
        val slow = SlowItems(fake)
        val vm = ListDetailViewModel(SavedStateHandle(mapOf("listId" to "l1")), householdContext(Role.ADULT), fake, slow)
        vm.state.launchIn(backgroundScope)
        runCurrent()
        assertFalse(vm.state.value.loaded, "no empty state before Firestore answers")
        slow.gate.emit(ListItems(listOf(milk)))
        runCurrent()
        assertTrue(vm.state.value.loaded)
        assertEquals(1, vm.state.value.sections.toBuyCount)
    }

    @Test
    fun shoppingWaitsForTheFirstSnapshot() = runTest(main.dispatcher) {
        val fake = FakeItems().apply { lists.value = listOf(ListDoc("l1", "Market", ListType.GROCERY)) }
        val slow = SlowItems(fake)
        val vm = ShoppingViewModel(
            SavedStateHandle(mapOf("listId" to "l1")), householdContext(Role.HELPER, FakeSession(Session("Ayşe", "tr", "h1", true))),
            fake, slow, FakeTrips(), FakeSession(Session("Ayşe", "tr", "h1", true)), FakeTripEnder(), FakeTripNotifier(),
        )
        vm.state.launchIn(backgroundScope)
        runCurrent()
        assertFalse(vm.state.value.loaded, "\"everything is in the cart\" must not flash at the start")
        slow.gate.emit(ListItems(listOf(milk)))
        runCurrent()
        assertTrue(vm.state.value.loaded)
        assertEquals(1, vm.state.value.toBuyCount)
    }
}
