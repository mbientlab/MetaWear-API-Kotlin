package com.mbientlab.metawear

import app.cash.turbine.test
import com.mbientlab.metawear.model.BatteryState
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.CommandSequence
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser
import com.mbientlab.metawear.protocol.Pollable
import com.mbientlab.metawear.sensor.AccelerometerBmi160
import com.mbientlab.metawear.transport.MockBleTransport
import com.mbientlab.metawear.transport.Uuids
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Step 3 vertical-slice suites: connect, read, stream, and command paths end to end. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent
class MetaWearDeviceTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    private fun TestScope.makeDevice(transport: MockBleTransport): MetaWearDevice =
        MetaWearDevice(mac, transport, backgroundScope)

    /** Connect a device against the stub board, then stop the discovery replier. */
    private suspend fun TestScope.connectedDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = makeDevice(transport)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport)
        device.connect()
        discovery.cancel()
        return device to transport
    }

    // ---- Connection ----

    @Test
    fun `connect transitions to idle`() = runTest {
        val (device, _) = connectedDevice()
        assertEquals(DeviceState.Idle, device.state.value)
    }

    @Test
    fun `connect populates device info`() = runTest {
        val (device, _) = connectedDevice()
        assertEquals("MbientLab", device.deviceInfo?.manufacturer)
        assertEquals("1.5.0", device.deviceInfo?.firmwareRevision)
    }

    @Test
    fun `connect when already connected throws`() = runTest {
        val (device, transport) = connectedDevice()
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport)

        val error = runCatching { device.connect() }.exceptionOrNull()
        discovery.cancel()
        assertTrue(error is MetaWearException.InvalidState)
    }

    @Test
    fun `connect while already connecting throws`() = runTest {
        val transport = makeConnectableTransport()
        // Widen the connecting window so the second caller sees Connecting.
        transport.connectDelay = 50.milliseconds
        val device = makeDevice(transport)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport)

        val first = async { device.connect() }
        runCurrent() // first connect is now parked inside transport.connect
        assertEquals(DeviceState.Connecting, device.state.value)

        val error = runCatching { device.connect() }.exceptionOrNull()
        assertTrue(error is MetaWearException.InvalidState)

        first.await() // first connect still completes normally
        discovery.cancel()
        assertEquals(DeviceState.Idle, device.state.value)
    }

    @Test
    fun `disconnect transitions to disconnected`() = runTest {
        val (device, _) = connectedDevice()
        device.disconnect()
        assertEquals(DeviceState.Disconnected, device.state.value)
    }

    @Test
    fun `connect when transport fails rethrows and resets state`() = runTest {
        val transport = makeConnectableTransport()
        transport.connectError = MetaWearException.OperationFailed("BLE timeout")
        val device = makeDevice(transport)

        val error = runCatching { device.connect() }.exceptionOrNull()
        assertTrue(error is MetaWearException.OperationFailed)
        assertEquals(DeviceState.Disconnected, device.state.value)
    }

    // ---- Streaming guard ----

    @Test
    fun `stream transitions to streaming`() = runTest {
        val (device, _) = connectedDevice()
        device.startStream(AccelerometerBmi160(), usePacked = false)
        assertEquals(DeviceState.Streaming, device.state.value)
    }

    @Test
    fun `stream when already streaming same sensor throws`() = runTest {
        val (device, _) = connectedDevice()
        val sensor = AccelerometerBmi160()
        device.startStream(sensor, usePacked = false)

        val error = runCatching { device.startStream(sensor, usePacked = false) }.exceptionOrNull()
        assertTrue(error is MetaWearException.InvalidState)
    }

    @Test
    fun `stream sends configure, subscribe, enable, start commands in order`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        device.startStream(sensor, usePacked = false)

        val commands = transport.writtenCommands
        assertArrayEquals(bytes(0x03, 0x03, 0x28, 0x03), commands[0]) // configure
        assertArrayEquals(bytes(0x03, 0x04, 0x01), commands[1])       // subscribe data register
        assertArrayEquals(bytes(0x03, 0x02, 0x01, 0x00), commands[2]) // enable
        assertArrayEquals(bytes(0x03, 0x01, 0x01), commands[3])       // start
    }

    @Test
    fun `packed stream subscribes the packed register`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()
        device.startStream(AccelerometerBmi160(), usePacked = true)

        // BMI160 packed register is 0x1C
        assertTrue(transport.writtenCommands.any { it.contentEquals(bytes(0x03, 0x1C, 0x01)) })
    }

    @Test
    fun `stopStreaming transitions back to idle`() = runTest {
        val (device, _) = connectedDevice()
        val sensor = AccelerometerBmi160()
        device.startStream(sensor, usePacked = false)

        device.stopStreaming(sensor)
        assertEquals(DeviceState.Idle, device.state.value)
    }

    @Test
    fun `stopStreaming sends stop and disable commands`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160()
        device.startStream(sensor, usePacked = false)

        transport.clearWrites()
        device.stopStreaming(sensor)

        val after = transport.writtenCommands
        assertTrue(after.any { it.contentEquals(sensor.stopCommand) })
        assertTrue(after.any { it.contentEquals(sensor.disableCommand) })
    }

    // ---- The vertical slice: streamed packets decode to typed samples ----

    @Test
    fun `streamed packets decode to timestamped samples`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        val stream = device.startStream(sensor, usePacked = false)

        stream.test {
            // x = 1g, y = 0g, z = 0.5g at ±2g scale
            transport.inject(bytes(0x03, 0x04, 0x00, 0x40, 0x00, 0x00, 0x00, 0x20), Uuids.notify)
            val sample = awaitItem()
            assertEquals(1.0f, sample.value.x)
            assertEquals(0.0f, sample.value.y)
            assertEquals(0.5f, sample.value.z)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `packed stream emits three samples per packet`() = runTest {
        val (device, transport) = connectedDevice()
        val stream = device.startStream(AccelerometerBmi160(), usePacked = true)

        stream.test {
            transport.inject(
                bytes(
                    0x03, 0x1C,
                    0x00, 0x40, 0x00, 0x00, 0x00, 0x00, // x = 1g
                    0x00, 0x00, 0x00, 0x40, 0x00, 0x00, // y = 1g
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x40, // z = 1g
                ),
                Uuids.notify,
            )
            assertEquals(1.0f, awaitItem().value.x)
            assertEquals(1.0f, awaitItem().value.y)
            assertEquals(1.0f, awaitItem().value.z)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stream fails when BLE drops mid-stream`() = runTest {
        val (device, transport) = connectedDevice()
        val stream = device.startStream(AccelerometerBmi160(), usePacked = false)

        stream.test {
            transport.simulateDisconnect()
            assertTrue(awaitError() is MetaWearException.OperationFailed)
        }
    }

    // ---- Module helpers ----

    @Test
    fun `hasGyroscope true when present`() = runTest {
        val (device, _) = connectedDevice()
        assertTrue(device.hasGyroscope)
        assertTrue(device.hasSensorFusion)
    }

    @Test
    fun `hasGyroscope false when absent`() = runTest {
        val transport = makeConnectableTransport()
        val device = makeDevice(transport)
        val discovery = backgroundScope.autoReplyAllAbsent(transport)
        device.connect()
        discovery.cancel()

        assertFalse(device.hasGyroscope)
        assertFalse(device.moduleInfo(Module.GYRO)!!.isPresent)
    }

    // ---- Command sequence dispatch ----

    @Test
    fun `send sequence issues writes in order`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()

        val sequence = object : CommandSequence {
            override val commands = listOf(
                bytes(0x03, 0x07, 0x02, 0x00),
                bytes(0x03, 0x06, 0x02, 0x00),
            )
        }
        device.send(sequence)

        assertArrayEquals(bytes(0x03, 0x07, 0x02, 0x00), transport.writtenCommands[0])
        assertArrayEquals(bytes(0x03, 0x06, 0x02, 0x00), transport.writtenCommands[1])
    }

    // ---- Generic read and poll ----

    /** Battery readable: read [0x11, 0x91] → [0x11, 0x91, charge, mV lo, mV hi]. */
    private class BatteryReadable : Pollable<BatteryState> {
        override val module = Module.SETTINGS
        override val dataRegister = 0x11
        override val readCommand = Packet.read(Module.SETTINGS, 0x11)
        override fun parseSample(packet: ByteArray) = PacketParser.parseBatteryState(packet)
    }

    private fun batteryReply(cmd: ByteArray): ByteArray? =
        if (cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x11 && (cmd[1].toInt() and 0xFF) == 0x91) {
            bytes(0x11, 0x91, 0x55, 0x9C, 0x0F) // charge 85 %, 3996 mV
        } else {
            null
        }

    @Test
    fun `generic read returns timestamped parsed sample`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport, ::batteryReply)

        val battery = device.read(BatteryReadable())
        replies.cancel()

        assertEquals(85, battery.value.charge)
        assertEquals(3996, battery.value.voltage)
    }

    @Test
    fun `poll delivers repeated readings on the interval`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport, ::batteryReply)

        device.poll(BatteryReadable(), every = 30.seconds).test {
            assertEquals(85, awaitItem().value.charge) // immediate first read
            assertEquals(85, awaitItem().value.charge) // after 30 s (virtual)
            assertEquals(85, awaitItem().value.charge)
            cancelAndIgnoreRemainingEvents()
        }
        replies.cancel()
    }

    @Test
    fun `read times out when board never responds`() = runTest {
        val (device, _) = connectedDevice()
        val error = runCatching { device.read(BatteryReadable()) }.exceptionOrNull()
        assertTrue(error is MetaWearException.Timeout)
    }

    // ---- Unexpected disconnect ----

    @Test
    fun `unexpected disconnect resets state and notifies callback`() = runTest {
        val (device, transport) = connectedDevice()
        var reported: Throwable? = null
        device.onUnexpectedDisconnect = { reported = it }

        device.startStream(AccelerometerBmi160(), usePacked = false)
        transport.simulateDisconnect()
        // runCurrent, not advanceUntilIdle: the router lives in backgroundScope,
        // and advanceUntilIdle only drains foreground tasks.
        runCurrent()

        assertEquals(DeviceState.Disconnected, device.state.value)
        assertTrue(reported is MetaWearException.OperationFailed)
    }

    @Test
    fun `intentional disconnect does not fire the callback`() = runTest {
        val (device, _) = connectedDevice()
        var fired = false
        device.onUnexpectedDisconnect = { fired = true }

        device.disconnect()
        runCurrent()

        assertFalse(fired)
    }
}
