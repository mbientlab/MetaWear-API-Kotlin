package com.mbientlab.metawear.model

import com.mbientlab.metawear.model.AnonymousSignalScheme.ProcessorLink
import com.mbientlab.metawear.protocol.Module
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// Ported from MWAnonymousSignalTests.swift — the pure (no-I/O) scheme suites:
// root identifiers, processor segments, and full-chain composition. The
// device-level suites (queryActiveProcessors + the test_anonymous_signal.py
// port) live in DeviceAnonymousSignalsTest.kt.

class AnonymousSignalTest {

    // ---- Root identifiers (Swift: SchemeRootIdentifierTests) ----

    @Test
    fun `acceleration packed XYZ`() {
        val s = AnonymousSignalScheme.rootIdentifier(
            module = Module.ACCELEROMETER, register = 0x04,
            channel = 0xFF, chunkOffset = 0, chunkLength = 6,
        )
        assertEquals("acceleration", s)
    }

    @Test
    fun `acceleration single axis Z`() {
        // Offset=4 → axis index 2 (Z)
        val s = AnonymousSignalScheme.rootIdentifier(
            module = Module.ACCELEROMETER, register = 0x04,
            channel = 0xFF, chunkOffset = 4, chunkLength = 2,
        )
        assertEquals("acceleration[2]", s)
    }

    @Test
    fun `angular velocity Y axis`() {
        // Matches TestGyroY: "angular-velocity[1]"
        val s = AnonymousSignalScheme.rootIdentifier(
            module = Module.GYRO, register = 0x05,
            channel = 0xFF, chunkOffset = 2, chunkLength = 2,
        )
        assertEquals("angular-velocity[1]", s)
    }

    @Test
    fun `temperature per channel`() {
        // TestTemperature: channel byte 0..3 drives the suffix.
        for (c in 0 until 4) {
            val s = AnonymousSignalScheme.rootIdentifier(
                module = Module.TEMPERATURE, register = 0xC1,
                channel = c, chunkOffset = 0, chunkLength = 4,
            )
            assertEquals("temperature[$c]", s)
        }
    }

    @Test
    fun `quaternion from sensor fusion`() {
        // Register 0x07 = quaternion data
        val s = AnonymousSignalScheme.rootIdentifier(
            module = Module.SENSOR_FUSION, register = 0x07,
            channel = 0xFF, chunkOffset = 0, chunkLength = 16,
        )
        assertEquals("quaternion", s)
    }

    @Test
    fun `unknown module returns null`() {
        val s = AnonymousSignalScheme.rootIdentifier(
            module = Module.HAPTIC, register = 0x01,
            channel = 0, chunkOffset = 0, chunkLength = 0,
        )
        assertNull(s)
    }

    // ---- Processor segments (Swift: SchemeProcessorSegmentTests) ----

    @Test
    fun `rms simple`() {
        val s = AnonymousSignalScheme.processorSegment(type = 0x07, id = 0, config = listOf(0xA5, 0x00))
        assertEquals("rms?id=0", s)
    }

    @Test
    fun `rss by config mode`() {
        val s = AnonymousSignalScheme.processorSegment(type = 0x07, id = 0, config = listOf(0xA5, 0x01))
        assertEquals("rss?id=0", s)
    }

    @Test
    fun `accumulate default mode`() {
        val s = AnonymousSignalScheme.processorSegment(type = 0x02, id = 1, config = listOf(0x00))
        assertEquals("accumulate?id=1", s)
    }

    @Test
    fun `count by config mode`() {
        val s = AnonymousSignalScheme.processorSegment(type = 0x02, id = 1, config = listOf(0x10))
        assertEquals("count?id=1", s)
    }

    @Test
    fun `accumulate uses mode bits not size bits`() {
        val s = AnonymousSignalScheme.processorSegment(type = 0x02, id = 1, config = listOf(0x01))
        assertEquals("accumulate?id=1", s)
    }

    @Test
    fun `low-pass vs high-pass`() {
        val lp = AnonymousSignalScheme.processorSegment(type = 0x03, id = 2, config = listOf(0x00, 0x00))
        val hp = AnonymousSignalScheme.processorSegment(type = 0x03, id = 2, config = listOf(0x00, 0x01))
        assertEquals("low-pass?id=2", lp)
        assertEquals("high-pass?id=2", hp)
    }

    @Test
    fun `covers full type table`() {
        val expected = mapOf(
            0x01 to "passthrough",
            0x06 to "comparison",
            0x08 to "time",
            0x09 to "math",
            0x0A to "delay",
            0x0B to "pulse",
            0x0C to "differential",
            0x0D to "threshold",
            0x0F to "buffer",
            0x10 to "packer",
            0x11 to "account",
            0x1B to "fuser",
        )
        for ((type, name) in expected) {
            val seg = AnonymousSignalScheme.processorSegment(type = type, id = 0, config = emptyList())
            assertEquals("$name?id=0", seg, "Expected $name?id=0 for type 0x${type.toString(16)}")
        }
    }

    @Test
    fun `unknown type returns null`() {
        val s = AnonymousSignalScheme.processorSegment(type = 0x7F, id = 0, config = emptyList())
        assertNull(s)
    }

    // ---- Full chain composition (Swift: SchemeCompositionTests) ----

    /**
     * Mirrors TestActivity signal[0]:
     * `"acceleration:rms?id=0:accumulate?id=1:time?id=2"`
     */
    @Test
    fun `activity full chain`() {
        val id = AnonymousSignalScheme.compose(
            root = "acceleration",
            chain = listOf(
                ProcessorLink(type = 0x07, id = 0, config = listOf(0xA5, 0x07)), // RMS
                ProcessorLink(type = 0x02, id = 1, config = listOf(0x00)),       // accumulate
                ProcessorLink(type = 0x08, id = 2, config = emptyList()),        // time
            ),
        )
        assertEquals("acceleration:rms?id=0:accumulate?id=1:time?id=2", id)
    }

    /**
     * TestActivity signal[1] — same chain but the terminal logger captures the
     * buffer's *state* register, not its data output:
     * `"acceleration:rms?id=0:accumulate?id=1:buffer-state?id=3"`
     */
    @Test
    fun `activity buffer-state chain`() {
        val id = AnonymousSignalScheme.compose(
            root = "acceleration",
            chain = listOf(
                ProcessorLink(type = 0x07, id = 0, config = listOf(0xA5, 0x07)),
                ProcessorLink(type = 0x02, id = 1, config = listOf(0x00)),
                ProcessorLink(type = 0x0F, id = 3, config = listOf(0x03)), // buffer
            ),
            captureStateOfTerminalBuffer = true,
        )
        assertEquals("acceleration:rms?id=0:accumulate?id=1:buffer-state?id=3", id)
    }

    /** TestQuaternionLimiter: `"quaternion:time?id=0"` */
    @Test
    fun `quaternion limiter`() {
        val id = AnonymousSignalScheme.compose(
            root = "quaternion",
            chain = listOf(ProcessorLink(type = 0x08, id = 0, config = emptyList())),
        )
        assertEquals("quaternion:time?id=0", id)
    }

    /** TestFuser: `"acceleration:fuser?id=1"` */
    @Test
    fun fuser() {
        val id = AnonymousSignalScheme.compose(
            root = "acceleration",
            chain = listOf(ProcessorLink(type = 0x1B, id = 1, config = listOf(0x01, 0x00))),
        )
        assertEquals("acceleration:fuser?id=1", id)
    }

    /** Flat root (no processors) → no trailing colon. */
    @Test
    fun `flat root`() {
        val id = AnonymousSignalScheme.compose(root = "acceleration", chain = emptyList())
        assertEquals("acceleration", id)
    }

    /** Unknown processor type short-circuits the whole composition. */
    @Test
    fun `unknown type aborts`() {
        val id = AnonymousSignalScheme.compose(
            root = "acceleration",
            chain = listOf(ProcessorLink(type = 0xFF, id = 0, config = emptyList())),
        )
        assertNull(id)
    }
}
