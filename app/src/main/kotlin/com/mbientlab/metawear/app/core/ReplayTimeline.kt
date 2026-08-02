package com.mbientlab.metawear.app.core

/**
 * Maps a persisted session's tick timestamps onto a scrubber-friendly,
 * zero-based, monotonic seconds axis for 3D replay.
 *
 * @property times Seconds offsets from the first sample, non-decreasing
 *   (out-of-order ticks — reset glitches — are clamped forward).
 * @property duration Total span in seconds (== last element of [times]).
 */
class ReplayTimeline(ticksMs: List<Double>) {

    val times: List<Double>
    val duration: Double

    init {
        if (ticksMs.isEmpty()) {
            times = emptyList()
            duration = 0.0
        } else {
            val first = ticksMs[0]
            var floor = 0.0
            times = ticksMs.map { tick ->
                floor = maxOf(floor, (tick - first) / 1000.0)
                floor
            }
            duration = floor
        }
    }

    /**
     * Index of the last sample at or before [seconds], clamped to the ends.
     * `null` only for an empty timeline.
     */
    fun index(at: Double): Int? {
        if (times.isEmpty()) return null
        if (at <= times[0]) return 0
        if (at >= duration) return times.size - 1
        var lo = 0
        var hi = times.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (times[mid] <= at) lo = mid else hi = mid - 1
        }
        return lo
    }
}
