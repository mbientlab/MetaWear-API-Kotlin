package com.mbientlab.metawear

import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Loggable
import com.mbientlab.metawear.protocol.PacketParser
import com.mbientlab.metawear.sensor.AccelerometerBmi160
import com.mbientlab.metawear.sensor.GyroscopeBmi160
import com.mbientlab.metawear.transport.MockBleTransport
import com.mbientlab.metawear.transport.Uuids
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Ported from MWLoggingTests.swift: startLogging commands, RawLogEntry
// parsing, log chunk configuration, log sample decode, flushLogPage, and
// clearLog. The download suites at the bottom cover the raw + typed
// `downloadLogs` pipeline the Swift file exercises only against hardware.

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LoggingTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    /** Connect against the stub board (logging module absent, like the Swift helper). */
    private suspend fun TestScope.connectedDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice(mac, transport, backgroundScope)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport)
        device.connect()
        discovery.cancel()
        return device to transport
    }

    /**
     * Connect like [connectedDevice] but override the logging module's
     * discovery response so `modules[LOGGING].revision == rev` and
     * `isPresent == true`.
     */
    private suspend fun TestScope.connectedDevice(loggingRevision: Int): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice(mac, transport, backgroundScope)
        val discovery = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size < 2) return@autoReply null
            val regByte = cmd[1].toInt() and 0xFF
            if (regByte and 0x80 == 0) return@autoReply null
            val moduleId = cmd[0].toInt() and 0xFF
            when {
                regByte == 0x80 && moduleId == 0x0B ->
                    // Logging: present with the requested revision
                    bytes(0x0B, 0x80, 0x00, loggingRevision)
                regByte == 0x80 -> {
                    val impl = if (moduleId in DEFAULT_PRESENT_MODULES) 0x01 else 0xFF
                    bytes(moduleId, 0x80, impl, 0x00)
                }
                else -> bytes(moduleId, regByte, 0x00, 0x00, 0x00, 0x00)
            }
        }
        device.connect()
        discovery.cancel()
        return device to transport
    }

    /**
     * Reply to logger-subscribe commands (`[0x0B, 0x02, ...]`) with sequential
     * logger IDs. Firmware response shape: `[0x0B, 0x02, logger_id]` — a plain
     * notification with bit-7 CLEAR, not a read response. (The Swift fixture
     * once used `0x82` here, which only worked because the SDK was awaiting the
     * wrong waiter type — both bugs cancelled out in unit tests but timed out
     * against real hardware.)
     */
    private fun CoroutineScope.autoReplyLoggerSubscriptions(
        transport: MockBleTransport,
        loggerIDs: List<Int>,
    ): Job {
        fun isSubscribe(cmd: ByteArray) =
            cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x0B && (cmd[1].toInt() and 0xFF) == 0x02
        // autoReply is index-based from command #0, so a helper started for a
        // second sensor would otherwise replay the first sensor's subscribe
        // commands and burn the fresh logger IDs on them.
        var staleToSkip = transport.writtenCommands.count(::isSubscribe)
        val remaining = ArrayDeque(loggerIDs)
        return autoReply(transport) { cmd ->
            when {
                !isSubscribe(cmd) -> null
                staleToSkip > 0 -> {
                    staleToSkip--
                    null
                }
                else -> remaining.removeFirstOrNull()?.let { bytes(0x0B, 0x02, it) }
            }
        }
    }

    /** Run startLogging while injecting the logger-ID responses it needs. */
    private suspend fun <S> TestScope.startLoggingWithReplies(
        loggable: Loggable<S>,
        device: MetaWearDevice,
        transport: MockBleTransport,
        loggerIDs: List<Int> = listOf(0x00, 0x01),
    ) {
        val injector = backgroundScope.autoReplyLoggerSubscriptions(transport, loggerIDs)
        try {
            device.startLogging(loggable)
        } finally {
            injector.cancel()
        }
    }

    // ---- startLogging commands ----

    @Test
    fun `startLogging sends enable logging command`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        startLoggingWithReplies(sensor, device, transport)

        assertTrue(
            transport.writtenCommands.any { it.contentEquals(bytes(0x0B, 0x01, 0x01)) },
            "startLogging must send [0x0B, 0x01, 0x01]",
        )
    }

    @Test
    fun `startLogging sends circular buffer command`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        startLoggingWithReplies(sensor, device, transport)

        assertTrue(
            transport.writtenCommands.any { it.contentEquals(bytes(0x0B, 0x0B, 0x01)) },
            "startLogging must send circular buffer enable [0x0B, 0x0B, 0x01]",
        )
    }

    @Test
    fun `startLogging subscribes each chunk`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        startLoggingWithReplies(sensor, device, transport)

        // Logger subscribe commands: [0x0B, 0x02, module, register, 0xFF, packed_byte]
        val subscriptions = transport.writtenCommands.filter {
            it.size >= 2 && (it[0].toInt() and 0xFF) == 0x0B && (it[1].toInt() and 0xFF) == 0x02
        }
        // Accelerometer has 2 chunks → 2 subscribe commands
        assertEquals(2, subscriptions.size)
    }

    /**
     * Regression test: the packed byte the SDK puts on the wire must match the
     * C++ encoding `((length - 1) << 5) | offset` (datasignal.cpp:162,
     * logging.cpp:868). An earlier version of the Swift SDK had the bit-fields
     * swapped, which produced wrong packing the firmware silently accepted but
     * couldn't reassemble. Lock the correct shape in.
     */
    @Test
    fun `startLogging packed byte on wire matches cpp encoding`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        startLoggingWithReplies(sensor, device, transport)

        // Subscribe wire shape: [0x0B, 0x02, module, register, 0xFF, packed]
        val accelSubs = transport.writtenCommands.filter {
            it.size == 6 &&
                (it[0].toInt() and 0xFF) == 0x0B && (it[1].toInt() and 0xFF) == 0x02 &&
                (it[2].toInt() and 0xFF) == 0x03 && (it[4].toInt() and 0xFF) == 0xFF
        }
        assertEquals(2, accelSubs.size, "Expected exactly 2 subscribe commands for 2 accel chunks")

        // The two accel chunks are (offset=0, length=4) and (offset=4, length=2).
        // Subscriptions are issued in declaration order, so byte 5 of each:
        //   Chunk 0 → ((4-1) << 5) | 0 = 0x60
        //   Chunk 1 → ((2-1) << 5) | 4 = 0x24
        val packedBytes = accelSubs.map { it[5].toInt() and 0xFF }
        assertTrue(0x60 in packedBytes, "Missing packed byte 0x60 (chunk 0,4)")
        assertTrue(0x24 in packedBytes, "Missing packed byte 0x24 (chunk 4,2)")
        // Lock out the legacy buggy values.
        assertFalse(0x03 in packedBytes, "0x03 is the legacy buggy chunk-0 packing")
        assertFalse(0x81 in packedBytes, "0x81 is the legacy buggy chunk-1 packing")
    }

    @Test
    fun `startLogging transitions to logging`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        startLoggingWithReplies(sensor, device, transport)

        assertEquals(DeviceState.Logging, device.state.value)
    }

    @Test
    fun `stopLogging sends stop command and returns to idle`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        startLoggingWithReplies(sensor, device, transport)

        device.stopLogging(sensor)

        assertTrue(
            transport.writtenCommands.any { it.contentEquals(bytes(0x0B, 0x01, 0x00)) },
            "stopLogging must send [0x0B, 0x01, 0x00]",
        )
        assertEquals(DeviceState.Idle, device.state.value)
    }

    @Test
    fun `startLogging same signal twice throws`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        startLoggingWithReplies(sensor, device, transport)

        // Already logging this signal — second call should throw
        val error = runCatching {
            startLoggingWithReplies(sensor, device, transport)
        }.exceptionOrNull()
        assertTrue(error is MetaWearException.InvalidState)
    }

    /**
     * Verifies the documented "multi-sensor stacking" design: two distinct
     * sensors can be logged in the same session by calling `startLogging` once
     * per sensor while the device is already `Logging`. The duplicate guard
     * rejects same-signal stacking (covered above); this confirms
     * different-signal stacking is accepted and that both sensors'
     * subscriptions + start commands actually reach the wire.
     */
    @Test
    fun `startLogging two distinct sensors both subscribe`() = runTest {
        val (device, transport) = connectedDevice()
        val accel = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        val gyro = GyroscopeBmi160(GyroscopeBmi160.Odr.HZ100, GyroscopeBmi160.Range.DPS2000)

        startLoggingWithReplies(accel, device, transport, loggerIDs = listOf(0x00, 0x01))
        startLoggingWithReplies(gyro, device, transport, loggerIDs = listOf(0x02, 0x03))

        val cmds = transport.writtenCommands

        // Subscription wire shape: [0x0B, 0x02, sensor_module, data_register, 0xFF, packed].
        // Each sensor splits its 6-byte XYZ payload into two chunks (default
        // Loggable.logDataChunks: [(0,4), (4,2)]) → 2 subscriptions per sensor.
        val accelSubs = cmds.filter {
            it.size >= 6 && (it[0].toInt() and 0xFF) == 0x0B && (it[1].toInt() and 0xFF) == 0x02 &&
                (it[2].toInt() and 0xFF) == 0x03
        }
        val gyroSubs = cmds.filter {
            it.size >= 6 && (it[0].toInt() and 0xFF) == 0x0B && (it[1].toInt() and 0xFF) == 0x02 &&
                (it[2].toInt() and 0xFF) == 0x13
        }
        assertEquals(2, accelSubs.size, "expected 2 accelerometer chunk subscriptions")
        assertEquals(2, gyroSubs.size, "expected 2 gyroscope chunk subscriptions")

        // Both sensors must be hardware-started (proves the call advanced past
        // the subscription stage on each invocation, not just the first).
        assertTrue(
            cmds.any { it.contentEquals(bytes(0x03, 0x01, 0x01)) },
            "accelerometer start command must reach the wire",
        )
        assertTrue(
            cmds.any { it.contentEquals(bytes(0x13, 0x01, 0x01)) },
            "gyroscope start command must reach the wire",
        )

        assertEquals(DeviceState.Logging, device.state.value)
    }

    // ---- RawLogEntry parsing ----

    @Test
    fun `parseAll single entry`() {
        // 9-byte entry layout: [id, tick(4 LE), data(4 LE)].
        // Header [0x0B, 0x07] + entry: id=0x05, tick=1(4 LE), data=0x3412CDAB
        val notification = bytes(
            0x0B, 0x07,
            0x05, 0x01, 0x00, 0x00, 0x00,
            0xAB, 0xCD, 0x12, 0x34,
        )
        assertEquals(11, notification.size)
        val entries = RawLogEntry.parseAll(notification)
        assertEquals(1, entries.size)
        assertEquals(0x05 and 0x1F, entries[0].id)
        assertEquals(1L, entries[0].tick)
        assertEquals(0x3412CDABL, entries[0].rawData)
    }

    @Test
    fun `parseAll double entry`() {
        val notification = bytes(0x0B, 0x07) +
            // Entry 1: id=0x00, tick=1 (4 LE), rawData=0x11223344
            bytes(0x00, 0x01, 0x00, 0x00, 0x00, 0x44, 0x33, 0x22, 0x11) +
            // Entry 2: id=0x01, tick=2 (4 LE), rawData=0x55667788
            bytes(0x01, 0x02, 0x00, 0x00, 0x00, 0x88, 0x77, 0x66, 0x55)
        assertEquals(20, notification.size)

        val entries = RawLogEntry.parseAll(notification)
        assertEquals(2, entries.size)
        assertEquals(0x00, entries[0].id)
        assertEquals(1L, entries[0].tick)
        assertEquals(0x11223344L, entries[0].rawData)
        assertEquals(0x01, entries[1].id)
        assertEquals(2L, entries[1].tick)
        assertEquals(0x55667788L, entries[1].rawData)
    }

    @Test
    fun `parseAll too short returns empty`() {
        // Header-only (2 bytes) — below the 9-byte entry threshold.
        val entries = RawLogEntry.parseAll(bytes(0x0B, 0x07))
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `parseAll epochMs matches tick`() {
        // Entry: id=0x00, tick=2 (4 LE), data=0
        val notification = bytes(
            0x0B, 0x07,
            0x00, 0x02, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val entries = RawLogEntry.parseAll(notification)
        val expected = 2.0 * PacketParser.MS_PER_TICK
        assertTrue(abs(entries[0].epochMs - expected) < 0.001)
    }

    @Test
    fun `parseAll real hardware notification`() {
        // Captured from a 1 Hz throttled euler download: paired entries at
        // tick 0x003B9F53 with logger ids 0xC0 and 0xC1 (id=0/1, resetUID=6).
        val notification = bytes(
            0x0B, 0x07,
            0xC0, 0x53, 0x9F, 0x3B, 0x00, 0xA4, 0xFD, 0xB3, 0x43,
            0xC1, 0x53, 0x9F, 0x3B, 0x00, 0x2E, 0x43, 0x68, 0x41,
        )
        assertEquals(20, notification.size)
        val entries = RawLogEntry.parseAll(notification)
        assertEquals(2, entries.size)
        assertEquals(0x00, entries[0].id)
        assertEquals(0x06, entries[0].resetUID)
        assertEquals(0x003B_9F53L, entries[0].tick)
        assertEquals(0x01, entries[1].id)
        assertEquals(0x06, entries[1].resetUID)
        assertEquals(entries[0].tick, entries[1].tick)
    }

    // ---- Log chunk configuration ----

    @Test
    fun `accelerometer has two chunks`() {
        val chunks = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2).logDataChunks
        assertEquals(2, chunks.size)
        assertEquals(0, chunks[0].offset)
        assertEquals(4, chunks[0].length)
        assertEquals(4, chunks[1].offset)
        assertEquals(2, chunks[1].length)
    }

    @Test
    fun `gyroscope has two chunks`() {
        val chunks = GyroscopeBmi160(GyroscopeBmi160.Odr.HZ100, GyroscopeBmi160.Range.DPS2000).logDataChunks
        assertEquals(2, chunks.size)
        assertEquals(4, chunks[0].length)
        assertEquals(2, chunks[1].length)
    }

    @Test
    fun `accel chunk packed bytes are correct`() {
        val chunks = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2).logDataChunks
        // C++ packing: ((length - 1) << 5) | offset
        //   datasignal.cpp:162 / logging.cpp:868 in metawear-cpp-sdk
        // Chunk 0: ((length=4 - 1) << 5) | offset=0 = 0x60
        assertEquals(0x60, ((chunks[0].length - 1) shl 5) or chunks[0].offset)
        // Chunk 1: ((length=2 - 1) << 5) | offset=4 = 0x24
        assertEquals(0x24, ((chunks[1].length - 1) shl 5) or chunks[1].offset)
    }

    // ---- Log sample decode ----

    @Test
    fun `accel parseLogSample decodes XYZ`() {
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2) // scale = 16384
        // x=1g (16384=0x4000 LE), y=-1g (-16384=0xC000 LE), z=0.5g (8192=0x2000 LE)
        val data = bytes(0x00, 0x40, 0x00, 0xC0, 0x00, 0x20)
        val sample = sensor.parseLogSample(data)
        assertTrue(abs(sample.x - 1.0f) < 0.001f)
        assertTrue(abs(sample.y - (-1.0f)) < 0.001f)
        assertTrue(abs(sample.z - 0.5f) < 0.001f)
    }

    @Test
    fun `accel reassemble and decode`() {
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2) // scale = 16384
        // Two 9-byte log entries for the same sample at tick=1
        // (layout: 1 byte id + 4 byte tick LE + 4 byte data LE):
        // Chunk 0 (logger ID 0): x=1g (0x4000), y=0g (0x0000) → rawData=0x00004000
        // Chunk 1 (logger ID 1): z=0.5g (0x2000), padding=0x0000 → rawData=0x00002000
        val entries = listOf(
            RawLogEntry.fromEntryBytes(bytes(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00)),
            RawLogEntry.fromEntryBytes(bytes(0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00)),
        )

        // Reassemble manually
        val chunks = listOf(LoggerChunk(id = 0, byteCount = 4), LoggerChunk(id = 1, byteCount = 2))
        var assembled = ByteArray(0)
        for (chunk in chunks) {
            val entry = entries.first { it.id == chunk.id }
            assembled += PacketParser.le32(entry.rawData).copyOfRange(0, chunk.byteCount)
        }
        // assembled = [0x00, 0x40, 0x00, 0x00, 0x00, 0x20] = x=0x4000, y=0x0000, z=0x2000
        val sample = sensor.parseLogSample(assembled)
        assertTrue(abs(sample.x - 1.0f) < 0.001f)
        assertTrue(abs(sample.y) < 0.001f)
        assertTrue(abs(sample.z - 0.5f) < 0.001f)
    }

    // ---- flushLogPage ----

    // On MMS (logging revision == 3) flushLogPage sends [0x0B, 0x10, 0x01].
    @Test
    fun `flushLogPage sends command when MMS`() = runTest {
        val (device, transport) = connectedDevice(loggingRevision = 3)
        val before = transport.writtenCommands.size

        val didSend = device.flushLogPage()
        assertTrue(didSend)

        val after = transport.writtenCommands.drop(before)
        assertTrue(
            after.any { it.contentEquals(bytes(0x0B, 0x10, 0x01)) },
            "flushLogPage must send [0x0B, 0x10, 0x01] on MMS",
        )
    }

    // Revision ≥ 3 should also send (forward-compat with future MMS revisions).
    @Test
    fun `flushLogPage sends command when revision above 3`() = runTest {
        val (device, transport) = connectedDevice(loggingRevision = 5)
        val before = transport.writtenCommands.size

        val didSend = device.flushLogPage()
        assertTrue(didSend)

        val after = transport.writtenCommands.drop(before)
        assertTrue(after.any { it.contentEquals(bytes(0x0B, 0x10, 0x01)) })
    }

    // On MMRL / pre-MMS firmware, flushLogPage is a no-op (matches C++ behavior).
    @Test
    fun `flushLogPage no-op when not MMS`() = runTest {
        val (device, transport) = connectedDevice(loggingRevision = 2)
        val before = transport.writtenCommands.size

        val didSend = device.flushLogPage()
        assertFalse(didSend)

        val after = transport.writtenCommands.drop(before)
        assertTrue(
            after.none { it.size >= 2 && (it[0].toInt() and 0xFF) == 0x0B && (it[1].toInt() and 0xFF) == 0x10 },
            "flushLogPage must not write anything on non-MMS boards",
        )
    }

    // When the logging module isn't present at all (impl=0xFF) flushLogPage no-ops.
    @Test
    fun `flushLogPage no-op when logging absent`() = runTest {
        val (device, transport) = connectedDevice() // default: logging absent
        val before = transport.writtenCommands.size

        val didSend = device.flushLogPage()
        assertFalse(didSend)

        val after = transport.writtenCommands.drop(before)
        assertTrue(after.none { it.size >= 2 && (it[0].toInt() and 0xFF) == 0x0B && (it[1].toInt() and 0xFF) == 0x10 })
    }

    // ---- clearLog ----

    @Test
    fun `clearLog sends clear command`() = runTest {
        val (device, transport) = connectedDevice()
        device.clearLog()

        assertTrue(
            transport.writtenCommands.any { it.contentEquals(bytes(0x0B, 0x09, 0xFF, 0xFF, 0xFF, 0xFF)) },
            "clearLog must send [0x0B, 0x09, 0xFF, 0xFF, 0xFF, 0xFF]",
        )
    }

    @Test
    fun `clearLog when not idle throws`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        startLoggingWithReplies(sensor, device, transport)

        val error = runCatching { device.clearLog() }.exceptionOrNull()
        assertTrue(error is MetaWearException.InvalidState)
    }

    // ---- downloadLogs (raw) ----

    /** Reply to the LOG_LENGTH read with a fixed entry count. */
    private fun lengthReply(count: Int): ByteArray =
        bytes(0x0B, 0x85, count and 0xFF, (count shr 8) and 0xFF, (count shr 16) and 0xFF, (count shr 24) and 0xFF)

    @Test
    fun `downloadLogs with empty log yields single complete snapshot`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x0B && (cmd[1].toInt() and 0xFF) == 0x85) {
                lengthReply(0)
            } else {
                null
            }
        }

        val snapshots = device.downloadLogs().toList()
        replies.cancel()

        assertEquals(1, snapshots.size)
        assertEquals(1.0, snapshots[0].percentComplete)
        assertEquals(0L, snapshots[0].totalEntries)
        assertEquals(0L, snapshots[0].entriesDownloaded)
        assertTrue(snapshots[0].data.isEmpty())
        assertEquals(DeviceState.Idle, device.state.value)
    }

    @Test
    fun `downloadLogs streams raw entries and completes`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size < 2) return@autoReply null
            val module = cmd[0].toInt() and 0xFF
            val reg = cmd[1].toInt() and 0xFF
            when {
                module == 0x0B && reg == 0x85 -> lengthReply(2)
                module == 0x0B && reg == 0x06 -> {
                    // Readout command: deliver both raw entries in one paired
                    // notification, then the final remaining=0 progress notice.
                    transport.inject(
                        bytes(
                            0x0B, 0x07,
                            0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00,
                            0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00,
                        ),
                        Uuids.notify,
                    )
                    bytes(0x0B, 0x08, 0x00, 0x00, 0x00, 0x00)
                }
                else -> null
            }
        }

        val snapshots = device.downloadLogs().toList()
        replies.cancel()

        // First snapshot is the immediate 0% (with total entry count); the last
        // one carries every raw entry at 100%.
        assertTrue(snapshots.size >= 2)
        assertEquals(0.0, snapshots.first().percentComplete)
        assertEquals(2L, snapshots.first().totalEntries)
        val final = snapshots.last()
        assertEquals(1.0, final.percentComplete)
        assertEquals(2L, final.entriesDownloaded)
        assertEquals(2, final.data.size)
        assertEquals(0x00, final.data[0].id)
        assertEquals(0x01, final.data[1].id)
        assertEquals(DeviceState.Idle, device.state.value)
    }

    @Test
    fun `downloadLogs when not idle throws`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        startLoggingWithReplies(sensor, device, transport)

        val error = runCatching { device.downloadLogs() }.exceptionOrNull()
        assertTrue(error is MetaWearException.InvalidState)
        assertEquals("Invalid state: Device must be idle to download", error?.message)
    }

    @Test
    fun `downloadLogs cleans up subscriptions and state after empty download`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x0B && (cmd[1].toInt() and 0xFF) == 0x85) {
                lengthReply(0)
            } else {
                null
            }
        }
        val before = transport.writtenCommands.size
        device.downloadLogs().toList()
        replies.cancel()

        // Cleanup must disable the readout-notify (0x07), page-completed (0x0D),
        // and progress (0x08) channels it enabled.
        val after = transport.writtenCommands.drop(before)
        assertTrue(after.any { it.contentEquals(bytes(0x0B, 0x07, 0x00)) })
        assertTrue(after.any { it.contentEquals(bytes(0x0B, 0x0D, 0x00)) })
        assertTrue(after.any { it.contentEquals(bytes(0x0B, 0x08, 0x00)) })
    }

    // ---- downloadLogs (typed) ----

    @Test
    fun `typed downloadLogs decodes accelerometer samples`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        startLoggingWithReplies(sensor, device, transport, loggerIDs = listOf(0x00, 0x01))
        device.stopLogging(sensor)

        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size < 2) return@autoReply null
            val module = cmd[0].toInt() and 0xFF
            val reg = cmd[1].toInt() and 0xFF
            when {
                module == 0x0B && reg == 0x85 -> lengthReply(2)
                module == 0x0B && reg == 0x06 -> {
                    // Chunk 0 (id 0): x=1g (0x4000), y=0 → rawData=0x00004000
                    // Chunk 1 (id 1): z=0.5g (0x2000), pad → rawData=0x00002000
                    transport.inject(
                        bytes(
                            0x0B, 0x07,
                            0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00,
                            0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00,
                        ),
                        Uuids.notify,
                    )
                    bytes(0x0B, 0x08, 0x00, 0x00, 0x00, 0x00)
                }
                else -> null
            }
        }

        val snapshots = device.downloadLogs(sensor).toList()
        replies.cancel()

        val samples = snapshots.last().data
        assertEquals(1, samples.size)
        assertEquals(1.0f, samples[0].value.x)
        assertEquals(0.0f, samples[0].value.y)
        assertEquals(0.5f, samples[0].value.z)
        assertTrue(abs(samples[0].tickMs - PacketParser.MS_PER_TICK) < 0.001)
        assertEquals(DeviceState.Idle, device.state.value)
    }

    @Test
    fun `typed downloadLogs without registration throws`() = runTest {
        val (device, _) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)

        val error = runCatching { device.downloadLogs(sensor) }.exceptionOrNull()
        assertTrue(error is MetaWearException.InvalidState)
    }

    @Test
    fun `decodeEntries without registration throws`() = runTest {
        val (device, _) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)

        val error = runCatching { device.decodeEntries(emptyList(), sensor) }.exceptionOrNull()
        assertTrue(error is MetaWearException.InvalidState)
    }

    /** Chunk-reassembly core: entries pair per logger-ID arrival order, sorted by tick. */
    @Test
    fun `decodeEntries core reassembles chunk pairs in arrival order`() = runTest {
        val (device, _) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)

        // Two samples interleaved: (id0, id1) at tick 2 then (id0, id1) at tick 1.
        val entries = listOf(
            RawLogEntry.fromEntryBytes(bytes(0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00)), // x=1g
            RawLogEntry.fromEntryBytes(bytes(0x01, 0x02, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00)), // z=0.5g
            RawLogEntry.fromEntryBytes(bytes(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0xC0, 0x00, 0x00)), // x=-1g
            RawLogEntry.fromEntryBytes(bytes(0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)), // z=0
        )
        val chunks = listOf(LoggerChunk(id = 0, byteCount = 4), LoggerChunk(id = 1, byteCount = 2))
        val samples = device.decodeEntries(entries, chunks) { sensor.parseLogSample(it) }

        assertEquals(2, samples.size)
        // Sorted by tick: the tick=1 sample (arrival-order pair #2) comes first.
        assertEquals(-1.0f, samples[0].value.x)
        assertEquals(0.0f, samples[0].value.z)
        assertEquals(1.0f, samples[1].value.x)
        assertEquals(0.5f, samples[1].value.z)
        assertTrue(samples[0].tickMs < samples[1].tickMs)
    }

    /** A trailing sample whose later chunks were cut off by the end of the download is dropped. */
    @Test
    fun `decodeEntries core drops incomplete trailing sample`() = runTest {
        val (device, _) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)

        val entries = listOf(
            RawLogEntry.fromEntryBytes(bytes(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00)),
            RawLogEntry.fromEntryBytes(bytes(0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00)),
            // Second sample: only chunk 0 arrived before the download ended.
            RawLogEntry.fromEntryBytes(bytes(0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00)),
        )
        val chunks = listOf(LoggerChunk(id = 0, byteCount = 4), LoggerChunk(id = 1, byteCount = 2))
        val samples = device.decodeEntries(entries, chunks) { sensor.parseLogSample(it) }

        assertEquals(1, samples.size)
    }
}
