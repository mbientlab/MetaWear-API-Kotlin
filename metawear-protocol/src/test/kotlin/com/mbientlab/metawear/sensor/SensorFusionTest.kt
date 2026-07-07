package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.floatLE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ported from MWSensorFusionLoggingTests.swift — the sensor-fusion Loggable
 * conformance (logger keys, flash-log chunk layouts, parseLogSample). All of
 * the Swift suite ports cleanly: none of its tests require the not-yet-ported
 * `device.startLogging` / `downloadLogs`.
 */
class SensorFusionLoggingTest {

    // ---- Logger keys ----

    @Test fun quaternion_loggerKey() =
        assertEquals("quaternion", SensorFusionQuaternion().loggerKey)

    @Test fun euler_loggerKey() =
        assertEquals("euler-angles", SensorFusionEuler().loggerKey)

    @Test fun gravity_loggerKey() =
        assertEquals("gravity", SensorFusionGravity().loggerKey)

    @Test fun linearAcc_loggerKey() =
        assertEquals("linear-acceleration", SensorFusionLinearAcceleration().loggerKey)

    // ---- Chunk layouts — Quaternion (4 × float32 = 16 bytes → 4 chunks) ----

    @Test fun quaternion_chunkCount() =
        assertEquals(4, SensorFusionQuaternion().logDataChunks.size)

    @Test fun quaternion_chunkOffsets() =
        assertEquals(listOf(0, 4, 8, 12), SensorFusionQuaternion().logDataChunks.map { it.offset })

    @Test fun quaternion_chunkLengths_allFour() =
        assertTrue(SensorFusionQuaternion().logDataChunks.all { it.length == 4 })

    // ---- Chunk layouts — EulerAngles (4 × float32 = 16 bytes → 4 chunks) ----

    @Test fun euler_chunkCount() =
        assertEquals(4, SensorFusionEuler().logDataChunks.size)

    @Test fun euler_chunkOffsets() =
        assertEquals(listOf(0, 4, 8, 12), SensorFusionEuler().logDataChunks.map { it.offset })

    @Test fun euler_chunkLengths_allFour() =
        assertTrue(SensorFusionEuler().logDataChunks.all { it.length == 4 })

    // ---- Chunk layouts — Gravity / LinearAcc (3 × float32 = 12 bytes → 3 chunks) ----

    @Test fun gravity_chunkCount() =
        assertEquals(3, SensorFusionGravity().logDataChunks.size)

    @Test fun gravity_chunkOffsets() =
        assertEquals(listOf(0, 4, 8), SensorFusionGravity().logDataChunks.map { it.offset })

    @Test fun gravity_chunkLengths_allFour() =
        assertTrue(SensorFusionGravity().logDataChunks.all { it.length == 4 })

    @Test fun linearAcc_chunkCount() =
        assertEquals(3, SensorFusionLinearAcceleration().logDataChunks.size)

    @Test fun linearAcc_chunkOffsets() =
        assertEquals(listOf(0, 4, 8), SensorFusionLinearAcceleration().logDataChunks.map { it.offset })

    // ---- parseLogSample — Quaternion ----

    @Test fun quaternion_parseLogSample_unitQuaternion() {
        // Build 16 bytes: w=1.0, x=0.0, y=0.0, z=0.0 as little-endian float32s
        val data = encodedFloats(1.0f, 0.0f, 0.0f, 0.0f)
        val q = SensorFusionQuaternion().parseLogSample(data)
        assertEquals(1.0f, q.w, 0.0001f)
        assertEquals(0.0f, q.x, 0.0001f)
        assertEquals(0.0f, q.y, 0.0001f)
        assertEquals(0.0f, q.z, 0.0001f)
    }

    @Test fun quaternion_parseLogSample_arbitraryValues() {
        val data = encodedFloats(0.5f, 0.5f, 0.5f, 0.5f)
        val q = SensorFusionQuaternion().parseLogSample(data)
        assertEquals(0.5f, q.w, 0.0001f)
        assertEquals(0.5f, q.x, 0.0001f)
        assertEquals(0.5f, q.y, 0.0001f)
        assertEquals(0.5f, q.z, 0.0001f)
    }

    // ---- parseLogSample — EulerAngles ----

    @Test fun euler_parseLogSample() {
        // heading=90, pitch=-45, roll=0, yaw=180
        val data = encodedFloats(90.0f, -45.0f, 0.0f, 180.0f)
        val e = SensorFusionEuler().parseLogSample(data)
        assertEquals(90.0f, e.heading, 0.001f)
        assertEquals(-45.0f, e.pitch, 0.001f)
        assertEquals(0.0f, e.roll, 0.001f)
        assertEquals(180.0f, e.yaw, 0.001f)
    }

    // ---- parseLogSample — Gravity (m/s² → g via /9.80665) ----

    @Test fun gravity_parseLogSample_pointsDown() {
        // 1g downward: z = 9.80665 m/s² → after /9.80665 = 1.0 g
        val data = encodedFloats(0.0f, 0.0f, 9.80665f)
        val g = SensorFusionGravity().parseLogSample(data)
        assertEquals(0.0f, g.x, 0.0001f)
        assertEquals(0.0f, g.y, 0.0001f)
        assertEquals(1.0f, g.z, 0.0001f)
    }
}

// ---- Helpers ----

/** Encode floats as contiguous little-endian float32 bytes. */
private fun encodedFloats(vararg values: Float): ByteArray =
    values.fold(ByteArray(0)) { acc, f -> acc + floatLE(f) }
