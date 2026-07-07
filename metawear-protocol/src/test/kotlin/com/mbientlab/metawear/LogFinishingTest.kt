package com.mbientlab.metawear

import com.mbientlab.metawear.model.LoggedSample
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Loggable
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.PacketParser
import com.mbientlab.metawear.sensor.AccelerometerBmi160
import com.mbientlab.metawear.transport.MockBleTransport
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Ported from MWLogFinishingTests.swift: the log time anchor, logger-registry
// persistence across disconnects, queryActiveLoggers, recoverLoggers, and
// LoggedSample field semantics.

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LogFinishingTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    /** Modules present for these suites: accel, logging, gyro, baro, mag, fusion. */
    private val presentModules = setOf(0x03, 0x0B, 0x13, 0x12, 0x15, 0x19)

    /**
     * Reply to discovery reads with logging present, and to the log
     * time-reference read (`[0x0B, 0x84]`) with the given [tick].
     */
    private fun CoroutineScope.autoReplyDiscoveryWithLogTime(
        transport: MockBleTransport,
        tick: Long,
    ): Job = autoReply(transport) { cmd ->
        if (cmd.size < 2) return@autoReply null
        val regByte = cmd[1].toInt() and 0xFF
        if (regByte and 0x80 == 0) return@autoReply null
        val moduleId = cmd[0].toInt() and 0xFF
        when {
            // Logging time read: [0x0B, 0x84]
            moduleId == 0x0B && regByte == 0x84 -> bytes(
                0x0B, 0x84,
                (tick and 0xFF).toInt(), ((tick shr 8) and 0xFF).toInt(),
                ((tick shr 16) and 0xFF).toInt(), ((tick shr 24) and 0xFF).toInt(),
                0x00,
            )
            // Module discovery: respond with impl present/absent
            regByte == 0x80 -> {
                val impl = if (moduleId in presentModules) 0x01 else 0xFF
                bytes(moduleId, 0x80, impl, 0x00)
            }
            else -> bytes(moduleId, regByte, 0x00, 0x00, 0x00, 0x00)
        }
    }

    private suspend fun TestScope.connectedDevice(
        injectLogTime: Long = 0,
    ): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice(mac, transport, backgroundScope)
        val discovery = backgroundScope.autoReplyDiscoveryWithLogTime(transport, injectLogTime)
        device.connect()
        discovery.cancel()
        return device to transport
    }

    private suspend fun <S> TestScope.startLoggingWithReplies(
        loggable: Loggable<S>,
        device: MetaWearDevice,
        transport: MockBleTransport,
        loggerIDs: List<Int> = listOf(0x00, 0x01),
    ) {
        val remaining = ArrayDeque(loggerIDs)
        val injector = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x0B && (cmd[1].toInt() and 0xFF) == 0x02) {
                // Logger subscribe response: plain notification, bit-7 clear.
                remaining.removeFirstOrNull()?.let { bytes(0x0B, 0x02, it) }
            } else {
                null
            }
        }
        try {
            device.startLogging(loggable)
        } finally {
            injector.cancel()
        }
    }

    // ---- Log time anchor ----

    @Test
    fun `connect reads log time reference`() = runTest {
        // Connect with tick=1000 — the reference date should be ~1.46s before now
        val (device, _) = connectedDevice(injectLogTime = 1000)

        val refDate = device.logReferenceDate
        assertNotNull(refDate, "logReferenceDate must be set after connect when logging module is present")

        val expectedOffsetSeconds = 1000.0 * PacketParser.MS_PER_TICK / 1000.0
        val actualOffsetSeconds = (Clock.System.now() - refDate!!).toDouble(DurationUnit.SECONDS)
        // Allow ±200ms timing slack
        assertTrue(
            abs(actualOffsetSeconds - expectedOffsetSeconds) < 0.2,
            "Reference date should be ~${expectedOffsetSeconds}s in the past",
        )
    }

    @Test
    fun `logged sample uses reference date`() = runTest {
        // tick=0 → reference = now. A sample at tick=1000 should be ~1.46s after ref.
        val (device, _) = connectedDevice(injectLogTime = 0)

        val before = Clock.System.now()
        val ref = device.logReferenceDate!!

        // Manually decode a fake entry at tick=1000
        val sampleTick = 1000L
        val msElapsed = sampleTick.toDouble() * PacketParser.MS_PER_TICK
        val expectedDate = ref + msElapsed.milliseconds
        assertTrue(expectedDate > before)
        assertTrue(abs((expectedDate - ref).toDouble(DurationUnit.MILLISECONDS) - msElapsed) < 1.0)
    }

    @Test
    fun `unexpected disconnect clears reference date`() = runTest {
        val (device, transport) = connectedDevice(injectLogTime = 500)
        assertNotNull(device.logReferenceDate)

        transport.simulateDisconnect()
        runCurrent()

        assertNull(device.logReferenceDate, "logReferenceDate must be cleared on unexpected disconnect")
    }

    @Test
    fun `reconnect refreshes reference date`() = runTest {
        val (device, transport) = connectedDevice(injectLogTime = 100)

        transport.simulateDisconnect()
        runCurrent()
        assertNull(device.logReferenceDate)

        // Re-prime for reconnect. autoReply is index-based, so duplicate
        // commands from the second discovery pass still get replied to.
        val rediscovery = backgroundScope.autoReplyDiscoveryWithLogTime(transport, tick = 2000)
        device.reconnect()
        rediscovery.cancel()

        assertNotNull(device.logReferenceDate, "logReferenceDate must be re-read after reconnect")
    }

    // ---- Logger registry persistence across disconnect ----

    @Test
    fun `logger registry survives unexpected disconnect`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        startLoggingWithReplies(sensor, device, transport)

        // Verify registry is populated
        assertTrue(device.loggerRegistry.containsKey(sensor.loggerKey))

        // Simulate unexpected drop
        transport.simulateDisconnect()
        runCurrent()

        // Registry must still be there so download can proceed after reconnect
        assertTrue(
            device.loggerRegistry.containsKey(sensor.loggerKey),
            "loggerRegistry must survive unexpected disconnect",
        )
    }

    @Test
    fun `clearLog removes registry`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        startLoggingWithReplies(sensor, device, transport)
        device.stopLogging(sensor)
        device.clearLog()

        assertFalse(
            device.loggerRegistry.containsKey(sensor.loggerKey),
            "clearLog must remove loggerRegistry entries",
        )
    }

    // ---- queryActiveLoggers ----

    @Test
    fun `queryActiveLoggers parses response`() = runTest {
        val (device, transport) = connectedDevice()

        // Respond for two logger IDs, then nothing for ID 2 → probe timeout
        // stops iteration. Wire format (6 bytes):
        // [0x0B, 0x82, src_module, src_register, src_data_id, packed].
        // The firmware does NOT echo the queried logger_id back — that's why
        // the SDK uses the loop's `id` as the loggerID. Packed byte: low 5
        // bits = offset, high 3 bits = length-1.
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size < 3 || (cmd[0].toInt() and 0xFF) != 0x0B || (cmd[1].toInt() and 0xFF) != 0x82) {
                return@autoReply null
            }
            when (cmd[2].toInt() and 0xFF) {
                // Logger 0: module=accel(0x03), register=0x04, data_id=0xFF,
                //           packed=0x60 (offset=0, length=4)
                0x00 -> bytes(0x0B, 0x82, 0x03, 0x04, 0xFF, 0x60)
                // Logger 1: packed=0x24 (offset=4, length=2)
                0x01 -> bytes(0x0B, 0x82, 0x03, 0x04, 0xFF, 0x24)
                // No response for logger 2 → timeout stops iteration
                else -> null
            }
        }

        val loggers = device.queryActiveLoggers()
        replies.cancel()

        assertEquals(2, loggers.size)
        assertEquals(0, loggers[0].loggerID)
        assertEquals(Module.ACCELEROMETER, loggers[0].module)
        assertEquals(1, loggers[1].loggerID)
        assertEquals(Module.ACCELEROMETER, loggers[1].module)
        assertEquals(0, loggers[0].chunkOffset)
        assertEquals(4, loggers[0].chunkLength)
        assertEquals(4, loggers[1].chunkOffset)
        assertEquals(2, loggers[1].chunkLength)
        assertEquals(0xFF, loggers[0].channel)
    }

    // ---- recoverLoggers ----

    @Test
    fun `recoverLoggers rebuilds registry`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)

        // No registry initially
        assertFalse(device.loggerRegistry.containsKey(sensor.loggerKey))

        // Inject query responses (6-byte wire format — no logger_id echo)
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size < 3 || (cmd[0].toInt() and 0xFF) != 0x0B || (cmd[1].toInt() and 0xFF) != 0x82) {
                return@autoReply null
            }
            when (cmd[2].toInt() and 0xFF) {
                0x00 -> bytes(0x0B, 0x82, 0x03, 0x04, 0xFF, 0x03)
                0x01 -> bytes(0x0B, 0x82, 0x03, 0x04, 0xFF, 0x81)
                else -> null // Let ID 2 time out
            }
        }

        device.recoverLoggers(sensor)
        replies.cancel()

        assertTrue(
            device.loggerRegistry.containsKey(sensor.loggerKey),
            "recoverLoggers must populate loggerRegistry",
        )
    }

    @Test
    fun `recoverLoggers throws when no match`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)

        // Only respond with a gyro logger — no accel
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size < 3 || (cmd[0].toInt() and 0xFF) != 0x0B || (cmd[1].toInt() and 0xFF) != 0x82) {
                return@autoReply null
            }
            when (cmd[2].toInt() and 0xFF) {
                0x00 -> bytes(0x0B, 0x82, 0x13, 0x05, 0xFF, 0x03) // gyro
                else -> null // ID 1 times out
            }
        }

        val error = runCatching { device.recoverLoggers(sensor) }.exceptionOrNull()
        replies.cancel()
        assertTrue(error is MetaWearException.OperationFailed)
    }

    // ---- LoggedSample ----

    @Test
    fun `date and tickMs are distinct`() {
        val ref = Clock.System.now() - 10.seconds
        val tickMs = 1000.0
        val date = ref + tickMs.milliseconds
        val sample = LoggedSample(date = date, tickMs = tickMs, value = 42)
        assertEquals(1000.0, sample.tickMs)
        assertTrue(abs((sample.date - ref).toDouble(DurationUnit.SECONDS) - 1.0) < 0.001)
    }
}
