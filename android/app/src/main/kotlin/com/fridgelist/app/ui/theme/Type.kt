package com.fridgelist.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.fridgelist.app.R

/**
 * Downloadable Google Fonts (no font files in the APK). Until they arrive, or on devices without
 * Google Play services, the system font is used, which covers Arabic and Turkish too.
 *
 * - Arabic UI: IBM Plex Sans Arabic (also carries Latin glyphs, so mixed lines look even).
 * - Turkish and English UI: Inter (full Turkish coverage: ı İ ş ğ ç ö ü).
 */
private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val weights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

private fun googleFamily(name: String): FontFamily =
    FontFamily(weights.map { Font(googleFont = GoogleFont(name), fontProvider = provider, weight = it) })

private val ArabicFamily: FontFamily by lazy { googleFamily("IBM Plex Sans Arabic") }
private val LatinFamily: FontFamily by lazy { googleFamily("Inter") }

private val Base = Typography()

private fun Typography.withFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

internal val ArabicTypography: Typography by lazy { Base.withFamily(ArabicFamily) }
internal val LatinTypography: Typography by lazy { Base.withFamily(LatinFamily) }
