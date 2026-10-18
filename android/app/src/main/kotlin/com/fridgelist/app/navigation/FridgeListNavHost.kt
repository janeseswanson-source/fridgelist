package com.fridgelist.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fridgelist.app.AppViewModel
import com.fridgelist.app.feature.home.HomeRoute
import com.fridgelist.app.feature.household.MembersRoute
import com.fridgelist.app.feature.lists.ListDetailRoute
import com.fridgelist.app.feature.onboarding.CreateHouseholdRoute
import com.fridgelist.app.feature.onboarding.HouseholdChoiceScreen
import com.fridgelist.app.feature.onboarding.JoinHouseholdRoute
import com.fridgelist.app.feature.onboarding.NotificationsRoute
import com.fridgelist.app.feature.onboarding.ProfileRoute
import com.fridgelist.app.feature.onboarding.SignInRoute
import com.fridgelist.app.feature.onboarding.WelcomeRoute
import com.fridgelist.app.feature.settings.SettingsRoute
import com.fridgelist.core.data.session.OnboardingStep
import kotlinx.serialization.Serializable

// Type-safe routes. Later milestones add shopping, trips, planner.
@Serializable data object Welcome
@Serializable data object SignIn
@Serializable data object Profile
@Serializable data object HouseholdChoice
@Serializable data object CreateHousehold
@Serializable data class JoinHousehold(val code: String? = null)
@Serializable data object Notifications
@Serializable data object Home
@Serializable data class ListDetail(val listId: String)
@Serializable data object Members
@Serializable data object Settings

private fun OnboardingStep.route(): Any = when (this) {
    OnboardingStep.WELCOME -> Welcome
    OnboardingStep.PROFILE -> Profile
    OnboardingStep.HOUSEHOLD -> HouseholdChoice
    OnboardingStep.NOTIFICATIONS -> Notifications
    OnboardingStep.DONE -> Home
}

/**
 * In-app Back. Pops only when there is a screen underneath: a fast double tap (or a keyboard
 * Space/Enter repeat) used to pop the last screen too and leave the app blank.
 */
private fun NavController.popIfCan() {
    if (previousBackStackEntry != null) popBackStack()
}

/** Moves to an onboarding step and clears the back stack, so Back never returns to a finished step. */
private fun NavController.goTo(step: OnboardingStep, joinCode: String?) {
    navigate(step.route()) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
    if (step == OnboardingStep.HOUSEHOLD && joinCode != null) navigate(JoinHousehold(joinCode))
}

@Composable
fun FridgeListNavHost(start: OnboardingStep, app: AppViewModel) {
    val nav = rememberNavController()
    val onNext: (OnboardingStep) -> Unit = { step ->
        nav.goTo(step, if (step == OnboardingStep.HOUSEHOLD) app.consumeJoinCode() else null)
    }

    // An invite link while signed in and set up opens the join screen right away; before that,
    // it waits until onboarding reaches the household step.
    val pendingJoinCode by app.pendingJoinCode.collectAsStateWithLifecycle()
    LaunchedEffect(pendingJoinCode) {
        if (pendingJoinCode != null && app.currentStep() >= OnboardingStep.HOUSEHOLD) {
            app.consumeJoinCode()?.let { nav.navigate(JoinHousehold(it)) }
        }
    }

    NavHost(navController = nav, startDestination = start.route()) {
        composable<Welcome> { WelcomeRoute(onContinue = { nav.navigate(SignIn) }) }
        composable<SignIn> { SignInRoute(onNext = onNext, onBack = { nav.popIfCan() }) }
        composable<Profile> { ProfileRoute(onNext = onNext) }
        composable<HouseholdChoice> {
            HouseholdChoiceScreen(
                onCreate = { nav.navigate(CreateHousehold) },
                onJoin = { nav.navigate(JoinHousehold()) },
            )
        }
        composable<CreateHousehold> { CreateHouseholdRoute(onNext = onNext, onBack = { nav.popIfCan() }) }
        composable<JoinHousehold> { JoinHouseholdRoute(onNext = onNext, onBack = { nav.popIfCan() }) }
        composable<Notifications> { NotificationsRoute(onNext = onNext) }
        composable<Home> {
            HomeRoute(
                onOpenList = { nav.navigate(ListDetail(it)) },
                onOpenMembers = { nav.navigate(Members) },
                onOpenSettings = { nav.navigate(Settings) },
            )
        }
        composable<ListDetail> { ListDetailRoute(onBack = { nav.popIfCan() }) }
        composable<Members> { MembersRoute(onBack = { nav.popIfCan() }) }
        composable<Settings> {
            SettingsRoute(
                onBack = { nav.popIfCan() },
                onSignedOut = { nav.goTo(OnboardingStep.WELCOME, null) },
            )
        }
    }
}
