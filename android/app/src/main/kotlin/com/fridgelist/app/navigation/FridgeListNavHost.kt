package com.fridgelist.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fridgelist.app.feature.home.HomeScreen
import com.fridgelist.app.feature.settings.SettingsRoute
import kotlinx.serialization.Serializable

/** Type-safe routes. Later milestones add lists, list detail, shopping, onboarding. */
@Serializable
data object Home

@Serializable
data object Settings

@Composable
fun FridgeListNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Home) {
        composable<Home> {
            HomeScreen(onOpenSettings = { navController.navigate(Settings) })
        }
        composable<Settings> {
            SettingsRoute(onBack = { navController.popBackStack() })
        }
    }
}
