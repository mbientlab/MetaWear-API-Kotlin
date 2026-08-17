package com.mbientlab.metawear.app.ui.components

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.datetime.Instant

private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun Instant.zoned() = java.time.Instant.ofEpochMilli(toEpochMilliseconds()).atZone(ZoneId.systemDefault())

/** "Aug 16, 2026, 7:15 PM" (locale-dependent). */
fun Instant.formatDateTime(): String = dateTimeFormatter.format(zoned())

/** "19:15:03" */
fun Instant.formatTime(): String = timeFormatter.format(zoned())

/** "19:15" */
fun Instant.formatClock(): String = clockFormatter.format(zoned())

/** "mm:ss" under an hour, "h:mm:ss" beyond. */
fun formatDuration(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** Fixed-precision value by unit: 2 digits for fine units, 1 for moderate, 0 for coarse. */
fun formatMeasurement(value: Float, unit: String): String {
    val digits = when (unit) {
        "g", "", "ratio" -> 2
        "°C", "%", "µT", "lux", "m" -> 1
        else -> 0
    }
    return String.format(Locale.US, "%.${digits}f", value)
}

/** "50 Hz" for whole rates, "12.5 Hz" otherwise. */
fun formatHz(hz: Double): String =
    if (hz == hz.toLong().toDouble()) "${hz.toLong()} Hz" else String.format(Locale.US, "%.1f Hz", hz)
