package com.fridgelist.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import com.fridgelist.app.feature.settings.AppLanguage
import com.fridgelist.app.feature.settings.DevUiState
import com.fridgelist.app.feature.settings.SettingsScreen
import com.fridgelist.app.ui.theme.FridgeListTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(dev: DevUiState?, onLanguage: (AppLanguage) -> Unit = {}) {
        compose.setContent {
            FridgeListTheme {
                SettingsScreen(AppLanguage.TURKISH, onLanguage, dev, {}, {}, {})
            }
        }
    }

    @Test
    fun languageNamesAreShownInTheirOwnLanguage() {
        show(dev = null)
        compose.onNodeWithText("العربية").assertIsDisplayed()
        compose.onNodeWithText("Türkçe").assertIsSelected()
        compose.onNodeWithText("English").assertIsDisplayed()
    }

    @Test
    fun choosingALanguageReportsIt() {
        var picked: AppLanguage? = null
        show(dev = null) { picked = it }
        compose.onNodeWithText("العربية").performClick()
        assertEquals(AppLanguage.ARABIC, picked)
    }

    @Test
    fun developerSectionOnlyWhenProvided() {
        show(dev = null)
        compose.onNodeWithText("Developer").assertDoesNotExist()
    }

    @Test
    fun developerSectionShowsHostError() {
        show(dev = DevUiState(hostInput = "http://x", hostInvalid = true))
        // Below the language and numbers sections: scroll there first.
        compose.onNodeWithText("Developer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Enter an IP address or host name, without a port").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ar-ldrtl")
    fun arabicStringsAreUsedInArabic() {
        show(dev = null)
        compose.onNodeWithText("الإعدادات").assertIsDisplayed()
        compose.onNodeWithText("لغة التطبيق").assertIsDisplayed()
    }
}
