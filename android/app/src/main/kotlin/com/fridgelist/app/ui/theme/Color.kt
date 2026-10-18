package com.fridgelist.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Placeholder brand palette: fresh green (primary) with a warm saffron accent (secondary).
// Swap these values when the final brand colours arrive; every screen reads the scheme only.

internal val LightColors = lightColorScheme(
    primary = Color(0xFF1F7A55),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA8F2CC),
    onPrimaryContainer = Color(0xFF002113),
    secondary = Color(0xFF9A4521),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCC),
    onSecondaryContainer = Color(0xFF351000),
    tertiary = Color(0xFF36618E),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6FAF6),
    onBackground = Color(0xFF181D1A),
    surface = Color(0xFFF6FAF6),
    onSurface = Color(0xFF181D1A),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF404943),
    surfaceContainer = Color(0xFFEAEFEA),
    surfaceContainerHigh = Color(0xFFE4E9E4),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFC0C9C1),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF8DD5B1),
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF005234),
    onPrimaryContainer = Color(0xFFA8F2CC),
    secondary = Color(0xFFFFB595),
    onSecondary = Color(0xFF562000),
    secondaryContainer = Color(0xFF7A3010),
    onSecondaryContainer = Color(0xFFFFDBCC),
    tertiary = Color(0xFFA0CAFD),
    onTertiary = Color(0xFF003258),
    background = Color(0xFF0F1411),
    onBackground = Color(0xFFDFE4DF),
    surface = Color(0xFF0F1411),
    onSurface = Color(0xFFDFE4DF),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFC0C9C1),
    surfaceContainer = Color(0xFF1B211D),
    surfaceContainerHigh = Color(0xFF262B27),
    outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF404943),
)
