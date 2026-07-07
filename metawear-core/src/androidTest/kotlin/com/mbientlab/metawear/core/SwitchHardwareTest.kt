package com.mbientlab.metawear.core

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.sensor.Switch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented port of the Swift `SwitchTests` — the mechanical button's
 * state-change stream.
 *
 * The button is event-driven, so sample counts depend on an operator pressing
 * it; like the Swift original, transitions are logged but not asserted. The
 * listen window is shortened from Swift's 10 s to 3 s to keep the bench suite
 * quick — the stream start/stop lifecycle is what's under test.
 */
@RunWith(AndroidJUnit4::class)
class SwitchHardwareTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    @Test
    fun switch_streamStartsAndStops_withoutError() =
        HardwareSupport.withConnectedDevice { device ->
            val button = Switch()

            val stream = device.startStream(button)
            assertEquals(DeviceState.Streaming, device.state.value)

            val transitions = collectStreamFor(stream, millis = 3_000)
            transitions.forEachIndexed { i, pressed ->
                println("switch transition $i: ${if (pressed) "PRESSED" else "released"}")
            }

            device.stopStreaming(button)
            assertEquals(DeviceState.Idle, device.state.value)
        }
}
