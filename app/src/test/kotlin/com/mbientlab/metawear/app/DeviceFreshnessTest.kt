package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.core.DeviceFreshness
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Advertisement-freshness window rules. */
class DeviceFreshnessTest {

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @Test
    fun `recent advertisement is fresh`() {
        assertTrue(DeviceFreshness.isFresh(now - 30.seconds, now))
    }

    @Test
    fun `silence beyond the window is stale`() {
        assertFalse(DeviceFreshness.isFresh(now - 3.minutes, now))
    }

    @Test
    fun `never-seen is stale`() {
        assertFalse(DeviceFreshness.isFresh(null, now))
    }

    @Test
    fun `boundary is exclusive`() {
        assertFalse(DeviceFreshness.isFresh(now - DeviceFreshness.WINDOW, now))
        assertTrue(DeviceFreshness.isFresh(now - DeviceFreshness.WINDOW + 10.milliseconds, now))
    }
}
