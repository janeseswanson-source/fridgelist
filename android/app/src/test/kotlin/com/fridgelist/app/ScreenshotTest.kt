package com.fridgelist.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.fridgelist.app.feature.home.HomeScreen
import com.fridgelist.app.feature.settings.AppLanguage
import com.fridgelist.app.feature.settings.DevUiState
import com.fridgelist.app.feature.settings.SettingsScreen
import com.fridgelist.app.ui.theme.FridgeListTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of every A1 screen in Arabic (RTL, checked first), Turkish and English, light and dark.
 * Record with `./gradlew recordRoborazziDevDebug`; images land in `app/src/test/screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent { FridgeListTheme { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val home: @Composable () -> Unit = { HomeScreen(onOpenSettings = {}) }

    private val settings: @Composable () -> Unit = {
        SettingsScreen(
            language = AppLanguage.ARABIC,
            onLanguageSelected = {},
            dev = DevUiState(hostInput = "10.0.2.2", connectedHost = "10.0.2.2"),
            onHostChange = {},
            onSaveHost = {},
            onBack = {},
        )
    }

    @Test @Config(qualifiers = AR_LIGHT) fun home_ar_light() = capture("home_ar_light", home)
    @Test @Config(qualifiers = AR_DARK) fun home_ar_dark() = capture("home_ar_dark", home)
    @Test @Config(qualifiers = TR_LIGHT) fun home_tr_light() = capture("home_tr_light", home)
    @Test @Config(qualifiers = TR_DARK) fun home_tr_dark() = capture("home_tr_dark", home)
    @Test @Config(qualifiers = EN_LIGHT) fun home_en_light() = capture("home_en_light", home)
    @Test @Config(qualifiers = EN_DARK) fun home_en_dark() = capture("home_en_dark", home)

    @Test @Config(qualifiers = AR_LIGHT) fun settings_ar_light() = capture("settings_ar_light", settings)
    @Test @Config(qualifiers = AR_DARK) fun settings_ar_dark() = capture("settings_ar_dark", settings)
    @Test @Config(qualifiers = TR_LIGHT) fun settings_tr_light() = capture("settings_tr_light", settings)
    @Test @Config(qualifiers = TR_DARK) fun settings_tr_dark() = capture("settings_tr_dark", settings)
    @Test @Config(qualifiers = EN_LIGHT) fun settings_en_light() = capture("settings_en_light", settings)
    @Test @Config(qualifiers = EN_DARK) fun settings_en_dark() = capture("settings_en_dark", settings)

    private companion object {
        const val PHONE = "w411dp-h891dp"
        const val AR_LIGHT = "ar-ldrtl-$PHONE-notnight-xxhdpi"
        const val AR_DARK = "ar-ldrtl-$PHONE-night-xxhdpi"
        const val TR_LIGHT = "tr-ldltr-$PHONE-notnight-xxhdpi"
        const val TR_DARK = "tr-ldltr-$PHONE-night-xxhdpi"
        const val EN_LIGHT = "en-ldltr-$PHONE-notnight-xxhdpi"
        const val EN_DARK = "en-ldltr-$PHONE-night-xxhdpi"
    }
}
