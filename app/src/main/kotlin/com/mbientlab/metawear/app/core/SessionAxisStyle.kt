package com.mbientlab.metawear.app.core

/**
 * Chart-style recovery for persisted sessions. Live channels know their
 * [SensorKey]; a stored session only carries its type discriminator
 * (`sensorKind`) and the capture-time label — this maps those back to the
 * correct [AxisStyle] so history charts render with the right channels,
 * units, and ranges.
 */
object SessionAxisStyle {

    /**
     * Resolve the style for a stored session.
     *
     * 1. Quaternion / Euler discriminators resolve directly (the kind alone
     *    is unambiguous).
     * 2. Otherwise the label's first `" · "` token is matched against sensor
     *    titles; a `±N` label chunk restores the captured range.
     * 3. Unknown labels (foreign recoveries, future sensors) fall back to a
     *    generic n-channel style.
     */
    fun forSession(sensorKind: String, label: String?, channelCount: Int): AxisStyle {
        when (sensorKind) {
            "quaternion" -> return SensorKey.FUSION_QUATERNION.axisStyle
            "euler" -> return SensorKey.FUSION_EULER.axisStyle
        }
        val fromLabel = label?.let(::styleFromLabel)
        return fromLabel ?: generic(channelCount)
    }

    private fun styleFromLabel(label: String): AxisStyle? {
        val parts = label.split(" · ")
        val title = parts.firstOrNull() ?: return null
        val key = SensorKey.entries.firstOrNull { it.title == title } ?: return null
        var style = key.axisStyle
        // Restore the captured full-scale range (e.g. "±500 dps").
        val rangePart = parts.firstOrNull { it.startsWith("±") }
        if (rangePart != null) {
            val value = rangePart.removePrefix("±")
                .takeWhile { it.isDigit() || it == '.' }
                .toFloatOrNull()
            if (value != null) style = style.copy(yRange = -value..value)
        }
        return style
    }

    /** Fallback style: x/y/z/w channels clamped to 1..4, unitless, autoscaled. */
    fun generic(channelCount: Int): AxisStyle {
        val channels = channelCount.coerceIn(1, 4)
        return AxisStyle(
            unit = "",
            yRange = null,
            labels = listOf("x", "y", "z", "w").take(channels),
            chartChannels = channels,
        )
    }

    /** Chart channel count implied by a persistence discriminator. */
    fun channelCountFor(sensorKind: String): Int = when (sensorKind) {
        "quaternion", "euler" -> 4
        "cartesian", "corrected-cartesian" -> 3
        else -> 1
    }
}
