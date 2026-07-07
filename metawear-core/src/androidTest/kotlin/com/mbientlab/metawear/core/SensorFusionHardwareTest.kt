package com.mbientlab.metawear.core

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.sensor.SensorFusionChip
import com.mbientlab.metawear.sensor.SensorFusionEuler
import com.mbientlab.metawear.sensor.SensorFusionGravity
import com.mbientlab.metawear.sensor.SensorFusionMode
import com.mbientlab.metawear.sensor.SensorFusionQuaternion
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented quaternion/gravity smoke tests against real hardware — stream
 * sanity for the on-board BSX fusion engine.
 *
 * Self-skips when the board has no sensor-fusion module. Mode picks NDOF
 * (9-DoF) when a magnetometer is present, IMU_PLUS (6-DoF) otherwise; the
 * chip byte comes from the gyro module's implementation, so the right
 * command bytes go to whichever IMU the board actually carries.
 */
@RunWith(AndroidJUnit4::class)
class SensorFusionHardwareTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    private fun fusionMode(device: MetaWearDevice): SensorFusionMode =
        if (device.hasMagnetometer) SensorFusionMode.NDOF else SensorFusionMode.IMU_PLUS

    private fun fusionChip(device: MetaWearDevice): SensorFusionChip =
        SensorFusionChip.fromGyroImpl(device.moduleInfo(Module.GYRO)?.implementation ?: -1)
            ?: SensorFusionChip.BMI160

    @Test
    fun quaternion_streams_thenReturnsToIdle() =
        HardwareSupport.withConnectedDevice { device ->
            assumeTrue("sensor fusion absent — skipping", device.hasSensorFusion)
            val quaternion = SensorFusionQuaternion(mode = fusionMode(device), chip = fusionChip(device))

            val stream = device.startStream(quaternion)
            assertEquals(DeviceState.Streaming, device.state.value)

            // Fusion outputs at ~100 Hz; expect >= 5 samples in 2 s.
            val samples = collectStreamFor(stream, millis = 2_000)
            device.stopStreaming(quaternion)

            assertTrue("expected >= 5 quaternion samples in ~2 s, got ${samples.size}", samples.size >= 5)
            assertEquals(DeviceState.Idle, device.state.value)
        }

    @Test
    fun quaternion_hasUnitMagnitude() =
        HardwareSupport.withConnectedDevice { device ->
            assumeTrue("sensor fusion absent — skipping", device.hasSensorFusion)
            val quaternion = SensorFusionQuaternion(mode = fusionMode(device), chip = fusionChip(device))

            val stream = device.startStream(quaternion)
            val samples = collectStreamFor(stream, millis = 8_000)
            device.stopStreaming(quaternion)

            assertTrue("expected > 30 quaternion samples in ~8 s, got ${samples.size}", samples.size > 30)
            // A rotation quaternion is unit-length; ±5% tolerance covers
            // fixed-point wire encoding and filter warmup.
            val q = samples.last()
            val magnitude = sqrt((q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z).toDouble())
            assertTrue(
                "quaternion should be unit length: |q| = $magnitude, expected within 0.05 of 1.0",
                abs(magnitude - 1.0) < 0.05,
            )
        }

    @Test
    fun gravity_magnitudeNearOneG() =
        HardwareSupport.withConnectedDevice { device ->
            assumeTrue("sensor fusion absent — skipping", device.hasSensorFusion)
            val gravity = SensorFusionGravity(mode = fusionMode(device), chip = fusionChip(device))

            val stream = device.startStream(gravity)
            val samples = collectStreamFor(stream, millis = 8_000)
            device.stopStreaming(gravity)

            assertTrue("expected > 30 gravity samples in ~8 s, got ${samples.size}", samples.size > 30)
            // At rest the gravity vector's magnitude is 1 g regardless of
            // orientation; ±0.2 g tolerance covers filter warmup.
            val g = samples.last()
            val magnitude = sqrt((g.x * g.x + g.y * g.y + g.z * g.z).toDouble())
            assertTrue(
                "gravity magnitude implausible: $magnitude g, expected within 0.2 of 1.0",
                abs(magnitude - 1.0) < 0.2,
            )
        }

    @Test
    fun quaternionAndEuler_streamTogether_onTheSharedEngine() =
        HardwareSupport.withConnectedDevice { device ->
            assumeTrue("sensor fusion absent — skipping", device.hasSensorFusion)
            val mode = fusionMode(device)
            val chip = fusionChip(device)
            val quaternion = SensorFusionQuaternion(mode = mode, chip = chip)
            val euler = SensorFusionEuler(mode = mode, chip = chip)

            // Both outputs ride the one fusion engine (same mode/range).
            val quaternionStream = device.startStream(quaternion)
            val eulerStream = device.startStream(euler)

            val quaternionSamples = mutableListOf<Boolean>()
            val eulerSamples = mutableListOf<Boolean>()
            val qJob = launch { quaternionStream.collect { quaternionSamples += true } }
            val eJob = launch { eulerStream.collect { eulerSamples += true } }
            delay(2_000)
            qJob.cancel()
            eJob.cancel()
            qJob.join()
            eJob.join()

            assertTrue("quaternion got ${quaternionSamples.size} samples", quaternionSamples.size >= 5)
            assertTrue("euler got ${eulerSamples.size} samples", eulerSamples.size >= 5)

            // Stopping one output leaves the engine (and device) streaming.
            device.stopStreaming(quaternion)
            assertEquals(DeviceState.Streaming, device.state.value)

            device.stopStreaming(euler)
            assertEquals(DeviceState.Idle, device.state.value)
        }
}
