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
 * Instrumented smoke test for the mechanical button's state-change stream
 * against real hardware.
 *
 * The button is event-driven, so sample counts depend on an operator pressing
 * it; transitions are logged but not asserted. The 3 s listen window keeps
 * the bench suite quick — the stream start/stop lifecycle is what's under
 * test.
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
