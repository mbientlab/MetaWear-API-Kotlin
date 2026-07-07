package com.mbientlab.metawear

import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.PacketParser
import com.mbientlab.metawear.protocol.ProtocolRouter
import com.mbientlab.metawear.sensor.AccelerometerBmi160
import com.mbientlab.metawear.sensor.EventSource
import com.mbientlab.metawear.sensor.GyroscopeBmi160
import com.mbientlab.metawear.sensor.SensorFusionEuler
import com.mbientlab.metawear.sensor.SensorFusionQuaternion
import com.mbientlab.metawear.sensor.Settings
import com.mbientlab.metawear.transport.MockBleTransport
import com.mbientlab.metawear.transport.Uuids
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ported from MWProductionGapTests.swift — read timeouts, reconnection,
 * sensor-conflict detection, settings command vectors, the disconnect event
 * source, and BLE advertising-name validation.
 *
 * Where the Swift tests poll `writtenData` with fixed sleeps, `runCurrent()`
 * on the virtual scheduler makes the same sequencing deterministic. Expected
 * failures inside `async` are caught with `runCatching` so the child cannot
 * fail the test scope.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent
class ProductionGapTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    private fun TestScope.makeRouter(): Pair<MockBleTransport, ProtocolRouter> {
        val transport = MockBleTransport()
        val router = ProtocolRouter(transport, backgroundScope)
        router.start()
        runCurrent() // let the routing collector subscribe
        return transport to router
    }

    /** Connect a device against the stub board, then stop the discovery replier. */
    private suspend fun TestScope.connectedDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice(mac, transport, backgroundScope)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport)
        device.connect()
        discovery.cancel()
        return device to transport
    }

    // ---- 1. Read timeouts ----

    @Test
    fun `read times out when no response`() = runTest {
        val (_, router) = makeRouter()

        // Never inject a response — should time out (virtual time skips the wait).
        val error = runCatching { router.read(Module.ACCELEROMETER, 0x03) }.exceptionOrNull()
        assertTrue(error is MetaWearException.Timeout)
    }

    @Test
    fun `read succeeds before timeout`() = runTest {
        val (transport, router) = makeRouter()

        val response = async { router.read(Module.ACCELEROMETER, 0x03) }
        runCurrent() // waiter parked, read command written
        transport.inject(bytes(0x03, 0x83, 0xAB), Uuids.notify)

        assertArrayEquals(bytes(0x03, 0x83, 0xAB), response.await())
    }

    @Test
    fun `timeout does not affect other waiters`() = runTest {
        val (transport, router) = makeRouter()

        // Read on gyro (will time out) and accel (will get a reply).
        val gyro = async { runCatching { router.read(Module.GYRO, 0x01) } }
        val accel = async { router.read(Module.ACCELEROMETER, 0x03) }
        runCurrent() // both requests written — proof both continuations are parked
        assertTrue(transport.writtenCommands.size >= 2)

        // Only inject the accel reply.
        transport.inject(bytes(0x03, 0x83, 0xFF), Uuids.notify)

        // Accel should succeed.
        assertArrayEquals(bytes(0x03, 0x83, 0xFF), accel.await())

        // The Swift test cancels the gyro task to dodge a real 5 s wait; under
        // virtual time the timeout is free, so let it expire and assert it.
        assertTrue(gyro.await().exceptionOrNull() is MetaWearException.Timeout)
    }

    // ---- 2. Reconnection ----

    @Test
    fun `unexpected disconnect sets state to disconnected`() = runTest {
        val (device, transport) = connectedDevice()
        assertEquals(DeviceState.Idle, device.state.value)

        // Simulate BLE drop
        transport.simulateDisconnect()
        runCurrent() // let the callback propagate

        assertEquals(DeviceState.Disconnected, device.state.value)
    }

    @Test
    fun `unexpected disconnect calls handler`() = runTest {
        val (device, transport) = connectedDevice()

        var handlerCalled = false
        device.onUnexpectedDisconnect = { handlerCalled = true }

        transport.simulateDisconnect()
        runCurrent()

        assertTrue(handlerCalled)
    }

    @Test
    fun `reconnect succeeds from disconnected state`() = runTest {
        val (device, transport) = connectedDevice()

        transport.simulateDisconnect()
        runCurrent()

        // The Swift helper dedupes replies by byte content, so its reconnect
        // needed an index-tracking replier (reconnect's discovery reads are
        // byte-identical to the initial connect's). The Kotlin autoReply is
        // index-based already, so a fresh replier answers every repeat.
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport)
        device.reconnect()
        discovery.cancel()

        assertEquals(DeviceState.Idle, device.state.value)
    }

    @Test
    fun `clean disconnect does not call handler`() = runTest {
        val (device, _) = connectedDevice()

        var handlerCalled = false
        device.onUnexpectedDisconnect = { handlerCalled = true }

        device.disconnect()
        runCurrent()

        assertFalse(handlerCalled)
    }

    // ---- 3. Sensor conflict detection ----

    @Test
    fun `sensor fusion blocks accelerometer`() = runTest {
        val (device, _) = connectedDevice()

        // Start sensor fusion
        device.startStream(SensorFusionQuaternion(), usePacked = false)

        // Trying to stream accelerometer should throw
        val error = runCatching {
            device.startStream(
                AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2),
                usePacked = false,
            )
        }.exceptionOrNull()
        assertTrue(error is MetaWearException.InvalidState)
    }

    @Test
    fun `accelerometer blocks sensor fusion`() = runTest {
        val (device, _) = connectedDevice()

        device.startStream(
            AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2),
            usePacked = false,
        )

        val error = runCatching {
            device.startStream(SensorFusionQuaternion(), usePacked = false)
        }.exceptionOrNull()
        assertTrue(error is MetaWearException.InvalidState)
    }

    @Test
    fun `accel and gyro can stream together`() = runTest {
        val (device, _) = connectedDevice()

        device.startStream(
            AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2),
            usePacked = false,
        )
        // Gyro and accel are both individual IMU sensors — should NOT conflict
        device.startStream(
            GyroscopeBmi160(GyroscopeBmi160.Odr.HZ100, GyroscopeBmi160.Range.DPS2000),
            usePacked = false,
        )

        assertEquals(DeviceState.Streaming, device.state.value)
    }

    @Test
    fun `sensor fusion outputs can stream together`() = runTest {
        val (device, transport) = connectedDevice()
        val quaternion = SensorFusionQuaternion()
        val euler = SensorFusionEuler()

        device.startStream(quaternion, usePacked = false)
        device.startStream(euler, usePacked = false)

        assertEquals(DeviceState.Streaming, device.state.value)

        val beforeStop = transport.writtenCommands.size
        device.stopStreaming(quaternion)
        val stopCommands = transport.writtenCommands.drop(beforeStop)

        // Stopping one of two fusion outputs disables only that output bit —
        // no fusion-engine stop, no clear-all-outputs mask.
        assertTrue(stopCommands.any { it.contentEquals(bytes(0x19, 0x03, 0x00, 0x08)) })
        assertFalse(stopCommands.any { it.contentEquals(bytes(0x19, 0x01, 0x00)) })
        assertFalse(stopCommands.any { it.contentEquals(bytes(0x19, 0x03, 0x00, 0x7F)) })
        assertEquals(DeviceState.Streaming, device.state.value)
    }

    @Test
    fun `stopStreaming allows fusion after IMU`() = runTest {
        val (device, _) = connectedDevice()

        val acc = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)
        device.startStream(acc, usePacked = false)
        device.stopStreaming(acc)

        // Now fusion should be allowed
        device.startStream(SensorFusionQuaternion(), usePacked = false)
        assertEquals(DeviceState.Streaming, device.state.value)
    }

    // ---- 4. Settings commands ----

    @Test
    fun `setDeviceName correct bytes`() {
        val cmd = Settings.SetDeviceName("MyBoard").commandData
        assertEquals(0x11, cmd[0].toInt() and 0xFF) // settings module
        assertEquals(0x01, cmd[1].toInt() and 0xFF) // DEVICE_NAME register
        assertArrayEquals("MyBoard".toByteArray(), cmd.copyOfRange(2, cmd.size))
    }

    @Test
    fun `setDeviceName truncates at max length`() {
        // 40-char input should be truncated to 26 bytes of payload (+2 header).
        val long = "A".repeat(40)
        val cmd = Settings.SetDeviceName(long)
        assertEquals(2 + Settings.MAX_DEVICE_NAME_LENGTH, cmd.commandData.size)
    }

    @Test
    fun `setTXPower correct byte`() {
        val cmd = Settings.SetTxPower(Settings.TxPower.MINUS_4)
        assertArrayEquals(bytes(0x11, 0x03, 0xFC), cmd.commandData) // 0xFC = -4 as UInt8
    }

    @Test
    fun `startAdvertising command`() {
        assertArrayEquals(bytes(0x11, 0x05), Settings.StartAdvertising().commandData)
    }

    @Test
    fun `setAdvertisingInterval encodes as 0_625 units`() {
        // 417ms / 0.625 = 667.2 → 667 units = 0x029B
        val cmd = Settings.SetAdvertisingInterval(intervalMs = 417, timeoutSec = 0).commandData
        assertEquals(0x11, cmd[0].toInt() and 0xFF)
        assertEquals(0x02, cmd[1].toInt() and 0xFF)
        assertEquals((417 / 0.625).toInt(), PacketParser.parseUInt16LE(cmd, 2))
        assertEquals(0, cmd[4].toInt() and 0xFF) // timeout
    }

    @Test
    fun `connectionParams correct bytes`() {
        val cmd = Settings.SetConnectionParameters(
            minInterval = 6, maxInterval = 24, latency = 0, timeout = 500,
        )
        // min=6 LE, max=24 LE, latency=0, timeout=500 LE — 10 bytes total.
        assertArrayEquals(
            bytes(0x11, 0x09, 0x06, 0x00, 0x18, 0x00, 0x00, 0x00, 0xF4, 0x01),
            cmd.commandData,
        )
    }

    @Test
    fun `lowLatency preset`() {
        val cmd = Settings.SetConnectionParameters.lowLatency
        assertEquals(6, cmd.minInterval)
        assertEquals(6, cmd.maxInterval)
        assertEquals(0, cmd.latency)
    }

    @Test
    fun `powerSaving preset`() {
        val cmd = Settings.SetConnectionParameters.powerSaving
        assertTrue(cmd.latency > 0)
        assertTrue(cmd.minInterval > 24)
    }

    // --- Reference vectors from MetaWear-SDK-Cpp/test/test_settings.py ---

    // test_set_name: set device name "AntiWare"
    @Test
    fun `setDeviceName python vector AntiWare`() {
        val cmd = Settings.SetDeviceName("AntiWare")
        assertArrayEquals(
            bytes(0x11, 0x01, 0x41, 0x6E, 0x74, 0x69, 0x57, 0x61, 0x72, 0x65),
            cmd.commandData,
        )
    }

    // Validating factory must produce the same wire bytes as the plain one
    // for an accepted name.
    @Test
    fun `setDeviceName validating AntiWare`() {
        val cmd = Settings.SetDeviceName.validating("AntiWare")
        assertArrayEquals(
            bytes(0x11, 0x01, 0x41, 0x6E, 0x74, 0x69, 0x57, 0x61, 0x72, 0x65),
            cmd.commandData,
        )
    }

    // test_set_tx_power: set_tx_power(-20) → [0x11, 0x03, 0xec]
    @Test
    fun `setTXPower python vector minus20`() {
        val cmd = Settings.SetTxPower(Settings.TxPower.MINUS_20)
        assertArrayEquals(bytes(0x11, 0x03, 0xEC), cmd.commandData)
    }

    // test_set_ad_interval (default rev >= 2): set_ad_interval(417, 0)
    // Encoding: 417 ms / 0.625 = 667 units (0x029B), timeout byte = 0.
    @Test
    fun `setAdvertisingInterval python vector 417ms 0s`() {
        val cmd = Settings.SetAdvertisingInterval(intervalMs = 417, timeoutSec = 0)
        assertArrayEquals(bytes(0x11, 0x02, 0x9B, 0x02, 0x00), cmd.commandData)
    }

    // test_set_ad_interval (Revision1): set_ad_interval(417, 180)
    @Test
    fun `setAdvertisingInterval python vector rev1 417ms 180s`() {
        val cmd = Settings.SetAdvertisingInterval(intervalMs = 417, timeoutSec = 180)
        assertArrayEquals(bytes(0x11, 0x02, 0x9B, 0x02, 0xB4), cmd.commandData)
    }

    // test_set_conn_params: set_connection_parameters(750ms, 1000ms, 128, 16384ms)
    // Kotlin takes raw 1.25 ms units for min/max and 10 ms units for timeout:
    //   750 / 1.25  = 600 = 0x0258
    //   1000 / 1.25 = 800 = 0x0320
    //   latency     = 128 = 0x0080
    //   16384 / 10  = 1638 ≈ 0x0666
    @Test
    fun `setConnectionParameters python vector`() {
        val cmd = Settings.SetConnectionParameters(
            minInterval = 600,
            maxInterval = 800,
            latency = 128,
            timeout = 1638,
        )
        assertArrayEquals(
            bytes(0x11, 0x09, 0x58, 0x02, 0x20, 0x03, 0x80, 0x00, 0x66, 0x06),
            cmd.commandData,
        )
    }

    // test_start_advertising: [0x11, 0x05]
    @Test
    fun `startAdvertising python vector`() {
        assertArrayEquals(bytes(0x11, 0x05), Settings.StartAdvertising().commandData)
    }

    // test_set_scan_response (21-byte payload, split across PARTIAL_SCAN_RESPONSE
    // (0x08) and SCAN_RESPONSE (0x07)).
    @Test
    fun `setScanResponse python vector split write`() {
        val payload = bytes(
            0x03, 0x03, 0xD8, 0xFE, 0x10, 0x16, 0xD8, 0xFE,
            0x00, 0x12, 0x00, 0x6D, 0x62, 0x69, 0x65, 0x6E,
            0x74, 0x6C, 0x61, 0x62, 0x00,
        )
        val cmds = Settings.SetScanResponse(payload).commands
        assertEquals(2, cmds.size)
        assertArrayEquals(
            bytes(
                0x11, 0x08,
                0x03, 0x03, 0xD8, 0xFE, 0x10, 0x16, 0xD8, 0xFE,
                0x00, 0x12, 0x00, 0x6D, 0x62,
            ),
            cmds[0],
        )
        assertArrayEquals(
            bytes(0x11, 0x07, 0x69, 0x65, 0x6E, 0x74, 0x6C, 0x61, 0x62, 0x00),
            cmds[1],
        )
    }

    @Test
    fun `setScanResponse short payload single write`() {
        // Short payload (≤ 13 bytes) should emit a single write to register 0x07.
        val cmds = Settings.SetScanResponse(bytes(0x01, 0x02, 0x03)).commands
        assertEquals(1, cmds.size)
        assertArrayEquals(bytes(0x11, 0x07, 0x01, 0x02, 0x03), cmds[0])
    }

    // test_read_battery_state in `test_settings.py` asserts `[0x11, 0xCC]` because
    // it calls `mbl_mw_datasignal_read` on a freshly-constructed signal, where the
    // C++ constructor leaves the SILENT bit (0x40) enabled. The production SDK
    // subscribes before reading, which clears the silent bit — the real wire byte
    // is `0x8C`, and that is what MMS firmware actually responds to.
    @Test
    fun `readBatteryState readCommand matches production wire`() {
        assertArrayEquals(bytes(0x11, 0x8C), Settings.ReadBatteryState().readCommand)
    }

    // test_battery_state_data: b'\x11\x8c\x63\x34\x10' → BatteryState(voltage: 4148, charge: 99)
    @Test
    fun `readBatteryState parse python vector`() {
        val state = Settings.ReadBatteryState().parseSample(bytes(0x11, 0x8C, 0x63, 0x34, 0x10))
        assertEquals(99, state.charge)
        assertEquals(4148, state.voltage)
    }

    // test_mac_address: b'\x11\x8b\x01\x07\x7b\x52\x8f\xc9\xe8' → "E8:C9:8F:52:7B:07"
    @Test
    fun `readMacAddress readCommand`() {
        assertArrayEquals(bytes(0x11, 0x8B), Settings.ReadMacAddress().readCommand)
    }

    @Test
    fun `readMacAddress parse python vector`() {
        val mac = Settings.ReadMacAddress()
            .parseSample(bytes(0x11, 0x8B, 0x01, 0x07, 0x7B, 0x52, 0x8F, 0xC9, 0xE8))
        assertEquals("E8:C9:8F:52:7B:07", mac)
    }

    // test_read_current_power: read command [0x11, 0x91]
    @Test
    fun `readPowerStatus readCommand python vector`() {
        assertArrayEquals(bytes(0x11, 0x91), Settings.ReadPowerStatus().readCommand)
    }

    // test_power_status_signal notifications: [0x11, 0x11, value]
    @Test
    fun `readPowerStatus parse`() {
        val cmd = Settings.ReadPowerStatus()
        assertEquals(0x00, cmd.parseSample(bytes(0x11, 0x11, 0x00)))
        assertEquals(0x01, cmd.parseSample(bytes(0x11, 0x11, 0x01)))
    }

    // test_read_current_charge: read command [0x11, 0x92]
    @Test
    fun `readChargeStatus readCommand python vector`() {
        assertArrayEquals(bytes(0x11, 0x92), Settings.ReadChargeStatus().readCommand)
    }

    // test_charge_status_signal notifications: [0x11, 0x12, value]
    @Test
    fun `readChargeStatus parse`() {
        val cmd = Settings.ReadChargeStatus()
        assertEquals(0x00, cmd.parseSample(bytes(0x11, 0x12, 0x00)))
        assertEquals(0x01, cmd.parseSample(bytes(0x11, 0x12, 0x01)))
    }

    // --- Settings revision ≥ 6 features ---

    // SetAdvertisingInterval with adType appends a 4th byte (revision ≥ 6).
    @Test
    fun `setAdvertisingInterval rev6 appends adType`() {
        val cmd = Settings.SetAdvertisingInterval(
            intervalMs = 417, timeoutSec = 0, adType = Settings.BleAdType.CONNECTABLE_UNDIRECTED,
        )
        assertArrayEquals(bytes(0x11, 0x02, 0x9B, 0x02, 0x00, 0x00), cmd.commandData)
    }

    @Test
    fun `setAdvertisingInterval rev6 directed`() {
        val cmd = Settings.SetAdvertisingInterval(
            intervalMs = 417, timeoutSec = 0, adType = Settings.BleAdType.CONNECTABLE_DIRECTED,
        )
        assertArrayEquals(bytes(0x11, 0x02, 0x9B, 0x02, 0x00, 0x01), cmd.commandData)
    }

    // Whitelist filter mode: [0x11, 0x13, mode].
    @Test
    fun `setWhitelistFilterMode all modes`() {
        val pairs = listOf(
            Settings.WhitelistFilterMode.ALLOW_FROM_ANY to 0x00,
            Settings.WhitelistFilterMode.SCAN_REQUESTS_ONLY to 0x01,
            Settings.WhitelistFilterMode.CONNECTION_REQUESTS_ONLY to 0x02,
            Settings.WhitelistFilterMode.SCAN_AND_CONNECTION_REQUESTS to 0x03,
        )
        for ((mode, raw) in pairs) {
            val cmd = Settings.SetWhitelistFilterMode(mode)
            assertArrayEquals(bytes(0x11, 0x13, raw), cmd.commandData, mode.name)
        }
    }

    // BluetoothAddress parses display-form MAC into LSB-first bytes
    // (matches MblMwBtleAddress wire layout).
    @Test
    fun `bluetoothAddress parse reverses to LSB first`() {
        val addr = Settings.BluetoothAddress.parse("E8:C9:8F:52:7B:07")
        assertEquals(Settings.BluetoothAddress.AddressType.PUBLIC, addr.type)
        assertArrayEquals(bytes(0x07, 0x7B, 0x52, 0x8F, 0xC9, 0xE8), addr.bytesLsbFirst)
    }

    @Test
    fun `bluetoothAddress parse accepts dash separator`() {
        val addr = Settings.BluetoothAddress.parse("E8-C9-8F-52-7B-07")
        assertArrayEquals(bytes(0x07, 0x7B, 0x52, 0x8F, 0xC9, 0xE8), addr.bytesLsbFirst)
    }

    @Test
    fun `bluetoothAddress parse rejects malformed`() {
        assertTrue(
            runCatching { Settings.BluetoothAddress.parse("not-a-mac") }
                .exceptionOrNull() is MetaWearException,
        )
        assertTrue(
            runCatching { Settings.BluetoothAddress.parse("E8:C9:8F:52:7B") } // 5 octets
                .exceptionOrNull() is MetaWearException,
        )
        assertTrue(
            runCatching { Settings.BluetoothAddress.parse("E8:C9:8F:52:7B:ZZ") } // invalid hex
                .exceptionOrNull() is MetaWearException,
        )
    }

    @Test
    fun `bluetoothAddress displayString round trip`() {
        val original = "E8:C9:8F:52:7B:07"
        val addr = Settings.BluetoothAddress.parse(original)
        assertEquals(original, addr.displayString)
    }

    // AddWhitelistAddress: [0x11, 0x14, index, address_type, b0..b5 LSB-first].
    @Test
    fun `addWhitelistAddress wire format`() {
        val addr = Settings.BluetoothAddress.parse("E8:C9:8F:52:7B:07")
        val cmd = Settings.AddWhitelistAddress(index = 0, address = addr)
        assertArrayEquals(
            bytes(
                0x11, 0x14,
                0x00, // slot index
                0x00, // public address type
                0x07, 0x7B, 0x52, 0x8F, 0xC9, 0xE8, // MAC LSB-first
            ),
            cmd.commandData,
        )
    }

    @Test
    fun `addWhitelistAddress random type`() {
        val addr = Settings.BluetoothAddress(
            type = Settings.BluetoothAddress.AddressType.RANDOM,
            bytesLsbFirst = bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
        )
        val cmd = Settings.AddWhitelistAddress(index = 2, address = addr)
        assertArrayEquals(
            bytes(0x11, 0x14, 0x02, 0x01, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
            cmd.commandData,
        )
    }

    // 3V regulator toggle: [0x11, 0x1C, enable].
    @Test
    fun `setThreeVoltPower enable`() {
        assertArrayEquals(bytes(0x11, 0x1C, 0x01), Settings.SetThreeVoltPower(true).commandData)
        assertArrayEquals(bytes(0x11, 0x1C, 0x00), Settings.SetThreeVoltPower(false).commandData)
    }

    // Force 1M PHY: [0x11, 0x1D, enable].
    @Test
    fun `setForce1MPhy enable`() {
        assertArrayEquals(bytes(0x11, 0x1D, 0x01), Settings.SetForce1MPhy(true).commandData)
        assertArrayEquals(bytes(0x11, 0x1D, 0x00), Settings.SetForce1MPhy(false).commandData)
    }

    // ---- Disconnect-event source ----

    // EventSource.disconnected() → module=settings (0x11), register=0x0A, dataId=0xFF.
    // Settings revision ≥ 2 required (verified at call site).
    @Test
    fun `disconnectEvent source`() {
        val src = EventSource.disconnected()
        assertEquals(Module.SETTINGS, src.module)
        assertEquals(0x0A, src.register)
        assertEquals(0xFF, src.dataId)
    }

    // ---- BLE advertising name validation ----
    //
    // Ported (via MWProductionGapTests.swift) from the Combine SDK's
    // NameUnitTests: test_IsNameValid_AcceptsValidNames / RejectsInvalidNames.

    @Test
    fun `accepts valid names`() {
        val cases = listOf(
            "Antidisestablishmentarian", // 25 chars — at limit minus 1
            "MetaWear",
            "MetaWear ",
            " MetaWear",
            "_",
            "-",
        )
        for (name in cases) {
            assertTrue(Settings.isNameValid(name), "expected valid: $name")
        }
    }

    @Test
    fun `rejects invalid names`() {
        val cases = listOf(
            "Pneumonoultramicroscopicsilicovolcanoconiosis", // > 26 chars
            "MetaWear $", // $ not in allowed set
            "MetaWear ~", // ~ not in allowed set
            "MetaWear —", // em-dash (non-ASCII)
            "* MetaWear ", // * not in allowed set
            "MetaWear ∀", // non-ASCII unicode
            "😂", // emoji
            "", // empty
        )
        for (name in cases) {
            assertFalse(Settings.isNameValid(name), "expected invalid: $name")
        }
    }

    @Test
    fun `rejects exactly 27 chars`() {
        assertFalse(Settings.isNameValid("A".repeat(27)))
    }

    @Test
    fun `accepts exactly 26 chars`() {
        assertTrue(Settings.isNameValid("A".repeat(26)))
    }

    @Test
    fun `validating factory rejects emoji`() {
        assertTrue(
            runCatching { Settings.SetDeviceName.validating("😂") }
                .exceptionOrNull() is MetaWearException,
        )
    }

    @Test
    fun `validating factory rejects over length`() {
        assertTrue(
            runCatching { Settings.SetDeviceName.validating("A".repeat(27)) }
                .exceptionOrNull() is MetaWearException,
        )
    }

    @Test
    fun `validating factory accepts valid name`() {
        val cmd = Settings.SetDeviceName.validating("My-Device 1")
        assertEquals("My-Device 1", cmd.name)
        assertEquals(0x11, cmd.commandData[0].toInt() and 0xFF)
        assertEquals(0x01, cmd.commandData[1].toInt() and 0xFF)
    }
}
