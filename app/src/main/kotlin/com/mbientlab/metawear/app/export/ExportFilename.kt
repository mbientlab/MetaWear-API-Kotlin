package com.mbientlab.metawear.app.export

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * CSV export filenames: `{device}-{sensor}-{yyyy-MM-dd-HH-mm-ss}.csv`.
 * Device names are sanitized to
 * letters/digits/underscore/dash (empty falls back to "MetaWear") and the
 * timestamp uses dashes only, so the name is safe on every filesystem.
 */
object ExportFilename {

    /**
     * @param discriminator Optional short unique suffix (e.g. a session-id
     *   prefix) so same-second exports from several boards can't overwrite
     *   each other's temp files.
     */
    fun make(
        deviceName: String,
        sensorTag: String,
        timestamp: Instant,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        discriminator: String? = null,
    ): String {
        val device = sanitize(deviceName).ifEmpty { "MetaWear" }
        val sensor = sanitize(sensorTag).ifEmpty { "sensor" }
        val suffix = discriminator?.let(::sanitize)?.takeIf { it.isNotEmpty() }?.let { "-$it" } ?: ""
        return "$device-$sensor-${format(timestamp, timeZone)}$suffix.csv"
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
