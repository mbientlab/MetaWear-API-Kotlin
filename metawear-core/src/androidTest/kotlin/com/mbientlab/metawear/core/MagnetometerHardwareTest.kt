package com.mbientlab.metawear.core

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.sensor.Magnetometer
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented port of the Swift `MagnetometerTests` (MagnetometerDataTests +
 * MagnetometerPackedDataTests + MagnetometerSuspendTests) — BMM150 magnetic
 * field streaming and power management.
 *
 * Self-skips when the board has no magnetometer (or the revision lacks the
 * packed/suspend features).
 */
@RunWith(AndroidJUnit4::class)
class MagnetometerHardwareTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    @Test
    fun magneticField_streamsLowPower() =
        HardwareSupport.withConnectedDevice { device ->
            assumeTrue("magnetometer absent — skipping", device.hasMagnetometer)
            val mag = Magnetometer(Magnetometer.Preset.LOW_POWER) // 10 Hz

            // BMM150 needs a warmup beat before accepting writes — the SDK's
            // warmupCommands/warmupDelayNanos handle it inside startStream.
            val stream = device.startStream(mag, usePacked = false)
            val samples = collectStreamFor(stream, millis = 4_000)
            device.stopStreaming(mag)

            // Swift subscribe_magnetic_field_data asserts >= 3 in 4 s at 10 Hz.
            assertTrue("expected >= 3 field samples in ~4 s at 10 Hz, got ${samples.size}", samples.size >= 3)
            assertEquals(DeviceState.Idle, device.state.value)
        }

    @Test
    fun magneticField_highAccuracy_plausibleEarthField() =
        HardwareSupport.withConnectedDevice { device ->
            assumeTrue("magnetometer absent — skipping", device.hasMagnetometer)
            val mag = Magnetometer(Magnetometer.Preset.HIGH_ACCURACY) // 20 Hz

            val stream = device.startStream(mag, usePacked = false)
            val samples = collectStreamFor(stream, millis = 8_000)
            device.stopStreaming(mag)

            assertTrue("expected > 30 samples in ~8 s at 20 Hz, got ${samples.size}", samples.size > 30)
            // Earth's field is ~25–65 µT; indoor interference widens the band.
            // Swift magnetometer_receivesFieldData accepts 10–500 µT.
            val last = samples.last()
            val magnitude = sqrt((last.x * last.x + last.y * last.y + last.z * last.z).toDouble())
            assertTrue(
                "field magnitude implausible: $magnitude µT (expected 10–500)",
                magnitude in 10.0..500.0,
            )
        }

    @Test
    fun magneticField_streamsPacked_whenRevisionSupports() =
        HardwareSupport.withConnectedDevice { device ->
            val info = device.moduleInfo(Module.MAGNETOMETER)
            assumeTrue("magnetometer absent — skipping", info?.isPresent == true)
            assumeTrue("packed mag data needs revision >= 1, got ${info?.revision}", (info?.revision ?: 0) >= 1)

            val mag = Magnetometer(Magnetometer.Preset.HIGH_ACCURACY) // 20 Hz
            val stream = device.startStream(mag, usePacked = true)
            val samples = collectStreamFor(stream, millis = 3_000)
            device.stopStreaming(mag)

            // 20 Hz × 3 s ≈ 60 samples (3 per packed packet); Swift asserts > 30.
            assertTrue("expected > 30 packed samples in ~3 s at 20 Hz, got ${samples.size}", samples.size > 30)
            assertEquals(DeviceState.Idle, device.state.value)
        }

    @Test
    fun suspend_doesNotThrow_whenRevisionSupports() =
        HardwareSupport.withConnectedDevice { device ->
            val info = device.moduleInfo(Module.MAGNETOMETER)
            assumeTrue("magnetometer absent — skipping", info?.isPresent == true)
            assumeTrue("suspend needs revision >= 2, got ${info?.revision}", (info?.revision ?: 0) >= 2)

            // Command-only (Swift parity): assert nothing beyond "no throw".
            device.send(Magnetometer.Suspend())
        }
}
