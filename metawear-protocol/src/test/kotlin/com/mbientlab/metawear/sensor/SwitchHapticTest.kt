package com.mbientlab.metawear.sensor

import app.cash.turbine.test
import com.mbientlab.metawear.DEFAULT_PRESENT_MODULES
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.autoReplyModuleDiscovery
import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.makeConnectableTransport
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.transport.MockBleTransport
import com.mbientlab.metawear.transport.Uuids
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Switch (module 0x01) and haptic (module 0x08) suites.

/** Switch command byte-layout tests. */
class SwitchCommandTest {

    private val sensor = Switch()

    // The switch's "subscribe = enable" is the same wire write
    // (`[0x01, 0x01, 0x01]`). The generic `startStream` already emits that
    // packet as the data-signal subscribe, so all four module-level hooks
    // below must stay no-ops to avoid sending the identical write twice. The
    // wire-level integration test `switch stream sends subscribe and
    // unsubscribe` (further down) is what pins the actual
    // `[0x01, 0x01, 0x01]` / `[0x01, 0x01, 0x00]` bytes on the BLE side.
    @Test fun enableCommand_isEmpty() = assertTrue(sensor.enableCommand.isEmpty())
    @Test fun disableCommand_isEmpty() = assertTrue(sensor.disableCommand.isEmpty())
    @Test fun startCommand_isEmpty() = assertTrue(sensor.startCommand.isEmpty())
    @Test fun stopCommand_isEmpty() = assertTrue(sensor.stopCommand.isEmpty())

    @Test fun module_isSwitch() = assertEquals(Module.SWITCH, sensor.module)

    @Test fun noPackedRegister() = assertNull(sensor.packedDataRegister)

    @Test fun noConfigureCommands() = assertTrue(sensor.configureCommands.isEmpty())

    @Test fun parsesPressed() =
        assertEquals(true, sensor.parseSample(bytes(0x01, 0x01, 0x01)))

    @Test fun parsesReleased() =
        assertEquals(false, sensor.parseSample(bytes(0x01, 0x01, 0x00)))

    @Test fun parsesTooShort_throws() {
        assertThrows(MetaWearException.OperationFailed::class.java) {
            sensor.parseSample(bytes(0x01, 0x01))
        }
    }

    // Python test_mbl_mw_switch_get_data_pushed — state byte 0x01 → pressed.
    @Test fun parsesPressed_pythonVector() =
        assertEquals(true, sensor.parseSample(bytes(0x01, 0x01, 0x01)))

    // Python test_mbl_mw_switch_get_data_released — state byte 0x00 → released.
    @Test fun parsesReleased_pythonVector() =
        assertEquals(false, sensor.parseSample(bytes(0x01, 0x01, 0x00)))

    // Any non-zero state byte is treated as "pressed" only when it is exactly 0x01.
    // The firmware never emits other values, but the parser defends the invariant.
    @Test fun nonOneStateByte_parsesAsReleased() =
        assertEquals(false, sensor.parseSample(bytes(0x01, 0x01, 0x02)))
}

/** Switch live-streaming tests. */
class SwitchStreamingTest {

    /** Connect against the stub board with the switch module reported present. */
    private suspend fun TestScope.connectedSwitchDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice("AA:BB:CC:DD:EE:FF", transport, backgroundScope)
        val discovery = backgroundScope.autoReplyModuleDiscovery(
            transport,
            presentModules = DEFAULT_PRESENT_MODULES + 0x01, // switch module present
        )
        device.connect()
        discovery.cancel()
        return device to transport
    }

    @Test
    fun `switch stream delivers pressed then released`() = runTest {
        val (device, transport) = connectedSwitchDevice()
        val stream = device.startStream(Switch(), usePacked = false)

        stream.test {
            // Python test_mbl_mw_switch_get_data_pushed
            transport.inject(bytes(0x01, 0x01, 0x01), Uuids.notify)
            assertEquals(true, awaitItem().value)
            // Python test_mbl_mw_switch_get_data_released
            transport.inject(bytes(0x01, 0x01, 0x00), Uuids.notify)
            assertEquals(false, awaitItem().value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switch stream sends subscribe and unsubscribe`() = runTest {
        val (device, transport) = connectedSwitchDevice()
        transport.clearWrites()

        // `startStream()` issues the subscribe command; `stopStreaming()` issues the unsubscribe.
        val sensor = Switch()
        device.startStream(sensor, usePacked = false)
        device.stopStreaming(sensor)

        val cmds = transport.writtenCommands
        assertTrue(
            cmds.any { it.contentEquals(bytes(0x01, 0x01, 0x01)) },
            "startStream() must send subscribe [0x01, 0x01, 0x01]",
        )
        assertTrue(
            cmds.any { it.contentEquals(bytes(0x01, 0x01, 0x00)) },
            "stopStreaming() must send unsubscribe [0x01, 0x01, 0x00]",
        )
        // The no-op enable/start/stop/disable hooks must not add extra writes.
        assertEquals(2, cmds.size)
        assertEquals(DeviceState.Idle, device.state.value)
    }
}

/** Haptic command byte-layout tests. */
class HapticCommandTest {

    @Test fun motor_correctBytes() {
        // dutyCycle 80% → min(248, 80*248/100) = 198 = 0xC6
        // pulseWidth 500 = 0x01F4 → LE: 0xF4, 0x01
        assertArrayEquals(
            bytes(0x08, 0x01, 0xC6, 0xF4, 0x01, 0x00),
            Haptic.motor(dutyCycle = 80, pulseWidth = 500).commandData,
        )
    }

    @Test fun buzzer_correctBytes() {
        // Buzzer always uses 0x7F regardless of dutyCycle
        // pulseWidth 200 = 0x00C8 → LE: 0xC8, 0x00
        assertArrayEquals(
            bytes(0x08, 0x01, 0x7F, 0xC8, 0x00, 0x01),
            Haptic.buzzer(pulseWidth = 200).commandData,
        )
    }

    @Test fun motor_mode_byte() {
        val cmd = Haptic.motor(dutyCycle = 100, pulseWidth = 100)
        assertEquals(0x00, cmd.commandData[5].toInt() and 0xFF) // mode = motor
    }

    @Test fun buzzer_mode_byte() {
        val cmd = Haptic.buzzer(pulseWidth = 100)
        assertEquals(0x01, cmd.commandData[5].toInt() and 0xFF) // mode = buzzer
    }

    @Test fun dutyCycleClamped_at100() {
        val cmd = Haptic.Pulse(Haptic.Mode.MOTOR, dutyCycle = 200, pulseWidth = 100)
        assertEquals(100, cmd.dutyCycle)
        // 100% → min(248, 100*248/100) = 248 = 0xF8
        assertEquals(0xF8, cmd.commandData[2].toInt() and 0xFF)
    }

    @Test fun motor_dutyCycle_100percent_scalesToMax() {
        // 100% → 248 = 0xF8; pulseWidth 5000 = 0x1388 → LE: 0x88, 0x13
        assertArrayEquals(
            bytes(0x08, 0x01, 0xF8, 0x88, 0x13, 0x00),
            Haptic.motor(dutyCycle = 100, pulseWidth = 5000).commandData,
        )
    }

    @Test fun motor_dutyCycle_scaling() {
        // 50% → min(248, 50*248/100) = 124 = 0x7C
        val cmd = Haptic.motor(dutyCycle = 50, pulseWidth = 100)
        assertEquals(0x7C, cmd.commandData[2].toInt() and 0xFF)
    }

    @Test fun pulseWidth_littleEndian() {
        val cmd = Haptic.motor(dutyCycle = 50, pulseWidth = 0x0102)
        // 0x0102 LE = [0x02, 0x01]
        assertEquals(0x02, cmd.commandData[3].toInt() and 0xFF)
        assertEquals(0x01, cmd.commandData[4].toInt() and 0xFF)
    }

    @Test fun module_byte() {
        val cmd = Haptic.motor()
        assertEquals(0x08, cmd.commandData[0].toInt() and 0xFF) // haptic module
        assertEquals(0x01, cmd.commandData[1].toInt() and 0xFF) // PULSE register
    }

    @Test fun commandLength() {
        // [module, register, dutyCycle, width_lo, width_hi, mode] = 6 bytes
        assertEquals(6, Haptic.motor().commandData.size)
        assertEquals(6, Haptic.buzzer().commandData.size)
    }
}
