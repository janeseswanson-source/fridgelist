package com.fridgelist.app.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Locale

/** Dates in the user's locale, with the locale's own digits (CLAUDE.md rule 3). */
object Formatting {
    fun dateTime(epochSeconds: Long, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withDecimalStyle(DecimalStyle.of(locale))
            .withZone(zone)
            .format(Instant.ofEpochSecond(epochSeconds))
}
