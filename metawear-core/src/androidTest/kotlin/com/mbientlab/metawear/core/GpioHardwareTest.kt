package com.mbientlab.metawear.core

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.sensor.Gpio
import com.mbientlab.metawear.sensor.GpioPinChange
import com.mbientlab.metawear.sensor.readAnalogADC
import com.mbientlab.metawear.sensor.readAnalogAbsolute
import com.mbientlab.metawear.sensor.readDigital
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented GPIO smoke tests against real hardware — digital reads against
 * internal pulls, analog ADC/absolute reads, and the pin-change stream, all on
 * pin 0 (safe to probe unwired).
 *
 * Self-skips when the board has no GPIO module.
 */
@RunWith(AndroidJUnit4::class)
class GpioHardwareTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    private val pin = 0

    private fun assumeGpio(device: MetaWearDevice) =
        assumeTrue("GPIO module absent — skipping", device.moduleInfo(Module.GPIO)?.isPresent == true)

    @Test
    fun digitalRead_pullUp_readsHigh() =
        HardwareSupport.withConnectedDevice { device ->
            assumeGpio(device)
            device.send(Gpio.SetPull(pin, Gpio.Pull.UP))
            delay(50) // let the pull settle before sampling
            assertTrue("pull-up pin should read HIGH", device.readDigital(pin))
        }

    @Test
    fun digitalRead_pullDown_readsLow() =
        HardwareSupport.withConnectedDevice { device ->
            assumeGpio(device)
            device.send(Gpio.SetPull(pin, Gpio.Pull.DOWN))
            delay(50)
            assertFalse("pull-down pin should read LOW", device.readDigital(pin))
            // Restore the benign default so later tests see a defined level.
            device.send(Gpio.SetPull(pin, Gpio.Pull.UP))
        }

    @Test
    fun analogAdc_returnsTenBitValue() =
        HardwareSupport.withConnectedDevice { device ->
            assumeGpio(device)
            val adc = device.readAnalogADC(pin)
            assertTrue("raw ADC out of 10-bit range: $adc", adc in 0..1023)
        }

    @Test
    fun analogAbsolute_returnsMillivoltsOnRail() =
        HardwareSupport.withConnectedDevice { device ->
            assumeGpio(device)
            val mv = device.readAnalogAbsolute(pin)
            assertTrue("absolute read above the 3.3 V rail: $mv mV", mv in 0..3300)
        }

    @Test
    fun pinChangeStream_startsAndStops_withoutError() =
        HardwareSupport.withConnectedDevice { device ->
            assumeGpio(device)
            val pinChange = GpioPinChange(pin, Gpio.ChangeType.ANY)

            // Nothing forces an edge on an unwired pin — the lifecycle is the test.
            val stream = device.startStream(pinChange)
            assertEquals(DeviceState.Streaming, device.state.value)
            collectStreamFor(stream, millis = 1_000)
            device.stopStreaming(pinChange)
            assertEquals(DeviceState.Idle, device.state.value)
        }
}
