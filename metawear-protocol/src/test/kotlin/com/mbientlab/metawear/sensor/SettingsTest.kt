package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.autoReply
import com.mbientlab.metawear.autoReplyModuleDiscovery
import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.makeConnectableTransport
import com.mbientlab.metawear.model.BatteryState
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Pollable
import com.mbientlab.metawear.transport.MockBleTransport
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Ported from the settings-module suites in MWProductionGapTests.swift
// ("Settings Commands", "Settings — BLE advertising name validation") plus the
// settings Pollable conformances from MWMiscReadablesTests.swift. Reference
// vectors from MetaWear-SDK-Cpp/test/test_settings.py.

/** Ported from the "Settings Commands" suite. */
class SettingsCommandTest {

    @Test fun setDeviceName_correctBytes() {
        val data = Settings.SetDeviceName("MyBoard").commandData
        assertEquals(0x11, data[0].toInt() and 0xFF) // settings module
        assertEquals(0x01, data[1].toInt() and 0xFF) // DEVICE_NAME register
        assertArrayEquals("MyBoard".toByteArray(Charsets.UTF_8), data.copyOfRange(2, data.size))
    }

    @Test fun setDeviceName_truncatesAtMaxLength() {
        // 40-char input should be truncated to 26 bytes of payload (+2 header).
        val long = "A".repeat(40)
        assertEquals(2 + Settings.MAX_DEVICE_NAME_LENGTH, Settings.SetDeviceName(long).commandData.size)
    }

    @Test fun setTxPower_correctByte() =
        // -4 dBm as an unsigned wire byte = 0xFC
        assertArrayEquals(bytes(0x11, 0x03, 0xFC), Settings.SetTxPower(Settings.TxPower.MINUS_4).commandData)

    @Test fun startAdvertising_command() =
        assertArrayEquals(bytes(0x11, 0x05), Settings.StartAdvertising().commandData)

    @Test fun setAdvertisingInterval_encodesAs0_625Units() {
        // 417ms / 0.625 = 667.2 → 667 units = 0x029B
        val data = Settings.SetAdvertisingInterval(intervalMs = 417, timeoutSec = 0).commandData
        assertEquals(0x11, data[0].toInt() and 0xFF)
        assertEquals(0x02, data[1].toInt() and 0xFF)
        val units = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        assertEquals((417 / 0.625).toInt(), units)
        assertEquals(0, data[4].toInt() and 0xFF) // timeout
    }

    @Test fun connectionParams_correctBytes() {
        val data = Settings.SetConnectionParameters(
            minInterval = 6, maxInterval = 24, latency = 0, timeout = 500,
        ).commandData
        assertArrayEquals(
            bytes(
                0x11, 0x09,
                0x06, 0x00, // min=6 LE
                0x18, 0x00, // max=24 LE
                0x00, 0x00, // latency=0
                0xF4, 0x01, // timeout=500 LE
            ),
            data,
        )
    }

    @Test fun lowLatencyPreset() {
        val cmd = Settings.SetConnectionParameters.lowLatency
        assertEquals(6, cmd.minInterval)
        assertEquals(6, cmd.maxInterval)
        assertEquals(0, cmd.latency)
    }

    @Test fun powerSavingPreset() {
        val cmd = Settings.SetConnectionParameters.powerSaving
        assertTrue(cmd.latency > 0)
        assertTrue(cmd.minInterval > 24)
    }

    // ---- Reference vectors from MetaWear-SDK-Cpp/test/test_settings.py ----

    // test_set_name: set device name "AntiWare"
    // Expected: [0x11, 0x01, 0x41, 0x6e, 0x74, 0x69, 0x57, 0x61, 0x72, 0x65]
    @Test fun setDeviceName_pythonVector_AntiWare() =
        assertArrayEquals(
            bytes(0x11, 0x01, 0x41, 0x6E, 0x74, 0x69, 0x57, 0x61, 0x72, 0x65),
            Settings.SetDeviceName("AntiWare").commandData,
        )

    // Validating factory must produce the same wire bytes as the plain
    // constructor for an accepted name.
    @Test fun setDeviceName_validating_AntiWare() =
        assertArrayEquals(
            bytes(0x11, 0x01, 0x41, 0x6E, 0x74, 0x69, 0x57, 0x61, 0x72, 0x65),
            Settings.SetDeviceName.validating("AntiWare").commandData,
        )

    // test_set_tx_power: set_tx_power(-20) → [0x11, 0x03, 0xec]
    @Test fun setTxPower_pythonVector_minus20() =
        assertArrayEquals(bytes(0x11, 0x03, 0xEC), Settings.SetTxPower(Settings.TxPower.MINUS_20).commandData)

    // test_set_ad_interval (default rev >= 2): set_ad_interval(417, 0)
    // Expected: [0x11, 0x02, 0x9b, 0x02, 0x00]
    // Encoding: 417 ms / 0.625 = 667 units (0x029B), timeout byte = 0.
    @Test fun setAdvertisingInterval_pythonVector_417ms_0s() =
        assertArrayEquals(
            bytes(0x11, 0x02, 0x9B, 0x02, 0x00),
            Settings.SetAdvertisingInterval(intervalMs = 417, timeoutSec = 0).commandData,
        )

    // test_set_ad_interval (Revision1): set_ad_interval(417, 180)
    // Expected: [0x11, 0x02, 0x9b, 0x02, 0xb4]
    @Test fun setAdvertisingInterval_pythonVector_rev1_417ms_180s() =
        assertArrayEquals(
            bytes(0x11, 0x02, 0x9B, 0x02, 0xB4),
            Settings.SetAdvertisingInterval(intervalMs = 417, timeoutSec = 180).commandData,
        )

    // test_set_conn_params: set_connection_parameters(750ms, 1000ms, 128, 16384ms)
    // Expected: [0x11, 0x09, 0x58, 0x02, 0x20, 0x03, 0x80, 0x00, 0x66, 0x06]
    // Kotlin takes raw 1.25 ms units for min/max and 10 ms units for timeout.
    //   750 / 1.25  = 600 = 0x0258
    //   1000 / 1.25 = 800 = 0x0320
    //   latency     = 128 = 0x0080
    //   16384 / 10  = 1638 ≈ 0x0666
    @Test fun setConnectionParameters_pythonVector() =
        assertArrayEquals(
            bytes(
                0x11, 0x09,
                0x58, 0x02,
                0x20, 0x03,
                0x80, 0x00,
                0x66, 0x06,
            ),
            Settings.SetConnectionParameters(
                minInterval = 600,
                maxInterval = 800,
                latency = 128,
                timeout = 1638,
            ).commandData,
        )

    // test_start_advertising: [0x11, 0x05]
    @Test fun startAdvertising_pythonVector() =
        assertArrayEquals(bytes(0x11, 0x05), Settings.StartAdvertising().commandData)

    // test_set_scan_response (21-byte payload, split across PARTIAL_SCAN_RESPONSE (0x08)
    // and SCAN_RESPONSE (0x07)).
    @Test fun setScanResponse_pythonVector_splitWrite() {
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

    @Test fun setScanResponse_shortPayload_singleWrite() {
        // Short payload (≤ 13 bytes) should emit a single write to register 0x07.
        val cmds = Settings.SetScanResponse(bytes(0x01, 0x02, 0x03)).commands
        assertEquals(1, cmds.size)
        assertArrayEquals(bytes(0x11, 0x07, 0x01, 0x02, 0x03), cmds[0])
    }

    // test_read_battery_state in `test_settings.py` asserts `[0x11, 0xCC]` because
    // it calls `mbl_mw_datasignal_read` directly on a freshly-constructed signal,
    // where the C++ constructor leaves the SILENT bit (0x40) enabled. The
    // *production* Combine SDK always subscribes before reading, and `subscribe()`
    // on a readable signal clears the silent bit. So the real wire byte ships as
    // `0x8C`, and that is what MMS firmware actually responds to — `0xCC` is
    // silently dropped on the firmware floor. We assert the live wire shape,
    // not the idle-signal shape the Python unit test happens to capture.
    @Test fun readBatteryState_readCommand_matchesProductionWire() =
        assertArrayEquals(bytes(0x11, 0x8C), Settings.ReadBatteryState().readCommand)

    // test_battery_state_data: b'\x11\x8c\x63\x34\x10' → BatteryState(voltage: 4148, charge: 99)
    @Test fun readBatteryState_parse_pythonVector() {
        val state = Settings.ReadBatteryState().parseSample(bytes(0x11, 0x8C, 0x63, 0x34, 0x10))
        assertEquals(99, state.charge)
        assertEquals(4148, state.voltage)
    }

    // test_mac_address: b'\x11\x8b\x01\x07\x7b\x52\x8f\xc9\xe8' → "E8:C9:8F:52:7B:07"
    @Test fun readMacAddress_readCommand() =
        assertArrayEquals(bytes(0x11, 0x8B), Settings.ReadMacAddress().readCommand)

    @Test fun readMacAddress_parse_pythonVector() =
        assertEquals(
            "E8:C9:8F:52:7B:07",
            Settings.ReadMacAddress().parseSample(bytes(0x11, 0x8B, 0x01, 0x07, 0x7B, 0x52, 0x8F, 0xC9, 0xE8)),
        )

    // test_read_current_power: read command [0x11, 0x91]
    @Test fun readPowerStatus_readCommand_pythonVector() =
        assertArrayEquals(bytes(0x11, 0x91), Settings.ReadPowerStatus().readCommand)

    // test_power_status_signal notifications: [0x11, 0x11, value]
    @Test fun readPowerStatus_parse() {
        val cmd = Settings.ReadPowerStatus()
        assertEquals(0x00, cmd.parseSample(bytes(0x11, 0x11, 0x00)))
        assertEquals(0x01, cmd.parseSample(bytes(0x11, 0x11, 0x01)))
    }

    // test_read_current_charge: read command [0x11, 0x92]
    @Test fun readChargeStatus_readCommand_pythonVector() =
        assertArrayEquals(bytes(0x11, 0x92), Settings.ReadChargeStatus().readCommand)

    // test_charge_status_signal notifications: [0x11, 0x12, value]
    @Test fun readChargeStatus_parse() {
        val cmd = Settings.ReadChargeStatus()
        assertEquals(0x00, cmd.parseSample(bytes(0x11, 0x12, 0x00)))
        assertEquals(0x01, cmd.parseSample(bytes(0x11, 0x12, 0x01)))
    }

    // ---- Settings revision ≥ 6 features ----

    // SetAdvertisingInterval with adType appends a 4th byte (revision ≥ 6).
    // 417 ms / 0.625 = 667 = 0x029B, timeout = 0, adType = CONNECTABLE_UNDIRECTED (0).
    @Test fun setAdvertisingInterval_rev6_appendsAdType() =
        assertArrayEquals(
            bytes(0x11, 0x02, 0x9B, 0x02, 0x00, 0x00),
            Settings.SetAdvertisingInterval(
                intervalMs = 417, timeoutSec = 0, adType = Settings.BleAdType.CONNECTABLE_UNDIRECTED,
            ).commandData,
        )

    @Test fun setAdvertisingInterval_rev6_directed() =
        assertArrayEquals(
            bytes(0x11, 0x02, 0x9B, 0x02, 0x00, 0x01),
            Settings.SetAdvertisingInterval(
                intervalMs = 417, timeoutSec = 0, adType = Settings.BleAdType.CONNECTABLE_DIRECTED,
            ).commandData,
        )

    // Whitelist filter mode: [0x11, 0x13, mode].
    @Test fun setWhitelistFilterMode_allModes() {
        val pairs = listOf(
            Settings.WhitelistFilterMode.ALLOW_FROM_ANY to 0x00,
            Settings.WhitelistFilterMode.SCAN_REQUESTS_ONLY to 0x01,
            Settings.WhitelistFilterMode.CONNECTION_REQUESTS_ONLY to 0x02,
            Settings.WhitelistFilterMode.SCAN_AND_CONNECTION_REQUESTS to 0x03,
        )
        for ((mode, raw) in pairs) {
            assertArrayEquals(bytes(0x11, 0x13, raw), Settings.SetWhitelistFilterMode(mode).commandData)
        }
    }

    // BluetoothAddress parses display-form MAC into LSB-first bytes
    // (matches MblMwBtleAddress wire layout).
    @Test fun bluetoothAddress_parse_reversesToLsbFirst() {
        val addr = Settings.BluetoothAddress.parse("E8:C9:8F:52:7B:07")
        assertEquals(Settings.BluetoothAddress.AddressType.PUBLIC, addr.type)
        assertArrayEquals(bytes(0x07, 0x7B, 0x52, 0x8F, 0xC9, 0xE8), addr.bytesLsbFirst)
    }

    @Test fun bluetoothAddress_parse_acceptsDashSeparator() {
        val addr = Settings.BluetoothAddress.parse("E8-C9-8F-52-7B-07")
        assertArrayEquals(bytes(0x07, 0x7B, 0x52, 0x8F, 0xC9, 0xE8), addr.bytesLsbFirst)
    }

    @Test fun bluetoothAddress_parse_rejectsMalformed() {
        assertThrows(MetaWearException.OperationFailed::class.java) {
            Settings.BluetoothAddress.parse("not-a-mac")
        }
        assertThrows(MetaWearException.OperationFailed::class.java) {
            Settings.BluetoothAddress.parse("E8:C9:8F:52:7B")       // 5 octets
        }
        assertThrows(MetaWearException.OperationFailed::class.java) {
            Settings.BluetoothAddress.parse("E8:C9:8F:52:7B:ZZ")    // invalid hex
        }
    }

    @Test fun bluetoothAddress_displayString_roundTrip() {
        val original = "E8:C9:8F:52:7B:07"
        assertEquals(original, Settings.BluetoothAddress.parse(original).displayString)
    }

    @Test fun bluetoothAddress_rejectsWrongByteCount() {
        assertThrows(MetaWearException.OperationFailed::class.java) {
            Settings.BluetoothAddress(
                Settings.BluetoothAddress.AddressType.PUBLIC,
                bytes(0x01, 0x02, 0x03),
            )
        }
    }

    // AddWhitelistAddress: [0x11, 0x14, index, address_type, b0..b5 LSB-first].
    @Test fun addWhitelistAddress_wireFormat() {
        val addr = Settings.BluetoothAddress.parse("E8:C9:8F:52:7B:07")
        assertArrayEquals(
            bytes(
                0x11, 0x14,
                0x00,                                    // slot index
                0x00,                                    // public address type
                0x07, 0x7B, 0x52, 0x8F, 0xC9, 0xE8,      // MAC LSB-first
            ),
            Settings.AddWhitelistAddress(index = 0, address = addr).commandData,
        )
    }

    @Test fun addWhitelistAddress_randomType() {
        val addr = Settings.BluetoothAddress(
            Settings.BluetoothAddress.AddressType.RANDOM,
            bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
        )
        assertArrayEquals(
            bytes(0x11, 0x14, 0x02, 0x01, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
            Settings.AddWhitelistAddress(index = 2, address = addr).commandData,
        )
    }

    // 3V regulator toggle: [0x11, 0x1C, enable].
    @Test fun setThreeVoltPower_enable() {
        assertArrayEquals(bytes(0x11, 0x1C, 0x01), Settings.SetThreeVoltPower(true).commandData)
        assertArrayEquals(bytes(0x11, 0x1C, 0x00), Settings.SetThreeVoltPower(false).commandData)
    }

    // Force 1M PHY: [0x11, 0x1D, enable].
    @Test fun setForce1MPhy_enable() {
        assertArrayEquals(bytes(0x11, 0x1D, 0x01), Settings.SetForce1MPhy(true).commandData)
        assertArrayEquals(bytes(0x11, 0x1D, 0x00), Settings.SetForce1MPhy(false).commandData)
    }
}

/**
 * Ported from the "Settings — BLE advertising name validation" suite
 * (itself ported from the Combine SDK's NameUnitTests).
 */
class DeviceNameValidationTest {

    @Test fun acceptsValidNames() {
        val cases = listOf(
            "Antidisestablishmentarian",  // 25 chars — at limit minus 1
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

    @Test fun rejectsInvalidNames() {
        val cases = listOf(
            "Pneumonoultramicroscopicsilicovolcanoconiosis",  // > 26 chars
            "MetaWear \$",       // $ not in allowed set
            "MetaWear ~",        // ~ not in allowed set
            "MetaWear —",        // em-dash (non-ASCII)
            "* MetaWear ",       // * not in allowed set
            "MetaWear ∀",        // non-ASCII unicode
            "😂",                // emoji
            "",                  // empty
        )
        for (name in cases) {
            assertFalse(Settings.isNameValid(name), "expected invalid: $name")
        }
    }

    @Test fun rejectsExactly27Chars() =
        assertFalse(Settings.isNameValid("A".repeat(27)))

    @Test fun acceptsExactly26Chars() =
        assertTrue(Settings.isNameValid("A".repeat(26)))

    @Test fun validatingInit_rejectsEmoji() {
        assertThrows(MetaWearException.OperationFailed::class.java) {
            Settings.SetDeviceName.validating("😂")
        }
    }

    @Test fun validatingInit_rejectsOverLength() {
        assertThrows(MetaWearException.OperationFailed::class.java) {
            Settings.SetDeviceName.validating("A".repeat(27))
        }
    }

    @Test fun validatingInit_acceptsValidName() {
        val cmd = Settings.SetDeviceName.validating("My-Device 1")
        assertEquals("My-Device 1", cmd.name)
        assertEquals(0x11, cmd.commandData[0].toInt() and 0xFF)
        assertEquals(0x01, cmd.commandData[1].toInt() and 0xFF)
    }
}

/** Ported from the settings Pollable conformances in MWMiscReadablesTests.swift. */
class SettingsPollableTest {

    @Test fun batteryState_isPollable() {
        val pollable: Pollable<BatteryState> = Settings.ReadBatteryState()
        assertEquals(Module.SETTINGS, pollable.module)
    }

    @Test fun macAddress_isPollable() {
        val pollable: Pollable<String> = Settings.ReadMacAddress()
        assertEquals(Module.SETTINGS, pollable.module)
    }

    @Test fun powerStatus_isPollable() {
        val pollable: Pollable<Int> = Settings.ReadPowerStatus()
        assertEquals(Module.SETTINGS, pollable.module)
    }

    @Test fun chargeStatus_isPollable() {
        val pollable: Pollable<Int> = Settings.ReadChargeStatus()
        assertEquals(Module.SETTINGS, pollable.module)
    }
}

/** Device-level checks for the settings readables and the [setScanResponse] extension. */
class SettingsDeviceTest {

    private suspend fun TestScope.connectedDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice("AA:BB:CC:DD:EE:FF", transport, backgroundScope)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport)
        device.connect()
        discovery.cancel()
        return device to transport
    }

    @Test
    fun `setScanResponse splits a long payload across registers 0x08 and 0x07`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()

        device.setScanResponse(
            bytes(
                0x03, 0x03, 0xD8, 0xFE, 0x10, 0x16, 0xD8, 0xFE,
                0x00, 0x12, 0x00, 0x6D, 0x62, 0x69, 0x65, 0x6E,
                0x74, 0x6C, 0x61, 0x62, 0x00,
            ),
        )

        val cmds = transport.writtenCommands
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
    fun `setScanResponse sends a short payload as one write`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()

        device.setScanResponse(bytes(0x01, 0x02, 0x03))

        assertEquals(1, transport.writtenCommands.size)
        assertArrayEquals(bytes(0x11, 0x07, 0x01, 0x02, 0x03), transport.writtenCommands[0])
    }

    @Test
    fun `device reads battery state through the settings readable`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x11 && (cmd[1].toInt() and 0xFF) == 0x8C) {
                bytes(0x11, 0x8C, 0x63, 0x34, 0x10) // python vector: charge 99 %, 4148 mV
            } else {
                null
            }
        }

        val battery = device.read(Settings.ReadBatteryState())
        replies.cancel()

        assertEquals(99, battery.value.charge)
        assertEquals(4148, battery.value.voltage)
    }

    @Test
    fun `device reads MAC address through the settings readable`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x11 && (cmd[1].toInt() and 0xFF) == 0x8B) {
                bytes(0x11, 0x8B, 0x01, 0x07, 0x7B, 0x52, 0x8F, 0xC9, 0xE8)
            } else {
                null
            }
        }

        val mac = device.read(Settings.ReadMacAddress())
        replies.cancel()

        assertEquals("E8:C9:8F:52:7B:07", mac.value)
    }
}
