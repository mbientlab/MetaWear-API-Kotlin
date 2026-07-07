package com.mbientlab.metawear

import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Loggable
import com.mbientlab.metawear.sensor.AccelerometerBmi160
import com.mbientlab.metawear.sensor.Debug
import com.mbientlab.metawear.transport.MockBleTransport
import com.mbientlab.metawear.transport.Uuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Factory-reset coverage, plus coverage for sendExpectingDisconnect
// (a path otherwise only exercised against hardware).

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FactoryResetTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    /** Modules present for these suites: accel, logging, gyro, baro, mag, fusion. */
    private val presentModules = setOf(0x03, 0x0B, 0x13, 0x12, 0x15, 0x19)

    /**
     * Reply to module-discovery reads (`[module, 0x80]`) and the
     * log-time-reference read (`[0x0B, 0x84]`) so `connect()` can complete.
     * Mirrors the pattern in LogFinishingTest.
     */
    private fun CoroutineScope.autoReplyDiscovery(transport: MockBleTransport): Job =
        autoReply(transport) { cmd ->
            if (cmd.size < 2) return@autoReply null
            val regByte = cmd[1].toInt() and 0xFF
            if (regByte and 0x80 == 0) return@autoReply null
            val moduleId = cmd[0].toInt() and 0xFF
            when {
                moduleId == 0x0B && regByte == 0x84 -> bytes(0x0B, 0x84, 0x00, 0x00, 0x00, 0x00, 0x00)
                regByte == 0x80 -> {
                    val impl = if (moduleId in presentModules) 0x01 else 0xFF
                    bytes(moduleId, 0x80, impl, 0x00)
                }
                else -> bytes(moduleId, regByte, 0x00, 0x00, 0x00, 0x00)
            }
        }

    private suspend fun TestScope.connectedDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice(mac, transport, backgroundScope)
        val discovery = backgroundScope.autoReplyDiscovery(transport)
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

    /** Only the writes that hit the command characteristic, after [baseline]. */
    private fun commandWrites(baseline: Int, transport: MockBleTransport): List<ByteArray> =
        transport.writtenData
            .filter { it.characteristic == Uuids.command }
            .map { it.data }
            .drop(baseline)

    // ---- factoryReset ----

    /**
     * Mirrors the Python C-API call sequence, with one addition (step 8):
     *   mbl_mw_logging_stop
     *   mbl_mw_logging_clear_entries
     *   mbl_mw_event_remove_all
     *   mbl_mw_dataprocessor_remove_all
     *   mbl_mw_macro_erase_all
     *   mbl_mw_debug_reset_after_gc
     *   mbl_mw_debug_reset                  // immediate-reset fallback
     *
     * The trailing `[0xFE, 0x01]` is needed because some firmware revisions
     * (observed: MMS fw 1.5.0) silently ignore `[0xFE, 0x05]` when the flash
     * GC queue is empty, leaving the boot counter unchanged. See the
     * `factoryReset` doc comment in `MetaWearDevice`.
     */
    @Test
    fun `factoryReset emits expected byte sequence in order`() = runTest {
        val (device, transport) = connectedDevice()
        val baseline = transport.writtenData.count { it.characteristic == Uuids.command }

        device.factoryReset()

        val new = commandWrites(baseline, transport)
        assertEquals(8, new.size)
        assertArrayEquals(bytes(0x0B, 0x01, 0x00), new[0])                   // 1. stop logging
        assertArrayEquals(bytes(0x0B, 0x09, 0xFF, 0xFF, 0xFF, 0xFF), new[1]) // 2. drop log entries
        assertArrayEquals(bytes(0x0B, 0x0A), new[2])                         // 3. remove all loggers
        assertArrayEquals(bytes(0x0A, 0x05), new[3])                         // 4. remove all events
        assertArrayEquals(bytes(0x09, 0x08), new[4])                         // 5. remove all processors
        assertArrayEquals(bytes(0x0F, 0x08), new[5])                         // 6. erase all macros
        assertArrayEquals(bytes(0xFE, 0x05), new[6])                         // 7. reset after GC
        assertArrayEquals(bytes(0xFE, 0x01), new[7])                         // 8. immediate-reset fallback
    }

    @Test
    fun `factoryReset transitions to disconnected`() = runTest {
        val (device, _) = connectedDevice()
        assertEquals(DeviceState.Idle, device.state.value)

        device.factoryReset()
        assertEquals(DeviceState.Disconnected, device.state.value)
    }

    @Test
    fun `factoryReset clears logger registry`() = runTest {
        val (device, transport) = connectedDevice()

        // Seed the registry by starting (and not stopping) a logger session.
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        startLoggingWithReplies(sensor, device, transport)
        assertTrue(device.loggerRegistry.containsKey(sensor.loggerKey))

        device.factoryReset()
        assertFalse(device.loggerRegistry.containsKey(sensor.loggerKey))
    }

    @Test
    fun `factoryReset clears log reference date`() = runTest {
        val (device, _) = connectedDevice()
        // connect() reads the time anchor; reference date should now be set.
        assertNotNull(device.logReferenceDate)

        device.factoryReset()
        assertNull(device.logReferenceDate)
    }

    @Test
    fun `factoryReset when already disconnected throws`() = runTest {
        val device = MetaWearDevice(mac, MockBleTransport(), backgroundScope)
        val error = runCatching { device.factoryReset() }.exceptionOrNull()
        assertTrue(
            error is MetaWearException.InvalidState,
            "Expected InvalidState when factoryReset called on a disconnected device",
        )
    }

    @Test
    fun `factoryReset from logging state succeeds`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        startLoggingWithReplies(sensor, device, transport)
        assertEquals(DeviceState.Logging, device.state.value)

        // factoryReset is intentionally permissive about the starting state —
        // the reboot wipes everything regardless.
        device.factoryReset()
        assertEquals(DeviceState.Disconnected, device.state.value)
    }

    // ---- sendExpectingDisconnect ----

    @Test
    fun `sendExpectingDisconnect converges when the board drops the link`() = runTest {
        val (device, transport) = connectedDevice()
        var unexpectedFired = false
        device.onUnexpectedDisconnect = { unexpectedFired = true }

        val op = async { device.sendExpectingDisconnect(Debug.Reset()) }
        runCurrent() // command written; waiting on the drop signal
        assertTrue(transport.writtenCommands.any { it.contentEquals(bytes(0xFE, 0x01)) })

        transport.simulateDisconnect()
        op.await()

        assertEquals(DeviceState.Disconnected, device.state.value)
        assertNull(device.logReferenceDate)
        assertFalse(unexpectedFired, "an expected drop must not fire onUnexpectedDisconnect")
    }

    @Test
    fun `sendExpectingDisconnect falls back to local disconnect after timeout`() = runTest {
        val (device, _) = connectedDevice()
        var unexpectedFired = false
        device.onUnexpectedDisconnect = { unexpectedFired = true }

        // No simulated drop: the 5s (virtual) timeout elapses and the device
        // tears the connection down locally.
        device.sendExpectingDisconnect(Debug.Reset())

        assertEquals(DeviceState.Disconnected, device.state.value)
        assertFalse(unexpectedFired)
    }
}
