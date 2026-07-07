package com.mbientlab.metawear

import com.mbientlab.metawear.model.AnonymousSignal
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.transport.MockBleTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Ported from MWAnonymousSignalTests.swift — the device-level suites:
// queryActiveProcessors (Tranche A) and the test_anonymous_signal.py port
// (Tranche D). The pure scheme suites live in model/AnonymousSignalTest.kt.
//
// Wire format note: real MetaWear firmware does NOT echo the queried logger/
// processor ID back in the response — the response is just `[module, 0x82,
// payload...]`. The queried `id` is implicit (the loop variable in the SDK).
// These fixtures match the Python byte-strings byte-for-byte.

class DeviceAnonymousSignalsTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    /**
     * Modules the Swift fixture's stub board reports present: accel,
     * temperature, logging, data-processor, gyro, fusion.
     */
    private val presentModules = setOf(0x03, 0x04, 0x0B, 0x09, 0x13, 0x19)

    private suspend fun TestScope.connectedDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice(mac, transport, backgroundScope)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport, presentModules)
        device.connect()
        discovery.cancel()
        return device to transport
    }

    /**
     * Scripted responder: answers logger, processor, and range queries with
     * pre-declared byte vectors. Port of the Swift `ScriptedResponder` actor,
     * which itself mirrors the Python `AnonymousSignalBase.commandLogger` +
     * `schedule_response` pattern. Response payloads are stored without the
     * 2-byte `[module, 0x82]` header, matching the Swift fixture.
     */
    private class ScriptedReplies(
        var accelRange: Int = 0x08, // BMI160 ±8g
        var gyroRange: Int = 0x03,  // ±250 dps
    ) {
        private val loggerResponses = mutableMapOf<Int, ByteArray>() // logger_id → payload (no header)
        private val procResponses = mutableMapOf<Int, ByteArray>()

        fun setLoggerResponse(id: Int, vararg payload: Int) {
            loggerResponses[id] = bytes(*payload)
        }

        fun setProcResponse(id: Int, vararg payload: Int) {
            procResponses[id] = bytes(*payload)
        }

        fun reply(cmd: ByteArray): ByteArray? {
            if (cmd.size < 2) return null
            val module = cmd[0].toInt() and 0xFF
            val register = cmd[1].toInt() and 0xFF
            // [0x0B, 0x82, id] — logger query.
            // Real firmware response: [0x0B, 0x82, src_mod, src_reg, src_data_id, packed]
            // (no logger-ID echo — `id` is implicit from the query)
            if (cmd.size == 3 && module == 0x0B && register == 0x82) {
                return loggerResponses[cmd[2].toInt() and 0xFF]?.let { bytes(0x0B, 0x82) + it }
            }
            // [0x09, 0x82, id] — processor query.
            // Real firmware response: [0x09, 0x82, parent_mod, parent_reg,
            // parent_proc_id, packed, proc_type, config...]
            if (cmd.size == 3 && module == 0x09 && register == 0x82) {
                return procResponses[cmd[2].toInt() and 0xFF]?.let { bytes(0x09, 0x82) + it }
            }
            // [0x03, 0x83] — accel range read
            if (cmd.size == 2 && module == 0x03 && register == 0x83) {
                return bytes(0x03, 0x83, 40, accelRange)
            }
            // [0x13, 0x83] — gyro range read
            if (cmd.size == 2 && module == 0x13 && register == 0x83) {
                return bytes(0x13, 0x83, 40, gyroRange)
            }
            return null
        }
    }

    private suspend fun TestScope.anonSetup(
        accelRange: Int = 0x08,
        gyroRange: Int = 0x03,
    ): Triple<MetaWearDevice, ScriptedReplies, Job> {
        val (device, transport) = connectedDevice()
        val replies = ScriptedReplies(accelRange = accelRange, gyroRange = gyroRange)
        val job = backgroundScope.autoReply(transport) { replies.reply(it) }
        return Triple(device, replies, job)
    }

    // ---- Tranche A: queryActiveProcessors ----

    /**
     * Exercises the TestActivity Python fixture: a three-stage chain
     *   accel → RMS(proc0) → accumulate(proc1) → time(proc2)
     * plus a buffer-state(proc3) branch. Confirms every byte-level field
     * is parsed correctly and that `parentIsProcessor` reflects the chain.
     */
    @Test
    fun `queryActiveProcessors parses activity chain`() = runTest {
        val (device, transport) = connectedDevice()

        // Python TestActivity responses. Wire format is `[0x09, 0x82,
        // parent_mod, parent_reg, parent_proc_id, packed, proc_type, config...]`
        // — no processor-id echo; the SDK derives the id from its own query
        // loop variable.
        val responses = mapOf(
            // proc 0: accel XYZ → RMS. parent_mod=0x03, parent_reg=0x04,
            //         parent_proc_id=0xFF, packed=0xA0 (offset=0, length=6), type=0x07 (RMS)
            0x00 to bytes(
                0x09, 0x82,
                0x03, 0x04, 0xFF, 0xA0, 0x07,
                0xA5, 0x00, 0x00, 0x00, 0x00, 0xD0,
                0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            ),
            // proc 1: RMS(proc0) → accumulate. parent=dataproc(0x09)/notify(0x03),
            //         parent_proc_id=0x00, packed=0x20 (offset=0, length=2), type=0x02
            0x01 to bytes(
                0x09, 0x82,
                0x09, 0x03, 0x00, 0x20, 0x02,
                0x07, 0x00, 0x00, 0x00, 0x00, 0xD0,
                0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            ),
            // proc 2: accumulate(proc1) → time. parent_proc_id=0x01, packed=0x60 (offset=0,len=4), type=0x08
            0x02 to bytes(
                0x09, 0x82,
                0x09, 0x03, 0x01, 0x60, 0x08,
                0x13, 0x30, 0x75, 0x00, 0x00, 0xD0,
                0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            ),
            // proc 3: accumulate(proc1) → buffer (type=0x0F).
            0x03 to bytes(
                0x09, 0x82,
                0x09, 0x03, 0x01, 0x60, 0x0F,
                0x03, 0x00, 0x00, 0x00, 0x00, 0xD0,
                0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            ),
            // Enumeration stops after id=3 by letting id=4 time out.
        )
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size == 3 && (cmd[0].toInt() and 0xFF) == 0x09 && (cmd[1].toInt() and 0xFF) == 0x82) {
                responses[cmd[2].toInt() and 0xFF]
            } else {
                null
            }
        }

        val procs = device.queryActiveProcessors()
        replies.cancel()

        assertEquals(4, procs.size)

        // Proc 0 — RMS on accel XYZ
        assertEquals(0, procs[0].processorID)
        assertEquals(Module.ACCELEROMETER, procs[0].parentModule)
        assertEquals(0x04, procs[0].parentRegister)
        assertFalse(procs[0].parentIsProcessor)
        assertEquals(0, procs[0].chunkOffset)
        assertEquals(6, procs[0].chunkLength)
        assertEquals(0x07, procs[0].processorType)
        assertEquals(0xA5, procs[0].configBytes.first())

        // Proc 1 — accumulate of proc0
        assertTrue(procs[1].parentIsProcessor)
        assertEquals(0x00, procs[1].parentProcessorID)
        assertEquals(0x02, procs[1].processorType)
        assertEquals(2, procs[1].chunkLength)

        // Proc 2 — time of proc1
        assertTrue(procs[2].parentIsProcessor)
        assertEquals(0x01, procs[2].parentProcessorID)
        assertEquals(0x08, procs[2].processorType)
        assertEquals(4, procs[2].chunkLength)

        // Proc 3 — buffer of proc1
        assertEquals(0x01, procs[3].parentProcessorID)
        assertEquals(0x0F, procs[3].processorType)
    }

    /** Empty-graph short-circuit: first query times out → no processors. */
    @Test
    fun `queryActiveProcessors with no processors times out early`() = runTest {
        val (device, _) = connectedDevice()
        val procs = device.queryActiveProcessors()
        assertTrue(procs.isEmpty())
    }

    // ---- Tranche D: TestAcceleration ----

    @Test
    fun `acceleration sync loggers and identifier`() = runTest {
        val (device, replies, job) = anonSetup()

        // Logger 0: accel packed=0x60 (offset=0, length=4)
        // Logger 1: accel packed=0x24 (offset=4, length=2) — completes XYZ split
        replies.setLoggerResponse(0, 0x03, 0x04, 0xFF, 0x60)
        replies.setLoggerResponse(1, 0x03, 0x04, 0xFF, 0x24)

        val signals = device.createAnonymousDataSignals()
        job.cancel()

        assertEquals(1, signals.size)
        assertEquals("acceleration", signals.first().identifier)
        assertEquals(listOf(0, 1), signals.first().loggerIDs)
    }

    @Test
    fun `acceleration decode xyz`() = runTest {
        val (device, replies, job) = anonSetup()
        replies.setLoggerResponse(0, 0x03, 0x04, 0xFF, 0x60)
        replies.setLoggerResponse(1, 0x03, 0x04, 0xFF, 0x24)

        val signals = device.createAnonymousDataSignals()
        job.cancel()
        val sig = signals.first()

        // int16 values: x=0x00F5=245, y=0x013D=317, z=0x0FDD=4061
        // Scale 4096 → x=0.0598, y=0.0774, z=0.9915
        val payload = bytes(0xF5, 0x00, 0x3D, 0x01, 0xDD, 0x0F)
        val out = sig.decode(payload)
        assertEquals(1, out.size)
        val v = (out[0] as AnonymousSignal.Output.Cartesian).value
        assertEquals(0.060f, v.x, 0.001f)
        assertEquals(0.077f, v.y, 0.001f)
        assertEquals(0.991f, v.z, 0.001f)
    }

    // ---- Tranche D: TestGyroY ----

    @Test
    fun `gyro Y sync and identifier`() = runTest {
        // Python uses gyr_range=0x03 (250 dps → scale 131.2)
        val (device, replies, job) = anonSetup()

        // Single logger on gyro Y axis: packed=0x22 (offset=2, length=2)
        replies.setLoggerResponse(0, 0x13, 0x05, 0xFF, 0x22)

        val signals = device.createAnonymousDataSignals()
        job.cancel()

        assertEquals(1, signals.size)
        assertEquals("angular-velocity[1]", signals.first().identifier)
    }

    @Test
    fun `gyro Y decode scalar`() = runTest {
        val (device, replies, job) = anonSetup()
        replies.setLoggerResponse(0, 0x13, 0x05, 0xFF, 0x22)

        val signals = device.createAnonymousDataSignals()
        job.cancel()
        val sig = signals.first()

        // int16 value -7 → -7 / 131.2 ≈ -0.0534
        val out = sig.decode(bytes(0xF9, 0xFF))
        assertEquals(1, out.size)
        val v = (out[0] as AnonymousSignal.Output.Scalar).value
        assertEquals(-0.053f, v, 0.001f)
    }

    // ---- Tranche D: TestSplitImu ----

    @Test
    fun `split imu produces accel and gyro signals`() = runTest {
        val (device, replies, job) = anonSetup()

        // Python fixture: 4 loggers total — accel(id=0,2), gyro(id=1,3).
        replies.setLoggerResponse(0, 0x03, 0x04, 0xFF, 0x60)
        replies.setLoggerResponse(1, 0x13, 0x05, 0xFF, 0x60)
        replies.setLoggerResponse(2, 0x03, 0x04, 0xFF, 0x24)
        replies.setLoggerResponse(3, 0x13, 0x05, 0xFF, 0x24)

        val signals = device.createAnonymousDataSignals()
        job.cancel()

        assertEquals(2, signals.size)
        val ids = signals.map { it.identifier }
        assertTrue("acceleration" in ids)
        assertTrue("angular-velocity" in ids)
    }

    // ---- Tranche D: TestActivity ----

    @Test
    fun `activity full chain identifiers`() = runTest {
        val (device, replies, job) = anonSetup()

        // Two loggers on data-processor output:
        //   logger 0 — proc 2 data (time terminus)   register 0x03, channel=0x02
        //   logger 1 — proc 3 state (buffer-state)   register 0xC4, channel=0x03
        replies.setLoggerResponse(0, 0x09, 0x03, 0x02, 0x60)
        replies.setLoggerResponse(1, 0x09, 0xC4, 0x03, 0x60)

        // Processor chain: accel → RMS(0) → accumulate(1) → time(2), buffer(3)
        replies.setProcResponse(
            0,
            0x03, 0x04, 0xFF, 0xA0, 0x07,
            0xA5, 0x00, 0x00, 0x00, 0x00, 0xD0,
            0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        replies.setProcResponse(
            1,
            0x09, 0x03, 0x00, 0x20, 0x02,
            0x07, 0x00, 0x00, 0x00, 0x00, 0xD0,
            0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        replies.setProcResponse(
            2,
            0x09, 0x03, 0x01, 0x60, 0x08,
            0x13, 0x30, 0x75, 0x00, 0x00, 0xD0,
            0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        replies.setProcResponse(
            3,
            0x09, 0x03, 0x01, 0x60, 0x0F,
            0x03, 0x00, 0x00, 0x00, 0x00, 0xD0,
            0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )

        val signals = device.createAnonymousDataSignals()
        job.cancel()

        assertEquals(2, signals.size)
        val ids = signals.map { it.identifier }
        assertTrue("acceleration:rms?id=0:accumulate?id=1:time?id=2" in ids)
        assertTrue("acceleration:rms?id=0:accumulate?id=1:buffer-state?id=3" in ids)
    }

    // ---- Tranche D: TestQuaternionLimiter ----

    @Test
    fun `quaternion limiter identifier`() = runTest {
        val (device, replies, job) = anonSetup()

        // Four loggers capturing the quaternion-time output, one per 4-byte chunk.
        // Each targets proc 0's notify register; channels all = 0x00.
        // Packed: offset=0/4/8/12, length=4 → 0x60, 0x64, 0x68, 0x6C.
        for ((id, packed) in listOf(0 to 0x60, 1 to 0x64, 2 to 0x68, 3 to 0x6C)) {
            replies.setLoggerResponse(id, 0x09, 0x03, 0x00, packed)
        }

        // Proc 0: quaternion → time.
        // parent_mod=0x19 (sensorFusion), parent_reg=0x07 (quaternion),
        // parent_proc_id=0xFF, packed=0xE0 (offset=0, length=16), type=0x08 (time)
        replies.setProcResponse(
            0,
            0x19, 0x07, 0xFF, 0xE0, 0x08,
            0x17, 0x14, 0x00, 0x00, 0x00, 0xD0,
            0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )

        val signals = device.createAnonymousDataSignals()
        job.cancel()

        assertEquals(1, signals.size)
        assertEquals("quaternion:time?id=0", signals.first().identifier)
    }

    // ---- Tranche D: TestMultipleLoggers ----

    @Test
    fun `multiple loggers split gyro into two signals`() = runTest {
        val (device, replies, job) = anonSetup()

        // Python fixture: three loggers on gyro.
        // id 0: gyro packed=0x60 (offset=0, length=4) — X+Y chunk
        // id 1: gyro packed=0x24 (offset=4, length=2) — Z chunk, completes XYZ with id 0
        // id 2: gyro packed=0x22 (offset=2, length=2) — single Y-axis signal
        replies.setLoggerResponse(0, 0x13, 0x05, 0xFF, 0x60)
        replies.setLoggerResponse(1, 0x13, 0x05, 0xFF, 0x24)
        replies.setLoggerResponse(2, 0x13, 0x05, 0xFF, 0x22)

        val signals = device.createAnonymousDataSignals()
        job.cancel()

        assertEquals(2, signals.size)
        val ids = signals.map { it.identifier }
        assertTrue("angular-velocity" in ids)
        assertTrue("angular-velocity[1]" in ids)
    }

    // ---- Tranche D: TestTemperature ----

    @Test
    fun `temperature four channel identifiers`() = runTest {
        val (device, replies, job) = anonSetup()

        // One logger per temperature channel (0..3).
        // packed=0x20 → offset=0, length=2 (sample is 2 bytes).
        for (c in 0 until 4) {
            replies.setLoggerResponse(c, 0x04, 0xC1, c, 0x20)
        }

        val signals = device.createAnonymousDataSignals()
        job.cancel()

        assertEquals(4, signals.size)
        for (c in 0 until 4) {
            assertTrue(
                signals.any { it.identifier == "temperature[$c]" },
                "Expected a temperature[$c] signal",
            )
        }
    }

    @Test
    fun `temperature decode samples`() = runTest {
        val (device, replies, job) = anonSetup()
        for (c in 0 until 4) {
            replies.setLoggerResponse(c, 0x04, 0xC1, c, 0x20)
        }

        val signals = device.createAnonymousDataSignals()
        job.cancel()

        // Temperature scale = 8 LSB/°C. Raw 258 → 32.25°C.
        val sig = signals.first { it.identifier == "temperature[0]" }
        val out = sig.decode(bytes(0x02, 0x01)) // 258
        val v = (out[0] as AnonymousSignal.Output.Scalar).value
        assertEquals(32.25f, v, 0.001f)
    }

    // ---- Tranche D: TestFuser ----

    @Test
    fun `fuser identifier and chain`() = runTest {
        val (device, replies, job) = anonSetup(accelRange = 0x03, gyroRange = 0x04)

        // Three loggers on fuser output (proc 1), 4-byte chunks covering the
        // 12-byte fused sample.
        for ((id, packed) in listOf(0 to 0x60, 1 to 0x64, 2 to 0x68)) {
            replies.setLoggerResponse(id, 0x09, 0x03, 0x01, packed)
        }

        // Proc 0: gyro branch
        replies.setProcResponse(
            0,
            0x13, 0x05, 0xFF, 0xA0, 0x0F,
            0x05, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0xE9, 0xFF,
        )
        // Proc 1: accel → fuser
        replies.setProcResponse(
            1,
            0x03, 0x04, 0xFF, 0xA0, 0x1B,
            0x01, 0x00, 0x01, 0x02, 0x03, 0x04,
            0x00, 0x00, 0x00, 0x00, 0x00, 0xE9, 0xFF,
        )

        val signals = device.createAnonymousDataSignals()
        job.cancel()

        assertEquals(1, signals.size)
        assertEquals("acceleration:fuser?id=1", signals.first().identifier)
    }

    // ---- Tranche D: TestTimeout ----

    @Test
    fun `logger query timeout returns empty`() = runTest {
        // No scripted logger/processor responses at all — every query times
        // out. Timeout in queryActiveLoggers stops iteration and returns an
        // empty list, so createAnonymousDataSignals returns []. (The Python
        // test asserts the C-style STATUS_ERROR_TIMEOUT sentinel, which this
        // API doesn't surface — empty list is the idiomatic equivalent since
        // nothing was recovered.)
        val (device, _, job) = anonSetup()
        val signals = device.createAnonymousDataSignals()
        job.cancel()
        assertTrue(signals.isEmpty())
    }
}
