package com.mbientlab.metawear.app.core

import kotlinx.datetime.Instant

/**
 * True-rate estimation over the most recent samples: window the last
 * [WINDOW_SAMPLES] arrival times and divide `(count − 1)` by the spanned
 * seconds. Returns 0 when fewer than two samples exist or the span is not
 * positive.
 */
object EffectiveHz {

    const val WINDOW_SAMPLES: Int = 32

    fun compute(sampleTimes: List<Instant>): Double {
        val window = if (sampleTimes.size > WINDOW_SAMPLES) {
            sampleTimes.subList(sampleTimes.size - WINDOW_SAMPLES, sampleTimes.size)
        } else {
            sampleTimes
        }
        if (window.size < 2) return 0.0
        val spanSeconds = (window.last() - window.first()).inWholeMicroseconds / 1_000_000.0
        if (spanSeconds <= 0.0) return 0.0
        return (window.size - 1) / spanSeconds
    }
}
