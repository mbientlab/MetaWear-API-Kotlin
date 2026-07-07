package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.core.EffectiveHz
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Windowed effective-Hz estimation math. */
class EffectiveHzTest {

    private val t0 = Instant.fromEpochMilliseconds(0)

    @Test
    fun `steady 100 Hz stream reads 100 Hz`() {
        val times = (0 until 50).map { t0 + (it * 10).milliseconds }
        assertEquals(100.0, EffectiveHz.compute(times), 0.5)
    }

    @Test
    fun `fewer than two samples reads zero`() {
        assertEquals(0.0, EffectiveHz.compute(emptyList()))
        assertEquals(0.0, EffectiveHz.compute(listOf(t0)))
    }

    @Test
    fun `identical timestamps read zero`() {
        assertEquals(0.0, EffectiveHz.compute(listOf(t0, t0, t0)))
    }

    @Test
    fun `only the trailing window is measured`() {
        // 100 slow samples (1 Hz) followed by 32 fast ones (100 Hz): the
        // 32-sample window sees only the fast tail.
        val slow = (0 until 100).map { t0 + (it * 1000).milliseconds }
        val fastStart = slow.last() + 10.milliseconds
        val fast = (0 until 32).map { fastStart + (it * 10).milliseconds }
        assertEquals(100.0, EffectiveHz.compute(slow + fast), 1.0)
    }
}
