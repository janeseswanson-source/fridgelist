package com.fridgelist.app

import androidx.compose.runtime.Composable
import com.fridgelist.app.feature.household.InviteContent
import com.fridgelist.app.feature.household.InviteUiState
import com.fridgelist.app.feature.household.MembersScreen
import com.fridgelist.app.feature.household.MembersUiState
import com.fridgelist.app.feature.home.HomeScreen
import com.fridgelist.app.feature.onboarding.CreateHouseholdScreen
import com.fridgelist.app.feature.onboarding.CreateHouseholdUiState
import com.fridgelist.app.feature.onboarding.HouseholdChoiceScreen
import com.fridgelist.app.feature.onboarding.JoinHouseholdScreen
import com.fridgelist.app.feature.onboarding.JoinHouseholdUiState
import com.fridgelist.app.feature.onboarding.NotificationsScreen
import com.fridgelist.app.feature.onboarding.ProfileScreen
import com.fridgelist.app.feature.onboarding.ProfileUiState
import com.fridgelist.app.feature.onboarding.SignInScreen
import com.fridgelist.app.feature.onboarding.SignInUiState
import com.fridgelist.app.feature.onboarding.WelcomeScreen
import com.fridgelist.app.feature.settings.AppLanguage
import com.fridgelist.app.feature.settings.DevUiState
import com.fridgelist.app.feature.settings.SettingsScreen
import com.fridgelist.app.ui.theme.FridgeListTheme
import com.fridgelist.core.model.CreateInviteResponse
import com.fridgelist.core.model.MemberDoc
import com.fridgelist.core.model.Role
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of every screen in Arabic (RTL, reviewed first), Turkish and English, light and dark.
 * Record with `./gradlew recordRoborazziDevDebug`; images land in `app/src/test/screenshots/`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest(private val config: String, private val qualifiers: String) {

    companion object {
        private const val PHONE = "w411dp-h891dp"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<String>> = listOf(
            arrayOf("ar_light", "ar-ldrtl-$PHONE-notnight-xxhdpi"),
            arrayOf("ar_dark", "ar-ldrtl-$PHONE-night-xxhdpi"),
            arrayOf("tr_light", "tr-ldltr-$PHONE-notnight-xxhdpi"),
            arrayOf("tr_dark", "tr-ldltr-$PHONE-night-xxhdpi"),
            arrayOf("en_light", "en-ldltr-$PHONE-notnight-xxhdpi"),
            arrayOf("en_dark", "en-ldltr-$PHONE-night-xxhdpi"),
        )
    }

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers(qualifiers)
        // Invite expiry is shown in local time; pin the zone so images are the same on every machine.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Riyadh"))
    }

    private fun capture(screen: String, content: @Composable () -> Unit) =
        captureRoboImage("src/test/screenshots/${screen}_$config.png") { FridgeListTheme { content() } }

    @Test fun home() = capture("home") { HomeScreen(Samples.lists, {}, {}, {}, {}) }

    @Test fun home_empty() = capture("home_empty") { HomeScreen(Samples.lists.copy(lists = emptyList()), {}, {}, {}, {}) }

    @Test fun settings() = capture("settings") {
        SettingsScreen(
            language = AppLanguage.ARABIC,
            onLanguageSelected = {},
            dev = DevUiState(hostInput = "10.0.2.2", connectedHost = "10.0.2.2"),
            onHostChange = {},
            onSaveHost = {},
            onBack = {},
            onSignOut = {},
        )
    }

    @Test fun welcome() = capture("welcome") { WelcomeScreen(selected = "ar", onSelect = {}, onContinue = {}) }

    @Test fun signin() = capture("signin") {
        SignInScreen(SignInUiState(googleAvailable = false, testUserAvailable = true, testUserName = "Mama"), {}, {}, {}, {})
    }

    @Test fun profile() = capture("profile") { ProfileScreen(ProfileUiState(name = "Yasser", itemLanguage = "ar"), {}, {}, {}) }

    @Test fun household_choice() = capture("household_choice") { HouseholdChoiceScreen({}, {}) }

    @Test fun create_household() = capture("create_household") {
        CreateHouseholdScreen(CreateHouseholdUiState(name = "", defaultLanguage = "ar", nameInvalid = true), {}, {}, {}, {})
    }

    @Test fun join_household() = capture("join_household") {
        JoinHouseholdScreen(JoinHouseholdUiState(code = "ABCD2345", error = R.string.error_invite_expired), {}, {}, {}, {})
    }

    @Test fun notifications() = capture("notifications") { NotificationsScreen({}, {}) }

    @Test fun members() = capture("members") {
        MembersScreen(
            MembersUiState(
                members = listOf(
                    MemberDoc("u1", Role.OWNER, "Yasser", "ar"),
                    MemberDoc("u2", Role.ADULT, "Ayşe", "tr"),
                    MemberDoc("u3", Role.HELPER, "Maria", "fil"),
                    MemberDoc("u4", Role.KID, "Lina", "ar"),
                ),
                myUid = "u1",
            ),
            onInvite = {},
            onBack = {},
        )
    }

    @Test fun invite() = capture("invite") {
        InviteContent(
            InviteUiState(
                role = Role.HELPER,
                invite = CreateInviteResponse("ABCD2345", "https://links.example.com/join/ABCD2345", 1761384800),
            ),
            onRole = {},
            onCreate = {},
            onShare = {},
        )
    }

    @Test fun store_detection() = capture("store_detection") {
        androidx.compose.material3.Surface {
            com.fridgelist.app.feature.settings.DetectionSection(
                state = com.fridgelist.app.feature.settings.DetectionUi(
                    enabled = true,
                    status = com.fridgelist.app.feature.settings.DetectionStatus.WATCHING,
                    watchedCount = 9,
                    nearby = listOf(
                        com.fridgelist.core.model.NearbyStore("s1", "Panda", "panda", 0.0, 0.0, 120.0),
                        com.fridgelist.core.model.NearbyStore("s2", "بقالة الحي", "other", 0.0, 0.0, 300.0),
                        com.fridgelist.core.model.NearbyStore("s3", "Migros Jet", "migros", 0.0, 0.0, 450.0),
                    ),
                    muted = listOf(com.fridgelist.core.data.stores.MutedStore("s9", "Tamimi Markets")),
                ),
                onToggle = {}, onRationaleContinue = {}, onDisclosureContinue = {}, onDismiss = {}, onOpenAppSettings = {},
                onMute = {}, onUnmute = {}, onStoreName = {}, onAddStore = {},
            )
        }
    }

    @Test fun store_detection_needs_background() = capture("store_detection_needs_background") {
        androidx.compose.material3.Surface {
            com.fridgelist.app.feature.settings.DetectionSection(
                state = com.fridgelist.app.feature.settings.DetectionUi(
                    enabled = true,
                    status = com.fridgelist.app.feature.settings.DetectionStatus.NEEDS_BACKGROUND,
                ),
                onToggle = {}, onRationaleContinue = {}, onDisclosureContinue = {}, onDismiss = {}, onOpenAppSettings = {},
                onMute = {}, onUnmute = {}, onStoreName = {}, onAddStore = {},
            )
        }
    }

    @Test fun share_tag() = capture("share_tag") {
        com.fridgelist.app.feature.share.ShareTagScreen(
            state = com.fridgelist.app.feature.share.ShareTagUiState(listName = "البقالة", qrUrl = "https://links.example.com/k/Ab3_-xYz0123456789abcdEF"),
            hasNfc = true, nfcOn = true, onBack = {}, onGuestsCanAdd = {}, onRetry = {}, onShare = {}, onPrint = {},
            onWriteTag = {}, onCancelTag = {}, onNfcSettings = {},
        )
    }

    @Test fun share_tag_hold() = capture("share_tag_hold") {
        com.fridgelist.app.feature.share.ShareTagScreen(
            state = com.fridgelist.app.feature.share.ShareTagUiState(
                listName = "البقالة",
                qrUrl = "https://links.example.com/k/Ab3_-xYz0123456789abcdEF",
                tagStep = com.fridgelist.app.feature.share.TagStep.HOLD,
                message = R.string.share_nfc_too_small,
            ),
            hasNfc = true, nfcOn = true, onBack = {}, onGuestsCanAdd = {}, onRetry = {}, onShare = {}, onPrint = {},
            onWriteTag = {}, onCancelTag = {}, onNfcSettings = {},
        )
    }

    @Test fun guest_view() = capture("guest_view") {
        com.fridgelist.app.feature.share.SharedLinkScreen(
            state = com.fridgelist.app.feature.share.SharedLinkUiState(
                loading = false,
                link = com.fridgelist.core.model.ResolveShareLinkResponse(
                    "Komşu Evi", "Market", "add",
                    listOf(
                        com.fridgelist.core.model.GuestItem("1", "ekmek"),
                        com.fridgelist.core.model.GuestItem("2", "حليب", "2 L"),
                    ),
                ),
                added = 2,
            ),
            onBack = {}, onRetry = {}, onText = {}, onGuestName = {}, onAdd = {},
        )
    }
}
