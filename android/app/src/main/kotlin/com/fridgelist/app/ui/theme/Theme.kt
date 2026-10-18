package com.fridgelist.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat

@Composable
fun FridgeListTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0]
    val typography = if (locale?.language == "ar") ArabicTypography else LatinTypography
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = typography,
        content = content,
    )
}
