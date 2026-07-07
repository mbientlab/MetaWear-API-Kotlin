package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.autoReplyModuleDiscovery
import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.makeConnectableTransport
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.transport.MockBleTransport
import com.mbientlab.metawear.transport.Uuids
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Byte-identical stand-ins for LED commands (the LED module lives in a
// separate file); the event machinery only ever sees their commandData bytes.
private class EventStubCommand(private val data: ByteArray) : Command {
    override val commandData: ByteArray get() = data
}

/** LED play → `[0x02, 0x01, 0x01]`. */
private fun ledPlay(): Command = EventStubCommand(bytes(0x02, 0x01, 0x01))

/** LED stop, clearing the pattern → `[0x02, 0x02, 0x01]`. */
private fun ledStopClearing(): Command = EventStubCommand(bytes(0x02, 0x02, 0x01))

/** Event-module tests: record/end commands and action byte layouts. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent
class EventTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    private suspend fun TestScope.connectedDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice(mac, transport, backgroundScope)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport)
        device.connect()
        discovery.cancel()
        return device to transport
    }

    /** Create a timer injecting the board's plain-notification ID assignment. */
    private suspend fun TestScope.makeTimer(
        device: MetaWearDevice,
        transport: MockBleTransport,
        timerId: Int = 0,
    ): MetaWearTimer {
        val created = async { device.createTimer(periodMs = 1000) }
        runCurrent()
        // Real firmware replies with [0x0C, 0x02, timer_id] — plain notification,
        // NOT a read response. Mirror that here.
        transport.inject(bytes(0x0C, 0x02, timerId), Uuids.notify)
        runCurrent()
        return created.await()
    }

    /** Create an event injecting the board's assigned event ID. */
    private suspend fun TestScope.makeEvent(
        device: MetaWearDevice,
        transport: MockBleTransport,
        source: EventSource,
        action: EventAction,
        dataToken: EventDataToken? = null,
        boardEventId: Int = 0,
    ): Event {
        val created = async { device.createEvent(source, action, dataToken) }
        runCurrent()
        // Real firmware replies with [0x0A, 0x02, event_id] — plain notification,
        // NOT a read response. Mirror that here.
        transport.inject(bytes(0x0A, 0x02, boardEventId), Uuids.notify)
        runCurrent()
        return created.await()
    }

    /** The ENTRY command `[0x0A, 0x02, ...]` among the written commands. */
    private fun findEntryCommand(transport: MockBleTransport): ByteArray? =
        transport.writtenCommands.firstOrNull {
            it.size >= 2 && (it[0].toInt() and 0xFF) == 0x0A && (it[1].toInt() and 0xFF) == 0x02
        }

    /** The CMD_PARAMETERS command `[0x0A, 0x03, ...]` among the written commands. */
    private fun findParamsCommand(transport: MockBleTransport): ByteArray? =
        transport.writtenCommands.firstOrNull {
            it.size >= 2 && (it[0].toInt() and 0xFF) == 0x0A && (it[1].toInt() and 0xFF) == 0x03
        }

    // ---- EventSource convenience constructors ----

    @Test
    fun `timerFired source uses timer notify register and timer id`() {
        val timer = MetaWearTimer(id = 2, periodMs = 1000, repetitions = MetaWearTimer.INFINITE, immediate = false)
        val src = EventSource.timerFired(timer)
        assertEquals(Module.TIMER, src.module)
        assertEquals(0x06, src.register)   // NOTIFY register
        assertEquals(2, src.dataId)        // timer.id
    }

    @Test
    fun `buttonChanged source uses switch state register`() {
        val src = EventSource.buttonChanged()
        assertEquals(Module.SWITCH, src.module)
        assertEquals(0x01, src.register)
        assertEquals(0xFF, src.dataId)     // any instance
    }

    @Test
    fun `gpioChanged source uses pin change register and pin id`() {
        val src = EventSource.gpioChanged(pin = 3)
        assertEquals(Module.GPIO, src.module)
        assertEquals(0x0A, src.register)   // PIN_CHANGE_NOTIFY
        assertEquals(3, src.dataId)
    }

    // ---- EventAction from Command ----

    @Test
    fun `ledPlay action extracts module, register, params`() {
        val action = EventAction.from(ledPlay())
        assertEquals(Module.LED, action.module)
        assertEquals(0x01, action.register)
        assertArrayEquals(bytes(0x01), action.params)
    }

    @Test
    fun `ledStop action extracts module, register, params`() {
        val action = EventAction.from(ledStopClearing())
        assertEquals(Module.LED, action.module)
        assertEquals(0x02, action.register)
        assertArrayEquals(bytes(0x01), action.params)
    }

    @Test
    fun `gpioSetHigh action extracts module, register, params`() {
        val action = EventAction.from(Gpio.SetHigh(pin = 2))
        assertEquals(Module.GPIO, action.module)
        assertEquals(0x01, action.register)
        assertArrayEquals(bytes(0x02), action.params)
    }

    @Test
    fun `action with only module and register has empty params`() {
        val action = EventAction(Module.DEBUG, 0x01)
        assertTrue(action.params.isEmpty())
    }

    // ---- createEvent command format ----

    @Test
    fun `createEvent sends the ENTRY command`() = runTest {
        val (device, transport) = connectedDevice()
        val timer = makeTimer(device, transport, timerId = 0)

        makeEvent(
            device, transport,
            source = EventSource.timerFired(timer),
            action = EventAction.from(ledPlay()),
            boardEventId = 0,
        )

        // ENTRY: [0x0A, 0x02, src_mod, src_reg, src_dataID, dst_mod, dst_reg, param_len]
        val entry = transport.writtenCommands.firstOrNull {
            it.size == 8 && (it[0].toInt() and 0xFF) == 0x0A && (it[1].toInt() and 0xFF) == 0x02
        }
        assertNotNull(entry, "createEvent must send [0x0A, 0x02, ...] ENTRY command")
        // src = timer(0x0C), notify reg(0x06), dataID=timer.id=0
        // dst = LED(0x02), PLAY reg(0x01), param_length = 1 (LED Play sends [0x01])
        assertArrayEquals(bytes(0x0A, 0x02, 0x0C, 0x06, 0x00, 0x02, 0x01, 0x01), entry)
    }

    @Test
    fun `createEvent sends CMD_PARAMETERS after ENTRY`() = runTest {
        val (device, transport) = connectedDevice()
        val timer = makeTimer(device, transport, timerId = 0)

        makeEvent(
            device, transport,
            source = EventSource.timerFired(timer),
            action = EventAction.from(ledPlay()),
            boardEventId = 0,
        )

        // CMD_PARAMETERS: [0x0A, 0x03, ...params...]
        val cmdParams = findParamsCommand(transport)
        assertNotNull(cmdParams, "createEvent must send [0x0A, 0x03, ...] CMD_PARAMETERS")
        assertArrayEquals(bytes(0x0A, 0x03, 0x01), cmdParams) // LED Play param = 0x01
    }

    @Test
    fun `createEvent with no params skips CMD_PARAMETERS`() = runTest {
        val (device, transport) = connectedDevice()

        // Action with no params
        val action = EventAction(Module.LED, 0x01)
        makeEvent(
            device, transport,
            source = EventSource.buttonChanged(),
            action = action,
            boardEventId = 1,
        )

        assertNull(findParamsCommand(transport), "No CMD_PARAMETERS when action has no params")
    }

    @Test
    fun `createEvent returns the board-assigned id`() = runTest {
        val (device, transport) = connectedDevice()

        val event = makeEvent(
            device, transport,
            source = EventSource.buttonChanged(),
            action = EventAction.from(ledPlay()),
            boardEventId = 5,
        )
        assertEquals(5, event.id)
    }

    @Test
    fun `createEvent button source encodes switch module bytes`() = runTest {
        val (device, transport) = connectedDevice()

        makeEvent(
            device, transport,
            source = EventSource.buttonChanged(),
            action = EventAction.from(Gpio.SetHigh(pin = 0)),
            boardEventId = 0,
        )

        val entry = findEntryCommand(transport)!!
        assertEquals(0x01, entry[2].toInt() and 0xFF)   // switch module
        assertEquals(0x01, entry[3].toInt() and 0xFF)   // register
        assertEquals(0xFF, entry[4].toInt() and 0xFF)   // dataID = any
    }

    // ---- removeEvent / removeAllEvents ----

    @Test
    fun `removeEvent sends the remove command`() = runTest {
        val (device, transport) = connectedDevice()
        device.removeEvent(Event(id = 3))

        assertTrue(
            transport.writtenCommands.any { it.contentEquals(bytes(0x0A, 0x04, 0x03)) },
            "removeEvent must send [0x0A, 0x04, event_id]",
        )
    }

    @Test
    fun `removeAllEvents sends the remove-all command`() = runTest {
        val (device, transport) = connectedDevice()
        device.removeAllEvents()

        assertTrue(
            transport.writtenCommands.any { it.contentEquals(bytes(0x0A, 0x05)) },
            "removeAllEvents must send [0x0A, 0x05]",
        )
    }

    // ---- Integration: timer → LED ----

    @Test
    fun `timer fired event full command sequence`() = runTest {
        val (device, transport) = connectedDevice()
        val timer = makeTimer(device, transport, timerId = 0)

        val event = makeEvent(
            device, transport,
            source = EventSource.timerFired(timer),
            action = EventAction.from(ledPlay()),
            boardEventId = 0,
        )

        device.startTimer(timer)
        // ... later, tear down
        device.removeEvent(event)
        device.stopTimer(timer)
        device.removeTimer(timer)

        val cmds = transport.writtenCommands
        assertTrue(cmds.any { it.contentEquals(bytes(0x0C, 0x03, 0x00)) }, "startTimer")
        assertTrue(cmds.any { it.contentEquals(bytes(0x0A, 0x04, 0x00)) }, "removeEvent")
        assertTrue(cmds.any { it.contentEquals(bytes(0x0C, 0x04, 0x00)) }, "stopTimer")
        assertTrue(cmds.any { it.contentEquals(bytes(0x0C, 0x05, 0x00)) }, "removeTimer")
    }

    // ---- EventDataToken bit-packing ----

    // encodedBytes = [0x01 | (length << 1) | (sourceOffset << 4), destOffset]
    // length=1, sourceOffset=0, destOffset=0 → [0x01 | 0x02 | 0x00, 0x00] = [0x03, 0x00]
    @Test
    fun `token with minimal length one`() {
        val t = EventDataToken(length = 1)
        assertArrayEquals(bytes(0x03, 0x00), t.encodedBytes)
    }

    // length=4, sourceOffset=2, destOffset=3
    //   byte0 = 0x01 | (4 << 1) | (2 << 4) = 0x01 | 0x08 | 0x20 = 0x29
    //   byte1 = 0x03
    @Test
    fun `token mid-range bits line up`() {
        val t = EventDataToken(length = 4, sourceOffset = 2, destOffset = 3)
        assertArrayEquals(bytes(0x29, 0x03), t.encodedBytes)
    }

    // Max legal values: length=7 (bits 1-3 full), sourceOffset=15 (bits 4-7 full)
    //   byte0 = 0x01 | (7 << 1) | (15 << 4) = 0x01 | 0x0E | 0xF0 = 0xFF
    @Test
    fun `token max values pack into all higher bits`() {
        val t = EventDataToken(length = 7, sourceOffset = 15, destOffset = 0xAB)
        assertArrayEquals(bytes(0xFF, 0xAB), t.encodedBytes)
    }

    // Bit 0 is always set — it's the "token present" marker.
    @Test
    fun `token bit zero always set`() {
        for (length in 1..7) {
            for (srcOff in 0..15) {
                val t = EventDataToken(length = length, sourceOffset = srcOff, destOffset = 0)
                assertEquals(0x01, t.encodedBytes[0].toInt() and 0x01)
            }
        }
    }

    // destOffset is written verbatim (no packing).
    @Test
    fun `token destOffset verbatim`() {
        for (d in 0..16) {
            val t = EventDataToken(length = 1, sourceOffset = 0, destOffset = d)
            assertEquals(d, t.encodedBytes[1].toInt() and 0xFF)
        }
    }

    // Out-of-range length / sourceOffset throw rather than crashing.
    @Test
    fun `token out-of-range values throw`() {
        assertTrue(runCatching { EventDataToken(length = 0) }.exceptionOrNull() is MetaWearException.OperationFailed)
        assertTrue(runCatching { EventDataToken(length = 8) }.exceptionOrNull() is MetaWearException.OperationFailed)
        assertTrue(
            runCatching { EventDataToken(length = 1, sourceOffset = 16) }
                .exceptionOrNull() is MetaWearException.OperationFailed,
        )
    }

    // ---- createEvent with data token ----

    // When no token is passed, the ENTRY command length stays at 8 bytes
    // (the baseline "straight passthrough" case — regression guard).
    @Test
    fun `no token leaves ENTRY at eight bytes`() = runTest {
        val (device, transport) = connectedDevice()
        makeEvent(
            device, transport,
            source = EventSource.buttonChanged(),
            action = EventAction.from(ledPlay()),
            boardEventId = 0,
        )

        val entry = findEntryCommand(transport)
        assertEquals(8, entry?.size, "ENTRY must be 8 bytes without a token")
    }

    // With a token, the ENTRY command is 10 bytes (8 baseline + 2 token bytes).
    @Test
    fun `token appends two bytes to ENTRY`() = runTest {
        val (device, transport) = connectedDevice()
        val token = EventDataToken(length = 4, sourceOffset = 2, destOffset = 3)

        makeEvent(
            device, transport,
            source = EventSource.buttonChanged(),
            action = EventAction.from(ledPlay()),
            dataToken = token,
            boardEventId = 0,
        )

        val entry = findEntryCommand(transport)
        assertNotNull(entry)
        assertEquals(10, entry!!.size, "ENTRY must be 10 bytes with a token appended")
        // Verify the appended token bytes:
        //   length=4, sourceOffset=2 → 0x01 | 0x08 | 0x20 = 0x29
        //   destOffset=3
        assertEquals(0x29, entry[8].toInt() and 0xFF)
        assertEquals(0x03, entry[9].toInt() and 0xFF)
    }

    // CMD_PARAMETERS is still sent exactly as before — the token lives on ENTRY only.
    @Test
    fun `token leaves CMD_PARAMETERS unchanged`() = runTest {
        val (device, transport) = connectedDevice()
        val token = EventDataToken(length = 1)

        makeEvent(
            device, transport,
            source = EventSource.buttonChanged(),
            action = EventAction.from(ledPlay()),
            dataToken = token,
            boardEventId = 0,
        )

        // LED Play commandData = [0x02, 0x01, 0x01] → action.params = [0x01]
        assertArrayEquals(bytes(0x0A, 0x03, 0x01), findParamsCommand(transport))
    }
}
