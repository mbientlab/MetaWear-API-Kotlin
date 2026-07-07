package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.autoReplyModuleDiscovery
import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.makeConnectableTransport
import com.mbientlab.metawear.transport.MockBleTransport
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Unsigned view of one wire byte. */
private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

/** LED command byte layouts and preset vectors. */
class LedTest {

    // ---- SetPattern command ----

    @Test fun setPattern_correctLength() {
        // [module, register, color, 0x02, highInt, lowInt, rise(2), high(2), fall(2), pulse(2), delay(2), repeat] = 17 bytes
        assertEquals(17, Led.SetPattern(Led.Color.RED, LedPattern.blink).commandData.size)
    }

    @Test fun setPattern_moduleAndRegisterBytes() {
        val cmd = Led.SetPattern(Led.Color.GREEN, LedPattern.blink)
        assertEquals(0x02, cmd.commandData.u(0)) // LED module
        assertEquals(0x03, cmd.commandData.u(1)) // LED_CONFIG register
    }

    @Test fun setPattern_colorBytes() {
        assertEquals(0, Led.SetPattern(Led.Color.GREEN, LedPattern.blink).commandData.u(2))
        assertEquals(1, Led.SetPattern(Led.Color.RED, LedPattern.blink).commandData.u(2))
        assertEquals(2, Led.SetPattern(Led.Color.BLUE, LedPattern.blink).commandData.u(2))
    }

    @Test fun setPattern_fixedByte() {
        // Byte index 3 is always 0x02 per protocol spec
        assertEquals(0x02, Led.SetPattern(Led.Color.RED, LedPattern.blink).commandData.u(3))
    }

    @Test fun setPattern_intensityBytes() {
        val p = LedPattern(
            highIntensity = 20, lowIntensity = 5,
            riseTime = 0, highTime = 200, fallTime = 0,
            pulseDuration = 1000, delay = 0, repeatCount = 3,
        )
        val cmd = Led.SetPattern(Led.Color.RED, p)
        assertEquals(20, cmd.commandData.u(4)) // highIntensity
        assertEquals(5, cmd.commandData.u(5))  // lowIntensity
    }

    @Test fun setPattern_timingBytesLittleEndian() {
        // riseTime=0x0102 (258ms), at offset 6-7
        val p = LedPattern(
            highIntensity = 31, lowIntensity = 0,
            riseTime = 0x0102, highTime = 0x0304, fallTime = 0x0506,
            pulseDuration = 0x0708, delay = 0x090A, repeatCount = 0,
        )
        val data = Led.SetPattern(Led.Color.GREEN, p).commandData
        // rise at [6,7]
        assertEquals(0x02, data.u(6))
        assertEquals(0x01, data.u(7))
        // high at [8,9]
        assertEquals(0x04, data.u(8))
        assertEquals(0x03, data.u(9))
        // fall at [10,11]
        assertEquals(0x06, data.u(10))
        assertEquals(0x05, data.u(11))
        // pulse at [12,13]
        assertEquals(0x08, data.u(12))
        assertEquals(0x07, data.u(13))
        // delay at [14,15]
        assertEquals(0x0A, data.u(14))
        assertEquals(0x09, data.u(15))
    }

    @Test fun setPattern_repeatCountByte() {
        val p = LedPattern(repeatCount = 5)
        assertEquals(5, Led.SetPattern(Led.Color.BLUE, p).commandData.u(16))
    }

    @Test fun setPattern_repeatCountZero_indefinite() {
        // "Indefinite" is 0xFF on the wire — the firmware treats a raw 0 as
        // undefined behaviour, so the encoder rewrites 0 to 0xFF.
        val p = LedPattern(repeatCount = 0)
        assertEquals(0xFF, Led.SetPattern(Led.Color.RED, p).commandData.u(16))
    }

    // ---- Preset patterns ----

    @Test fun solidPreset_noBlink() {
        // Solid: rise=0, fall=0, lowIntensity == highIntensity (never dims between pulses)
        val p = LedPattern.solid
        assertEquals(0, p.riseTime)
        assertEquals(0, p.fallTime)
        assertEquals(p.highIntensity, p.lowIntensity)
    }

    @Test fun blinkPreset_shortHighTime() {
        val p = LedPattern.blink
        assertTrue(p.highTime < p.pulseDuration)
        assertEquals(0, p.riseTime)
        assertEquals(0, p.fallTime)
    }

    @Test fun breathePreset_hasRiseAndFall() {
        val p = LedPattern.breathe
        assertTrue(p.riseTime > 0)
        assertTrue(p.fallTime > 0)
    }

    @Test fun flashPreset_finiteRepeat() {
        assertTrue(LedPattern.flash.repeatCount > 0)
    }

    // ---- Preset exact values (pinned against C++ SDK reference) ----

    @Test fun solidPreset_exactValues() {
        val p = LedPattern.solid
        assertEquals(31, p.highIntensity)
        assertEquals(31, p.lowIntensity)
        assertEquals(0, p.riseTime)
        assertEquals(500, p.highTime)
        assertEquals(0, p.fallTime)
        assertEquals(1000, p.pulseDuration)
        assertEquals(0xFF, p.repeatCount) // 0xFF = repeat indefinitely (MbientLab SDK convention)
    }

    @Test fun blinkPreset_exactValues() {
        val p = LedPattern.blink
        assertEquals(31, p.highIntensity)
        assertEquals(0, p.lowIntensity)
        assertEquals(0, p.riseTime)
        assertEquals(50, p.highTime)
        assertEquals(0, p.fallTime)
        assertEquals(500, p.pulseDuration)
        assertEquals(0xFF, p.repeatCount)
    }

    @Test fun breathePreset_exactValues() {
        val p = LedPattern.breathe
        assertEquals(31, p.highIntensity)
        assertEquals(725, p.riseTime)
        assertEquals(500, p.highTime)
        assertEquals(725, p.fallTime)
        assertEquals(2000, p.pulseDuration)
        assertEquals(0xFF, p.repeatCount)
    }

    @Test fun flashPreset_exactValues() {
        val p = LedPattern.flash
        assertEquals(31, p.highIntensity)
        assertEquals(0, p.lowIntensity)
        assertEquals(0, p.riseTime)
        assertEquals(100, p.highTime)
        assertEquals(0, p.fallTime)
        assertEquals(500, p.pulseDuration)
        assertEquals(3, p.repeatCount)
    }

    // ---- Play / Pause / Stop ----

    @Test fun play_command() =
        assertArrayEquals(bytes(0x02, 0x01, 0x01), Led.Play().commandData)

    @Test fun autoplay_command() =
        assertArrayEquals(bytes(0x02, 0x01, 0x02), Led.Autoplay().commandData)

    @Test fun pause_command() =
        assertArrayEquals(bytes(0x02, 0x01, 0x00), Led.Pause().commandData)

    @Test fun stop_keepPattern() =
        assertArrayEquals(bytes(0x02, 0x02, 0x00), Led.Stop(clearPattern = false).commandData)

    @Test fun stop_clearPattern() =
        assertArrayEquals(bytes(0x02, 0x02, 0x01), Led.Stop(clearPattern = true).commandData)

    @Test fun stop_defaultClearsPattern() =
        assertArrayEquals(bytes(0x02, 0x02, 0x01), Led.Stop().commandData)

    // ---- Reference vectors from test_led.py (MbientLab C++ SDK) ----
    // Packet layout: [module, register, color, 0x02, highInt, lowInt,
    //                 rise(le16), high(le16), fall(le16), pulse(le16), delay(le16), repeat]

    @Test fun blinkPreset_referenceVector_green() {
        // BLINK loaded into green channel; delay=0, repeat=10
        val p = LedPattern(
            highIntensity = 31, lowIntensity = 0, riseTime = 0, highTime = 50,
            fallTime = 0, pulseDuration = 500, delay = 0, repeatCount = 10,
        )
        assertArrayEquals(
            bytes(
                0x02, 0x03, 0x00, 0x02,
                0x1F, 0x00,
                0x00, 0x00,
                0x32, 0x00,
                0x00, 0x00,
                0xF4, 0x01,
                0x00, 0x00,
                0x0A,
            ),
            Led.SetPattern(Led.Color.GREEN, p).commandData,
        )
    }

    @Test fun solidPreset_referenceVector_red() {
        // SOLID loaded into red channel; delay=0, repeat=20
        val p = LedPattern(
            highIntensity = 31, lowIntensity = 31, riseTime = 0, highTime = 500,
            fallTime = 0, pulseDuration = 1000, delay = 0, repeatCount = 20,
        )
        assertArrayEquals(
            bytes(
                0x02, 0x03, 0x01, 0x02,
                0x1F, 0x1F,
                0x00, 0x00,
                0xF4, 0x01,
                0x00, 0x00,
                0xE8, 0x03,
                0x00, 0x00,
                0x14,
            ),
            Led.SetPattern(Led.Color.RED, p).commandData,
        )
    }

    @Test fun pulsePreset_referenceVector_blue() {
        // PULSE (breathe) loaded into blue channel; delay=0, repeat=40
        val p = LedPattern(
            highIntensity = 31, lowIntensity = 0, riseTime = 725, highTime = 500,
            fallTime = 725, pulseDuration = 2000, delay = 0, repeatCount = 40,
        )
        assertArrayEquals(
            bytes(
                0x02, 0x03, 0x02, 0x02,
                0x1F, 0x00,
                0xD5, 0x02,
                0xF4, 0x01,
                0xD5, 0x02,
                0xD0, 0x07,
                0x00, 0x00,
                0x28,
            ),
            Led.SetPattern(Led.Color.BLUE, p).commandData,
        )
    }

    @Test fun blinkPreset_delayedFirmware_referenceVector() {
        // Same as blink test but delay=5000ms (firmware >= 1.2.3)
        val p = LedPattern(
            highIntensity = 31, lowIntensity = 0, riseTime = 0, highTime = 50,
            fallTime = 0, pulseDuration = 500, delay = 5000, repeatCount = 10,
        )
        assertArrayEquals(
            bytes(
                0x02, 0x03, 0x00, 0x02,
                0x1F, 0x00,
                0x00, 0x00,
                0x32, 0x00,
                0x00, 0x00,
                0xF4, 0x01,
                0x88, 0x13, // 5000ms LE16
                0x0A,
            ),
            Led.SetPattern(Led.Color.GREEN, p).commandData,
        )
    }
}

/** Device-level checks for the [setLed] / [stopLed] convenience extensions. */
class LedDeviceTest {

    private suspend fun TestScope.connectedDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice("AA:BB:CC:DD:EE:FF", transport, backgroundScope)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport)
        device.connect()
        discovery.cancel()
        return device to transport
    }

    @Test
    fun `setLed sends stop, channel patterns, then play in order`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()

        device.setLed(red = LedPattern.blink, blue = LedPattern.solid, autoPlay = true)

        val cmds = transport.writtenCommands
        assertEquals(4, cmds.size)
        assertArrayEquals(bytes(0x02, 0x02, 0x01), cmds[0]) // stop + clear first
        assertArrayEquals(Led.SetPattern(Led.Color.RED, LedPattern.blink).commandData, cmds[1])
        assertArrayEquals(Led.SetPattern(Led.Color.BLUE, LedPattern.solid).commandData, cmds[2])
        assertArrayEquals(bytes(0x02, 0x01, 0x01), cmds[3]) // play
    }

    @Test
    fun `setLed without autoPlay omits the play command`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()

        device.setLed(green = LedPattern.breathe, autoPlay = false)

        val cmds = transport.writtenCommands
        assertEquals(2, cmds.size)
        assertArrayEquals(bytes(0x02, 0x02, 0x01), cmds[0])
        assertArrayEquals(Led.SetPattern(Led.Color.GREEN, LedPattern.breathe).commandData, cmds[1])
    }

    @Test
    fun `stopLed sends stop with the requested clear flag`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()

        device.stopLed(clearPattern = false)
        device.stopLed(clearPattern = true)

        assertArrayEquals(bytes(0x02, 0x02, 0x00), transport.writtenCommands[0])
        assertArrayEquals(bytes(0x02, 0x02, 0x01), transport.writtenCommands[1])
    }
}
