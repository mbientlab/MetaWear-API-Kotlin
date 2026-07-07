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
import com.mbientlab.metawear.transport.WriteType
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Byte-identical stand-ins for LED commands (the LED module lives in a
// separate file); the macro machinery only ever sees their commandData bytes.
private class MacroStubCommand(private val data: ByteArray) : Command {
    override val commandData: ByteArray get() = data
}

/** LED play → `[0x02, 0x01, 0x01]`. */
private fun playCommand(): Command = MacroStubCommand(bytes(0x02, 0x01, 0x01))

/** LED stop, keeping the pattern → `[0x02, 0x02, 0x00]`. */
private fun stopCommand(): Command = MacroStubCommand(bytes(0x02, 0x02, 0x00))

/**
 * A 15-byte command (LED SetPattern shape) — over the 13-byte ADD_COMMAND
 * threshold, so recording it must split into ADD_PARTIAL + ADD_COMMAND.
 */
private fun longCommand(): Command = MacroStubCommand(
    bytes(0x02, 0x03, 0x01, 0x02, 0x1F, 0x00, 0x64, 0x00, 0xC8, 0x00, 0x64, 0x00, 0x20, 0x03, 0xFF),
)

/** Macro-module tests: record, end, execute, and erase command flows. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent
class MacroTest {

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
     * Record a macro, injecting the board's ID-assignment response.
     * Response to BEGIN: `[0x0F, 0x02, macro_id]` — plain notification (high
     * bit clear), NOT a read response. Mirror real firmware here.
     */
    private suspend fun TestScope.recordMacro(
        device: MetaWearDevice,
        transport: MockBleTransport,
        executeOnBoot: Boolean = false,
        commands: List<Command> = emptyList(),
        boardMacroId: Int = 0,
    ): Macro {
        val recorded = async { device.recordMacro(executeOnBoot, commands) }
        runCurrent() // BEGIN written; notify waiter parked
        transport.inject(bytes(0x0F, 0x02, boardMacroId), Uuids.notify)
        runCurrent()
        return recorded.await()
    }

    /** The 3-byte BEGIN command `[0x0F, 0x02, exec_on_boot]`. */
    private fun findBeginCommand(transport: MockBleTransport): ByteArray? =
        transport.writtenCommands.firstOrNull {
            it.size == 3 && (it[0].toInt() and 0xFF) == 0x0F && (it[1].toInt() and 0xFF) == 0x02
        }

    /** All ADD_COMMAND packets `[0x0F, 0x03, ...]`. */
    private fun findAddCommands(transport: MockBleTransport): List<ByteArray> =
        transport.writtenCommands.filter {
            it.size >= 3 && (it[0].toInt() and 0xFF) == 0x0F && (it[1].toInt() and 0xFF) == 0x03
        }

    // ---- recordMacro command format ----

    @Test
    fun `recordMacro sends BEGIN for manual-only`() = runTest {
        val (device, transport) = connectedDevice()
        recordMacro(device, transport, executeOnBoot = false, boardMacroId = 0)

        val begin = findBeginCommand(transport)
        assertNotNull(begin, "recordMacro must send [0x0F, 0x02, ...]")
        assertEquals(0x00, begin!![2].toInt() and 0xFF, "exec_on_boot should be 0x00 for manual-only")
    }

    @Test
    fun `recordMacro sends BEGIN for execute-on-boot`() = runTest {
        val (device, transport) = connectedDevice()
        recordMacro(device, transport, executeOnBoot = true, boardMacroId = 1)

        val begin = findBeginCommand(transport)
        assertNotNull(begin)
        assertEquals(0x01, begin!![2].toInt() and 0xFF, "exec_on_boot should be 0x01")
    }

    @Test
    fun `recordMacro sends END`() = runTest {
        val (device, transport) = connectedDevice()
        recordMacro(device, transport, boardMacroId = 0)

        assertTrue(
            transport.writtenCommands.any { it.contentEquals(bytes(0x0F, 0x04)) },
            "recordMacro must send END [0x0F, 0x04]",
        )
    }

    @Test
    fun `recordMacro returns the board-assigned id`() = runTest {
        val (device, transport) = connectedDevice()
        val macro = recordMacro(device, transport, boardMacroId = 7)
        assertEquals(7, macro.id)
    }

    @Test
    fun `short command records via ADD_COMMAND`() = runTest {
        val (device, transport) = connectedDevice()
        recordMacro(device, transport, commands = listOf(playCommand()), boardMacroId = 0)

        // ADD_COMMAND: [0x0F, 0x03, dst_mod, dst_reg, ...] — LED Play = [0x02, 0x01, 0x01]
        val addCmd = findAddCommands(transport).firstOrNull()
        assertNotNull(addCmd, "Short command must use ADD_COMMAND [0x0F, 0x03, ...]")
        assertArrayEquals(bytes(0x0F, 0x03, 0x02, 0x01, 0x01), addCmd)
    }

    @Test
    fun `multiple commands all record via ADD_COMMAND`() = runTest {
        val (device, transport) = connectedDevice()
        recordMacro(
            device, transport,
            commands = listOf(playCommand(), stopCommand()),
            boardMacroId = 0,
        )

        assertEquals(2, findAddCommands(transport).size, "Two commands → two ADD_COMMAND packets")
    }

    @Test
    fun `long command splits into ADD_PARTIAL then ADD_COMMAND`() = runTest {
        val (device, transport) = connectedDevice()
        assertTrue(longCommand().commandData.size > 13, "Long command must be > 13 bytes for this test")

        recordMacro(device, transport, commands = listOf(longCommand()), boardMacroId = 0)

        val cmds = transport.writtenCommands
        val partial = cmds.firstOrNull {
            it.size == 4 && (it[0].toInt() and 0xFF) == 0x0F && (it[1].toInt() and 0xFF) == 0x09
        }
        assertNotNull(partial, "Long command must send ADD_PARTIAL [0x0F, 0x09, byte0, byte1]")
        val addCmd = cmds.firstOrNull {
            it.size > 4 && (it[0].toInt() and 0xFF) == 0x0F && (it[1].toInt() and 0xFF) == 0x03
        }
        assertNotNull(addCmd, "Long command must also send ADD_COMMAND [0x0F, 0x03, ...]")
    }

    @Test
    fun `ADD_PARTIAL carries the first two command bytes`() = runTest {
        val (device, transport) = connectedDevice()
        val originalData = longCommand().commandData

        recordMacro(device, transport, commands = listOf(longCommand()), boardMacroId = 0)

        val partial = transport.writtenCommands.first {
            it.size == 4 && (it[0].toInt() and 0xFF) == 0x0F && (it[1].toInt() and 0xFF) == 0x09
        }
        assertEquals(originalData[0], partial[2], "ADD_PARTIAL byte0 must match command byte0")
        assertEquals(originalData[1], partial[3], "ADD_PARTIAL byte1 must match command byte1")
        // ADD_COMMAND carries the remaining bytes verbatim.
        val addCmd = transport.writtenCommands.first {
            it.size > 4 && (it[0].toInt() and 0xFF) == 0x0F && (it[1].toInt() and 0xFF) == 0x03
        }
        assertArrayEquals(bytes(0x0F, 0x03) + originalData.copyOfRange(2, originalData.size), addCmd)
    }

    @Test
    fun `macro ADD and END use write-with-response`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()
        recordMacro(device, transport, commands = listOf(playCommand()), boardMacroId = 0)

        val writes = transport.writtenData
        val begin = writes.first {
            it.data.size == 3 && (it.data[0].toInt() and 0xFF) == 0x0F && (it.data[1].toInt() and 0xFF) == 0x02
        }
        val add = writes.first {
            it.data.size >= 3 && (it.data[0].toInt() and 0xFF) == 0x0F && (it.data[1].toInt() and 0xFF) == 0x03
        }
        val end = writes.first { it.data.contentEquals(bytes(0x0F, 0x04)) }

        // BEGIN goes out as a normal command; ADD/END are flash writes that
        // require write-with-response pacing.
        assertEquals(WriteType.WITHOUT_RESPONSE, begin.type)
        assertEquals(WriteType.WITH_RESPONSE, add.type)
        assertEquals(WriteType.WITH_RESPONSE, end.type)
    }

    // ---- MacroRecorder event recording ----

    @Test
    fun `recorder createEvent rejects oversized action payload`() {
        val recorder = MacroRecorder()
        val action = EventAction(
            module = Module.LED,
            register = 0x01,
            params = ByteArray(256) { 0xAA.toByte() },
        )

        val error = runCatching {
            recorder.createEvent(source = EventSource.buttonChanged(), action = action)
        }.exceptionOrNull()
        assertTrue(error is MetaWearException.OperationFailed)

        assertTrue(
            recorder.packets.isEmpty(),
            "Rejected embedded events must not leave partial macro packets behind",
        )
    }

    @Test
    fun `closure recordMacro embeds event ENTRY and CMD_PARAMETERS`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()

        val recorded = async {
            device.recordMacro(executeOnBoot = true) { recorder ->
                recorder.send(playCommand())
                recorder.createEvent(
                    source = EventSource.buttonChanged(),
                    action = EventAction.from(playCommand()),
                )
            }
        }
        runCurrent()
        transport.inject(bytes(0x0F, 0x02, 0x00), Uuids.notify)
        runCurrent()
        recorded.await()

        val cmds = transport.writtenCommands
        // Recorder packets replay through ADD_COMMAND: [0x0F, 0x03, ...]
        assertTrue(cmds.any { it.contentEquals(bytes(0x0F, 0x03, 0x02, 0x01, 0x01)) }, "buffered LED Play")
        assertTrue(
            cmds.any { it.contentEquals(bytes(0x0F, 0x03, 0x0A, 0x02, 0x01, 0x01, 0xFF, 0x02, 0x01, 0x01)) },
            "embedded event ENTRY",
        )
        assertTrue(cmds.any { it.contentEquals(bytes(0x0F, 0x03, 0x0A, 0x03, 0x01)) }, "embedded CMD_PARAMETERS")
    }

    // ---- executeMacro / eraseAllMacros ----

    @Test
    fun `executeMacro sends the execute command`() = runTest {
        val (device, transport) = connectedDevice()
        device.executeMacro(Macro(id = 3))

        assertTrue(
            transport.writtenCommands.any { it.contentEquals(bytes(0x0F, 0x05, 0x03)) },
            "executeMacro must send [0x0F, 0x05, macro_id]",
        )
    }

    @Test
    fun `eraseAllMacros sends the erase command`() = runTest {
        val (device, transport) = connectedDevice()
        device.eraseAllMacros()

        assertTrue(
            transport.writtenCommands.any { it.contentEquals(bytes(0x0F, 0x08)) },
            "eraseAllMacros must send [0x0F, 0x08]",
        )
    }

    // ---- Integration ----

    @Test
    fun `boot macro full sequence`() = runTest {
        val (device, transport) = connectedDevice()

        val macro = recordMacro(
            device, transport,
            executeOnBoot = true,
            commands = listOf(playCommand(), stopCommand()),
            boardMacroId = 2,
        )

        assertEquals(2, macro.id)

        // BEGIN with exec_on_boot=1
        val begin = findBeginCommand(transport)!!
        assertEquals(0x01, begin[2].toInt() and 0xFF)
        // Two ADD_COMMAND packets
        assertEquals(2, findAddCommands(transport).size)
        // END
        assertTrue(transport.writtenCommands.any { it.contentEquals(bytes(0x0F, 0x04)) })
    }
}
