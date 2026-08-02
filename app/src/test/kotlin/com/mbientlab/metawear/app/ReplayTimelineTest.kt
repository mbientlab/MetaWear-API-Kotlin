package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.core.ReplayTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Time axis behind the session 3D replay scrubber. */
class ReplayTimelineTest {

    @Test
    fun `empty timeline has no index and zero duration`() {
        val timeline = ReplayTimeline(emptyList())
        assertNull(timeline.index(0.0))
        assertEquals(0.0, timeline.duration)
    }

    @Test
    fun `single sample always indexes zero`() {
        val timeline = ReplayTimeline(listOf(5_000.0))
        assertEquals(0.0, timeline.duration)
        assertEquals(0, timeline.index(0.0))
        assertEquals(0, timeline.index(99.0))
    }

    @Test
    fun `ticks normalize to zero-based seconds`() {
        val timeline = ReplayTimeline(listOf(3_600_000.0, 3_600_500.0, 3_601_000.0))
        assertEquals(listOf(0.0, 0.5, 1.0), timeline.times)
        assertEquals(1.0, timeline.duration)
    }

    @Test
    fun `index returns last sample at or before position`() {
        val timeline = ReplayTimeline(listOf(0.0, 100.0, 200.0, 300.0))
        assertEquals(0, timeline.index(0.0))
        assertEquals(1, timeline.index(0.1))
        assertEquals(1, timeline.index(0.15))
        assertEquals(2, timeline.index(0.299))
    }

    @Test
    fun `positions beyond the ends clamp to first and last`() {
        val timeline = ReplayTimeline(listOf(0.0, 1_000.0))
        assertEquals(0, timeline.index(-5.0))
        assertEquals(1, timeline.index(999.0))
    }

    @Test
    fun `out-of-order ticks clamp monotonic`() {
        val timeline = ReplayTimeline(listOf(0.0, 500.0, 400.0, 900.0))
        assertEquals(listOf(0.0, 0.5, 0.5, 0.9), timeline.times)
        assertEquals(2, timeline.index(0.6))
    }
}
