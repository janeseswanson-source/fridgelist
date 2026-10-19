package com.fridgelist.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.fridgelist.app.feature.home.NewListContent
import com.fridgelist.app.feature.lists.AddItemsContent
import com.fridgelist.app.feature.lists.EditItemContent
import com.fridgelist.app.feature.lists.ListDetailScreen
import com.fridgelist.app.feature.lists.ListDetailUiState
import com.fridgelist.app.feature.shopping.ShoppingScreen
import com.fridgelist.app.feature.shopping.SummaryContent
import com.fridgelist.app.ui.theme.FridgeListTheme
import com.fridgelist.app.voice.VoiceState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/** A3 screens in ar (reviewed first), tr, en × light/dark. See [ScreenshotTest] for recording. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListScreenshotTest(private val config: String, private val qualifiers: String) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<String>> = ScreenshotTest.configs()
    }

    @Before
    fun setUp() = RuntimeEnvironment.setQualifiers(qualifiers)

    private fun capture(screen: String, content: @Composable () -> Unit) =
        captureRoboImage("src/test/screenshots/${screen}_$config.png") { FridgeListTheme { content() } }

    @Test fun list_detail() = capture("list_detail") {
        ListDetailScreen(Samples.detail, Samples.adultActions, remember { SnackbarHostState() }, {}, {})
    }

    @Test fun list_empty() = capture("list_empty") {
        ListDetailScreen(ListDetailUiState(listName = "البقالة", loaded = true), Samples.adultActions, remember { SnackbarHostState() }, {}, {})
    }

    @Test fun add_items() = capture("add_items") { AddItemsContent(Samples.add, {}, { _, _ -> }, {}, {}) }

    @Test fun add_items_listening() = capture("add_items_listening") {
        AddItemsContent(Samples.add.copy(text = "", chips = emptyList()), {}, { _, _ -> }, {}, {}, voice = VoiceState.Listening("٢ كيلو بندورة وعلبة لبنة", onDevice = true))
    }

    @Test fun add_items_voice_offline() = capture("add_items_voice_offline") {
        AddItemsContent(Samples.add, {}, { _, _ -> }, {}, {}, voice = VoiceState.Failed(R.string.voice_offline))
    }

    @Test fun edit_item() = capture("edit_item") { EditItemContent(Samples.editRow, canRename = true, onSave = {}) }

    @Test fun shopping() = capture("shopping") {
        ShoppingScreen(Samples.shopping, Samples.shoppingActions, remember { SnackbarHostState() }, {}, {})
    }

    @Test fun shopping_summary() = capture("shopping_summary") { SummaryContent(Samples.summary, "البقالة") {} }

    @Test fun new_list() = capture("new_list") { NewListContent { _, _, _ -> } }
}
