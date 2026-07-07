package com.mbientlab.metawear.core

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.mbientlab.metawear.sensor.Haptic
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented haptic-driver smoke tests against real hardware — one-shot
 * pulses on the haptic driver.
 *
 * Fire-and-forget commands with no readable feedback: the assertion is
 * "no throw" (plus a physical buzz if you're holding the board). The delays
 * let each pulse finish before disconnect so the write actually leaves the
 * radio.
 */
@RunWith(AndroidJUnit4::class)
class HapticHardwareTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    @Test
    fun haptic_motorPulse() =
        HardwareSupport.withConnectedDevice { device ->
            device.send(Haptic.motor(dutyCycle = 80, pulseWidth = 300))
            delay(400)
        }

    @Test
    fun haptic_buzzerPulse() =
        HardwareSupport.withConnectedDevice { device ->
            device.send(Haptic.buzzer(pulseWidth = 200))
            delay(300)
        }

    @Test
    fun haptic_motorAtMaxDutyCycle() =
        HardwareSupport.withConnectedDevice { device ->
            device.send(Haptic.motor(dutyCycle = 100, pulseWidth = 500))
            delay(600)
        }
}
