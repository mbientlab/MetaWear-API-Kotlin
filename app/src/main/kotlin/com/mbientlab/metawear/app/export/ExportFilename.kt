package com.mbientlab.metawear.app.export

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * CSV export filenames: `{device}-{sensor}-{yyyy-MM-dd-HH-mm-ss}.csv`.
 * Port of `ExportFilename.swift` — device names are sanitized to
 * letters/digits/underscore/dash (empty falls back to "MetaWear") and the
 * timestamp uses dashes only, so the name is safe on every filesystem.
 */
object ExportFilename {

    fun make(
        deviceName: String,
        sensorTag: String,
        timestamp: Instant,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val device = sanitize(deviceName).ifEmpty { "MetaWear" }
        val sensor = sanitize(sensorTag).ifEmpty { "sensor" }
        return "$device-$sensor-${format(timestamp, timeZone)}.csv"
    }

    /** Keep letters, digits, `_`, and `-`; drop everything else. */
    fun sanitize(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun format(timestamp: Instant, timeZone: TimeZone): String {
        val dt = timestamp.toLocalDateTime(timeZone)
        fun pad(v: Int) = v.toString().padStart(2, '0')
        return "${dt.year}-${pad(dt.monthNumber)}-${pad(dt.dayOfMonth)}" +
            "-${pad(dt.hour)}-${pad(dt.minute)}-${pad(dt.second)}"
    }
}
