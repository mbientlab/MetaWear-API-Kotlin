package com.mbientlab.metawear.sensor

import app.cash.turbine.test
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.autoReplyModuleDiscovery
import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.makeConnectableTransport
import com.mbientlab.metawear.transport.MockBleTransport
import com.mbientlab.metawear.transport.Uuids
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ported from the GPIO suites of MWGPIOLEDTests.swift (the LED suites live
 * with the LED module port).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent
class GpioTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    private suspend fun TestScope.connectedDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice(mac, transport, backgroundScope)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport)
        device.connect()
        discovery.cancel()
        return device to transport
    }

    // ---- GPIO output commands ----

    @Test
    fun `setHigh command bytes`() =
        assertArrayEquals(bytes(0x05, 0x01, 0x02), Gpio.SetHigh(pin = 2).commandData)

    @Test
    fun `setLow command bytes`() =
        assertArrayEquals(bytes(0x05, 0x02, 0x00), Gpio.SetLow(pin = 0).commandData)

    @Test
    fun `setPull up command bytes`() =
        assertArrayEquals(bytes(0x05, 0x03, 0x01), Gpio.SetPull(pin = 1, pull = Gpio.Pull.UP).commandData)

    @Test
    fun `setPull down command bytes`() =
        assertArrayEquals(bytes(0x05, 0x04, 0x01), Gpio.SetPull(pin = 1, pull = Gpio.Pull.DOWN).commandData)

    @Test
    fun `setPull none command bytes`() =
        assertArrayEquals(bytes(0x05, 0x05, 0x03), Gpio.SetPull(pin = 3, pull = Gpio.Pull.NONE).commandData)

    @Test
    fun `configurePinChange rising`() =
        assertArrayEquals(
            bytes(0x05, 0x09, 0x00, 0x01),
            Gpio.ConfigurePinChange(pin = 0, type = Gpio.ChangeType.RISING).commandData,
        )

    @Test
    fun `configurePinChange falling`() =
        assertArrayEquals(
            bytes(0x05, 0x09, 0x00, 0x02),
            Gpio.ConfigurePinChange(pin = 0, type = Gpio.ChangeType.FALLING).commandData,
        )

    @Test
    fun `configurePinChange any`() =
        assertArrayEquals(
            bytes(0x05, 0x09, 0x02, 0x03),
            Gpio.ConfigurePinChange(pin = 2, type = Gpio.ChangeType.ANY).commandData,
        )

    // Python test_set_pin_change_type reference vectors.
    @Test
    fun `configurePinChange pin 6 falling`() =
        assertArrayEquals(
            bytes(0x05, 0x09, 0x06, 0x02),
            Gpio.ConfigurePinChange(pin = 6, type = Gpio.ChangeType.FALLING).commandData,
        )

    @Test
    fun `configurePinChange pin 7 rising`() =
        assertArrayEquals(
            bytes(0x05, 0x09, 0x07, 0x01),
            Gpio.ConfigurePinChange(pin = 7, type = Gpio.ChangeType.RISING).commandData,
        )

    // Python test_pin_monitor_start / test_pin_monitor_stop reference vectors.
    @Test
    fun `startPinMonitor pin 5`() =
        assertArrayEquals(bytes(0x05, 0x0B, 0x05, 0x01), Gpio.StartPinMonitor(pin = 5).commandData)

    @Test
    fun `stopPinMonitor pin 6`() =
        assertArrayEquals(bytes(0x05, 0x0B, 0x06, 0x00), Gpio.StopPinMonitor(pin = 6).commandData)

    // ---- GPIO read command builders (byte-only, no transport) ----

    // Python test_read_digital_input
    @Test
    fun `digitalRead pin 4`() =
        assertArrayEquals(bytes(0x05, 0x88, 0x04), Gpio.DigitalRead(pin = 4).commandData)

    // Python test_read_digital_input_silent
    @Test
    fun `digitalRead silent pin 4`() =
        assertArrayEquals(bytes(0x05, 0xC8, 0x04), Gpio.DigitalRead(pin = 4, silent = true).commandData)

    // Python test_read_analog_input (ABS_REF)
    @Test
    fun `analogRead absolute reference pin 3`() =
        assertArrayEquals(
            bytes(0x05, 0x86, 0x03),
            Gpio.AnalogRead(mode = Gpio.AnalogReadMode.ABSOLUTE_REFERENCE, pin = 3).commandData,
        )

    // Python test_read_analog_input (ADC)
    @Test
    fun `analogRead adc pin 2`() =
        assertArrayEquals(
            bytes(0x05, 0x87, 0x02),
            Gpio.AnalogRead(mode = Gpio.AnalogReadMode.ADC, pin = 2).commandData,
        )

    // Python test_read_analog_input_silent (ABS_REF)
    @Test
    fun `analogRead silent absolute reference pin 3`() =
        assertArrayEquals(
            bytes(0x05, 0xC6, 0x03),
            Gpio.AnalogRead(mode = Gpio.AnalogReadMode.ABSOLUTE_REFERENCE, pin = 3, silent = true).commandData,
        )

    // Python test_read_analog_input_silent (ADC)
    @Test
    fun `analogRead silent adc pin 2`() =
        assertArrayEquals(
            bytes(0x05, 0xC7, 0x02),
            Gpio.AnalogRead(mode = Gpio.AnalogReadMode.ADC, pin = 2, silent = true).commandData,
        )

    // Python TestGpioEnhancedAnalogRead.test_read_analog_no_parameters — defaults
    // expand to [pullup=0xFF, pulldown=0xFF, delay=0x00, virtual=0xFF] with silent bit on.
    @Test
    fun `enhanced analogRead defaults absolute reference pin 3`() =
        assertArrayEquals(
            bytes(0x05, 0xC6, 0x03, 0xFF, 0xFF, 0x00, 0xFF),
            Gpio.AnalogRead(
                mode = Gpio.AnalogReadMode.ABSOLUTE_REFERENCE, pin = 3,
                silent = true, parameters = Gpio.AnalogReadParameters.DEFAULTS,
            ).commandData,
        )

    @Test
    fun `enhanced analogRead defaults adc pin 2`() =
        assertArrayEquals(
            bytes(0x05, 0xC7, 0x02, 0xFF, 0xFF, 0x00, 0xFF),
            Gpio.AnalogRead(
                mode = Gpio.AnalogReadMode.ADC, pin = 2,
                silent = true, parameters = Gpio.AnalogReadParameters.DEFAULTS,
            ).commandData,
        )

    // Python TestGpioEnhancedAnalogRead.test_read_analog_with_parameters
    // pullup=1, pulldown=2, delay_us=10 (→ 10>>2 = 2), virtual=0x15. Non-silent.
    @Test
    fun `enhanced analogRead with parameters absolute reference pin 3`() {
        val p = Gpio.AnalogReadParameters(
            pullupPin = 1, pulldownPin = 2, virtualPin = 0x15, delayMicroseconds = 10,
        )
        assertArrayEquals(
            bytes(0x05, 0x86, 0x03, 0x01, 0x02, 0x02, 0x15),
            Gpio.AnalogRead(mode = Gpio.AnalogReadMode.ABSOLUTE_REFERENCE, pin = 3, parameters = p).commandData,
        )
    }

    @Test
    fun `enhanced analogRead with parameters adc pin 2`() {
        val p = Gpio.AnalogReadParameters(
            pullupPin = 1, pulldownPin = 2, virtualPin = 0x15, delayMicroseconds = 10,
        )
        assertArrayEquals(
            bytes(0x05, 0x87, 0x02, 0x01, 0x02, 0x02, 0x15),
            Gpio.AnalogRead(mode = Gpio.AnalogReadMode.ADC, pin = 2, parameters = p).commandData,
        )
    }

    // Delay encoding — firmware divides by 4. Max 1020 µs clamps to byte 0xFF.
    @Test
    fun `analog parameters delay encoding`() {
        assertEquals(0, Gpio.AnalogReadParameters(delayMicroseconds = 0).encodedDelay)
        assertEquals(1, Gpio.AnalogReadParameters(delayMicroseconds = 4).encodedDelay)
        assertEquals(10, Gpio.AnalogReadParameters(delayMicroseconds = 40).encodedDelay)
        assertEquals(0xFF, Gpio.AnalogReadParameters(delayMicroseconds = 1020).encodedDelay)
    }

    @Test
    fun `analog parameters delay clamps at 1020`() {
        // Over-max values clamp to 1020 before encoding.
        assertEquals(0xFF, Gpio.AnalogReadParameters(delayMicroseconds = 5000).encodedDelay)
    }

    @Test
    fun `analog parameters unused pin sentinel`() {
        assertEquals(0xFF, Gpio.AnalogReadParameters.UNUSED_PIN)
        val d = Gpio.AnalogReadParameters.DEFAULTS
        assertEquals(0xFF, d.pullupPin)
        assertEquals(0xFF, d.pulldownPin)
        assertEquals(0xFF, d.virtualPin)
        assertEquals(0, d.delayMicroseconds)
    }

    // ---- GPIO one-shot reads ----

    @Test
    fun `readDigital returns true for high`() = runTest {
        val (device, transport) = connectedDevice()

        val read = async { device.readDigital(pin = 0) }
        runCurrent() // read command written; read waiter parked
        // Response: [0x05, 0x88, 0x00, 0x01]  (pin=0, state=HIGH)
        transport.inject(bytes(0x05, 0x88, 0x00, 0x01), Uuids.notify)
        runCurrent()
        assertTrue(read.await())
    }

    @Test
    fun `readDigital returns false for low`() = runTest {
        val (device, transport) = connectedDevice()

        val read = async { device.readDigital(pin = 1) }
        runCurrent()
        transport.inject(bytes(0x05, 0x88, 0x01, 0x00), Uuids.notify)
        runCurrent()
        assertFalse(read.await())
    }

    @Test
    fun `readDigital sends the correct command`() = runTest {
        val (device, transport) = connectedDevice()

        val read = async { device.readDigital(pin = 2) }
        runCurrent()
        transport.inject(bytes(0x05, 0x88, 0x02, 0x00), Uuids.notify)
        runCurrent()
        read.await()

        val readCmd = transport.writtenCommands.firstOrNull { it.contentEquals(bytes(0x05, 0x88, 0x02)) }
        assertNotNull(readCmd, "readDigital must send [0x05, 0x88, pin]")
    }

    @Test
    fun `readAnalogADC sends the correct command`() = runTest {
        val (device, transport) = connectedDevice()

        val read = async { device.readAnalogADC(pin = 1) }
        runCurrent()
        transport.inject(bytes(0x05, 0x87, 0x01, 0xE8, 0x03), Uuids.notify)
        runCurrent()
        read.await()

        val readCmd = transport.writtenCommands.firstOrNull { it.contentEquals(bytes(0x05, 0x87, 0x01)) }
        assertNotNull(readCmd, "readAnalogADC must send [0x05, 0x87, pin]")
    }

    @Test
    fun `readAnalogADC parses the little-endian value`() = runTest {
        val (device, transport) = connectedDevice()

        val read = async { device.readAnalogADC(pin = 0) }
        runCurrent()
        // 1000 = 0x03E8 LE
        transport.inject(bytes(0x05, 0x87, 0x00, 0xE8, 0x03), Uuids.notify)
        runCurrent()
        assertEquals(1000, read.await())
    }

    @Test
    fun `readAnalogAbsolute sends the correct command`() = runTest {
        val (device, transport) = connectedDevice()

        val read = async { device.readAnalogAbsolute(pin = 0) }
        runCurrent()
        transport.inject(bytes(0x05, 0x86, 0x00, 0x10, 0x27), Uuids.notify)
        runCurrent()
        read.await()

        val readCmd = transport.writtenCommands.firstOrNull { it.contentEquals(bytes(0x05, 0x86, 0x00)) }
        assertNotNull(readCmd, "readAnalogAbsolute must send [0x05, 0x86, pin]")
    }

    // ---- GPIO pin-change streaming ----

    @Test
    fun `pin change stream enable command`() =
        assertArrayEquals(
            bytes(0x05, 0x0B, 0x01, 0x01),
            GpioPinChange(pin = 1, changeType = Gpio.ChangeType.RISING).enableCommand,
        )

    @Test
    fun `pin change stream disable command`() =
        assertArrayEquals(
            bytes(0x05, 0x0B, 0x02, 0x00),
            GpioPinChange(pin = 2, changeType = Gpio.ChangeType.ANY).disableCommand,
        )

    @Test
    fun `pin change stream configure command`() =
        assertArrayEquals(
            bytes(0x05, 0x09, 0x00, 0x02),
            GpioPinChange(pin = 0, changeType = Gpio.ChangeType.FALLING).configureCommands.first(),
        )

    @Test
    fun `pin change stream parses a high sample`() {
        val sensor = GpioPinChange(pin = 0, changeType = Gpio.ChangeType.ANY)
        val sample = sensor.parseSample(bytes(0x05, 0x0A, 0x00, 0x01))
        assertEquals(0, sample.pin)
        assertTrue(sample.isHigh)
    }

    @Test
    fun `pin change stream parses a low sample`() {
        val sensor = GpioPinChange(pin = 2, changeType = Gpio.ChangeType.FALLING)
        val sample = sensor.parseSample(bytes(0x05, 0x0A, 0x02, 0x00))
        assertEquals(2, sample.pin)
        assertFalse(sample.isHigh)
    }

    @Test
    fun `pin change stream delivers notifications`() = runTest {
        val (device, transport) = connectedDevice()
        val sensor = GpioPinChange(pin = 0, changeType = Gpio.ChangeType.ANY)

        val stream = device.startStream(sensor, usePacked = false)

        stream.test {
            transport.inject(bytes(0x05, 0x0A, 0x00, 0x01), Uuids.notify)
            transport.inject(bytes(0x05, 0x0A, 0x00, 0x00), Uuids.notify)
            assertTrue(awaitItem().value.isHigh)
            assertFalse(awaitItem().value.isHigh)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
