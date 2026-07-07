package com.mbientlab.metawear

import com.mbientlab.metawear.model.ActiveLogger
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.PolledLogger
import com.mbientlab.metawear.sensor.Humidity
import com.mbientlab.metawear.sensor.Thermometer
import com.mbientlab.metawear.transport.MockBleTransport
import com.mbientlab.metawear.transport.Uuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Polled-logging coverage: the timer → event → logger
// chain built by `startLogging(PolledLogger)`, its teardown, the
// PolledLoggable conformances (Thermometer, Humidity), and polled-logger
// recovery + download. This surface is otherwise only verified against
// hardware; these tests lock the documented wire protocol in.

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PolledLoggingTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    private suspend fun TestScope.connectedDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice(mac, transport, backgroundScope)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport)
        device.connect()
        discovery.cancel()
        return device to transport
    }

    /**
     * Reply to the three create-resource commands a polled-logger setup
     * issues: timer create (`[0x0C, 0x02, …]` → id), event create
     * (`[0x0A, 0x02, …]` → id), and logger subscribe (`[0x0B, 0x02, …]` → id).
     * All three reply as plain notifications (bit-7 clear).
     */
    private fun CoroutineScope.autoReplyPolledSetup(
        transport: MockBleTransport,
        timerId: Int,
        eventId: Int,
        loggerIDs: List<Int>,
    ): Job {
        val remaining = ArrayDeque(loggerIDs)
        return autoReply(transport) { cmd ->
            if (cmd.size < 2) return@autoReply null
            val module = cmd[0].toInt() and 0xFF
            val reg = cmd[1].toInt() and 0xFF
            when {
                module == 0x0C && reg == 0x02 -> bytes(0x0C, 0x02, timerId)
                module == 0x0A && reg == 0x02 -> bytes(0x0A, 0x02, eventId)
                module == 0x0B && reg == 0x02 -> remaining.removeFirstOrNull()?.let { bytes(0x0B, 0x02, it) }
                else -> null
            }
        }
    }

    // ---- startLogging(PolledLogger) ----

    @Test
    fun `polled startLogging builds the timer event logger chain in order`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()
        val replies = backgroundScope.autoReplyPolledSetup(transport, timerId = 3, eventId = 5, loggerIDs = listOf(2))

        val logger = PolledLogger(Thermometer(channel = 1), periodMs = 1000)
        val handles = device.startLogging(logger)
        replies.cancel()

        assertEquals(3, handles.timerID)
        assertEquals(5, handles.eventID)
        assertEquals(listOf(2), handles.loggerIDs)

        val cmds = transport.writtenCommands
        // 1. Timer create: [0x0C, 0x02, period(4 LE)=1000, reps=0xFFFF, immediate=0]
        assertArrayEquals(bytes(0x0C, 0x02, 0xE8, 0x03, 0x00, 0x00, 0xFF, 0xFF, 0x00), cmds[0])
        // 2. Event entry: timer 3 fired → SILENT temperature read (0x81|0x40 =
        //    0xC1) with the channel byte as its one parameter.
        assertArrayEquals(bytes(0x0A, 0x02, 0x0C, 0x06, 0x03, 0x04, 0xC1, 0x01), cmds[1])
        //    …followed by the event parameter block (the channel byte).
        assertArrayEquals(bytes(0x0A, 0x03, 0x01), cmds[2])
        // 3. Logger subscribe names the FULL trigger register (0xC1) and the
        //    channel index, packed = ((2-1) << 5) | 0 = 0x20.
        assertArrayEquals(bytes(0x0B, 0x02, 0x04, 0xC1, 0x01, 0x20), cmds[3])
        // 4. Circular buffer + logging enable, then the timer starts last so
        //    the first read fires into a ready logger.
        assertArrayEquals(bytes(0x0B, 0x0B, 0x01), cmds[4])
        assertArrayEquals(bytes(0x0B, 0x01, 0x01), cmds[5])
        assertArrayEquals(bytes(0x0C, 0x03, 0x03), cmds[6])
        assertEquals(7, cmds.size)

        assertEquals(DeviceState.Logging, device.state.value)
    }

    @Test
    fun `polled startLogging registers the synthetic logger key`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReplyPolledSetup(transport, timerId = 0, eventId = 0, loggerIDs = listOf(4))

        val logger = PolledLogger(Thermometer(channel = 1), periodMs = 500)
        assertEquals("polled-04-01", logger.loggerKey)

        device.startLogging(logger)
        replies.cancel()

        assertEquals(listOf(LoggerChunk(id = 4, byteCount = 2)), device.loggerRegistry["polled-04-01"])
    }

    @Test
    fun `polled startLogging duplicate key throws`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReplyPolledSetup(transport, timerId = 0, eventId = 0, loggerIDs = listOf(0, 1))

        device.startLogging(PolledLogger(Thermometer(channel = 1), periodMs = 500))
        val error = runCatching {
            device.startLogging(PolledLogger(Thermometer(channel = 1), periodMs = 250))
        }.exceptionOrNull()
        replies.cancel()

        assertTrue(error is MetaWearException.InvalidState)
        assertEquals("Invalid state: polled-04-01 is already being logged", error?.message)
    }

    @Test
    fun `polled stopLogging tears down timer event and logging`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReplyPolledSetup(transport, timerId = 3, eventId = 5, loggerIDs = listOf(2))
        val logger = PolledLogger(Thermometer(channel = 1), periodMs = 1000)
        val handles = device.startLogging(logger)
        replies.cancel()

        transport.clearWrites()
        device.stopLogging(logger, handles)

        val cmds = transport.writtenCommands
        assertArrayEquals(bytes(0x0C, 0x04, 0x03), cmds[0]) // stop timer
        assertArrayEquals(bytes(0x0C, 0x05, 0x03), cmds[1]) // remove timer
        assertArrayEquals(bytes(0x0A, 0x04, 0x05), cmds[2]) // remove event
        assertArrayEquals(bytes(0x0B, 0x01, 0x00), cmds[3]) // stop logging
        assertEquals(DeviceState.Idle, device.state.value)

        // Registry survives so the flash entries can still be downloaded.
        assertTrue(device.loggerRegistry.containsKey(logger.loggerKey))
    }

    // ---- PolledLoggable conformances ----

    @Test
    fun `humidity logs one 4-byte chunk with default trigger`() {
        val humidity = Humidity()
        assertEquals(1, humidity.logDataChunks.size)
        assertEquals(0, humidity.logDataChunks[0].offset)
        assertEquals(4, humidity.logDataChunks[0].length)
        assertEquals(0xFF, humidity.loggerTriggerIndex)
        assertEquals(0xC1, humidity.loggerTriggerRegister)
    }

    @Test
    fun `humidity parseLogSample decodes via synthetic header`() {
        // Reassembled payload: raw 64711 → 64711 / 1024 %
        val sample = Humidity().parseLogSample(bytes(0xC7, 0xFC, 0x00, 0x00))
        assertEquals(64711.0f / 1024.0f, sample)
    }

    @Test
    fun `thermometer logs one 2-byte chunk keyed to its channel`() {
        val thermometer = Thermometer(channel = 2)
        assertEquals(1, thermometer.logDataChunks.size)
        assertEquals(0, thermometer.logDataChunks[0].offset)
        assertEquals(2, thermometer.logDataChunks[0].length)
        assertEquals(2, thermometer.loggerTriggerIndex)
        assertEquals(0xC1, thermometer.loggerTriggerRegister)
    }

    @Test
    fun `thermometer parseLogSample decodes bare int16`() {
        // 168 / 8 = 21 °C — no channel byte in the logged payload.
        assertEquals(21.0f, Thermometer(channel = 1).parseLogSample(bytes(0xA8, 0x00)))
    }

    @Test
    fun `thermometer parseLogSample too short throws`() {
        val error = runCatching { Thermometer(channel = 1).parseLogSample(bytes(0xA8)) }.exceptionOrNull()
        assertTrue(error is MetaWearException.OperationFailed)
        assertEquals("Operation failed: Temperature log chunk too short: 1 bytes", error?.message)
    }

    // ---- Recovery ----

    @Test
    fun `polled recoverLoggers matches full register byte and channel`() = runTest {
        val (device, _) = connectedDevice()
        val logger = PolledLogger(Thermometer(channel = 1), periodMs = 500)

        val active = listOf(
            // Wrong channel — must be skipped.
            ActiveLogger(loggerID = 2, module = Module.TEMPERATURE, register = 0xC1, channel = 0, chunkOffset = 0, chunkLength = 2),
            // Match: the board echoes the trigger's full register byte (0xC1),
            // which normalizes to data register 0x01.
            ActiveLogger(loggerID = 4, module = Module.TEMPERATURE, register = 0xC1, channel = 1, chunkOffset = 0, chunkLength = 2),
        )
        device.recoverLoggers(logger, active)

        assertEquals(listOf(LoggerChunk(id = 4, byteCount = 2)), device.loggerRegistry[logger.loggerKey])
    }

    @Test
    fun `polled recoverLoggers throws when no channel matches`() = runTest {
        val (device, _) = connectedDevice()
        val logger = PolledLogger(Thermometer(channel = 1), periodMs = 500)

        val active = listOf(
            ActiveLogger(loggerID = 2, module = Module.TEMPERATURE, register = 0xC1, channel = 0, chunkOffset = 0, chunkLength = 2),
        )
        val error = runCatching { device.recoverLoggers(logger, active) }.exceptionOrNull()
        assertTrue(error is MetaWearException.OperationFailed)
    }

    // ---- Download ----

    @Test
    fun `polled downloadLogs decodes temperature samples`() = runTest {
        val (device, transport) = connectedDevice()
        val setupReplies = backgroundScope.autoReplyPolledSetup(transport, timerId = 3, eventId = 5, loggerIDs = listOf(2))
        val logger = PolledLogger(Thermometer(channel = 1), periodMs = 1000)
        val handles = device.startLogging(logger)
        setupReplies.cancel()
        device.stopLogging(logger, handles)

        val downloadReplies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size < 2) return@autoReply null
            val module = cmd[0].toInt() and 0xFF
            val reg = cmd[1].toInt() and 0xFF
            when {
                module == 0x0B && reg == 0x85 -> bytes(0x0B, 0x85, 0x01, 0x00, 0x00, 0x00)
                module == 0x0B && reg == 0x06 -> {
                    // One raw entry from logger id 2: value 168 (21 °C) at tick 1.
                    transport.inject(
                        bytes(0x0B, 0x07, 0x02, 0x01, 0x00, 0x00, 0x00, 0xA8, 0x00, 0x00, 0x00),
                        Uuids.notify,
                    )
                    bytes(0x0B, 0x08, 0x00, 0x00, 0x00, 0x00)
                }
                else -> null
            }
        }

        val snapshots = device.downloadLogs(logger).toList()
        downloadReplies.cancel()

        val samples = snapshots.last().data
        assertEquals(1, samples.size)
        assertEquals(21.0f, samples[0].value)
        assertEquals(DeviceState.Idle, device.state.value)
    }
}
