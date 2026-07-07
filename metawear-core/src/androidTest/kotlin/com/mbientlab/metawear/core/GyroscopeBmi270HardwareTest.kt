package com.mbientlab.metawear.core

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.sensor.GyroscopeBmi270
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented BMI270 rotation-streaming smoke tests against real hardware,
 * unpacked and packed.
 *
 * Needs a MetaMotion S (BMI270, gyro implementation 1) in range; self-skips
 * otherwise. Timeouts are real wall-clock time (`runBlocking` via
 * [HardwareSupport.withConnectedDevice]).
 */
@RunWith(AndroidJUnit4::class)
class GyroscopeBmi270HardwareTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    /** Skip unless the gyro module reports the BMI270 implementation (1). */
    private fun assumeBmi270(device: com.mbientlab.metawear.MetaWearDevice) {
        val gyro = device.moduleInfo(Module.GYRO)
        assumeTrue("gyro module absent — skipping", gyro?.isPresent == true)
        assumeTrue("BMI270 gyro required (impl 1), got ${gyro?.implementation}", gyro?.implementation == 1)
    }

    @Test
    fun rotation_streamsUnpacked_thenReturnsToIdle() =
        HardwareSupport.withConnectedDevice { device ->
            assumeBmi270(device)
            val gyro = GyroscopeBmi270(GyroscopeBmi270.Odr.HZ50, GyroscopeBmi270.Range.DPS500)

            val stream = device.startStream(gyro, usePacked = false)
            assertEquals(DeviceState.Streaming, device.state.value)

            // 50 Hz for ~2 s ≈ 100 samples; > 50 tolerates connection ramp-up.
            val samples = collectStreamFor(stream, millis = 2_000)
            device.stopStreaming(gyro)

            assertTrue(
                "expected > 50 rotation samples in ~2 s at 50 Hz, got ${samples.size}",
                samples.size > 50,
            )
            assertEquals(
                "device should return to Idle after the stream stops",
                DeviceState.Idle,
                device.state.value,
            )
        }

    @Test
    fun rotation_streamsPacked_higherThroughput() =
        HardwareSupport.withConnectedDevice { device ->
            assumeBmi270(device)
            val gyro = GyroscopeBmi270(GyroscopeBmi270.Odr.HZ100, GyroscopeBmi270.Range.DPS500)

            val stream = device.startStream(gyro, usePacked = true)
            val samples = collectStreamFor(stream, millis = 2_000)
            device.stopStreaming(gyro)

            // 100 Hz × 2 s ≈ 200 samples (3 per packed BLE packet); > 120
            // tolerates connection ramp-up.
            assertTrue(
                "expected > 120 packed rotation samples in ~2 s at 100 Hz, got ${samples.size}",
                samples.size > 120,
            )
            assertEquals(DeviceState.Idle, device.state.value)
        }
}
