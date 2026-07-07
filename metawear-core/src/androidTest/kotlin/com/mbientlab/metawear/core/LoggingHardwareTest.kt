package com.mbientlab.metawear.core

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.clearLog
import com.mbientlab.metawear.downloadLogs
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.queryActiveLoggers
import com.mbientlab.metawear.sensor.AccelerometerBmi270
import com.mbientlab.metawear.sensor.LogLength
import com.mbientlab.metawear.startLogging
import com.mbientlab.metawear.stopLogging
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented port of the Swift `LoggingTests` essentials — the on-device
 * log → flash → download round trip on the BMI270 accelerometer (MetaMotion S
 * focus), plus log-length and logger-registry maintenance.
 *
 * Each test leaves the flash cleared so suites can run in any order.
 */
@RunWith(AndroidJUnit4::class)
class LoggingHardwareTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    /** Skip unless the accelerometer reports the BMI270 implementation (4). */
    private fun assumeBmi270Accel(device: MetaWearDevice) {
        val accel = device.moduleInfo(Module.ACCELEROMETER)
        assumeTrue("accelerometer absent — skipping", accel?.isPresent == true)
        assumeTrue("BMI270 accel required (impl 4), got ${accel?.implementation}", accel?.implementation == 4)
    }

    @Test
    fun accelerometer_logsAndDownloads_roundTrip() =
        HardwareSupport.withConnectedDevice { device ->
            assumeBmi270Accel(device)
            val accel = AccelerometerBmi270(AccelerometerBmi270.Odr.HZ50, AccelerometerBmi270.Range.G2)

            device.clearLog()
            device.startLogging(accel)
            assertEquals(DeviceState.Logging, device.state.value)
            delay(5_000) // 50 Hz × 5 s ≈ 250 entries in flash
            device.stopLogging(accel)
            assertEquals(DeviceState.Idle, device.state.value)

            // Each downloadLogs emission is cumulative; the last one holds the
            // fully-reassembled sample list.
            val entries = device.downloadLogs(accel).last().data
            assertTrue("expected > 50 logged samples in ~5 s at 50 Hz, got ${entries.size}", entries.size > 50)

            // Board at rest → last sample's magnitude ≈ 1 g (Swift allows ±0.5 g
            // for flash quantization and bench vibration).
            val v = entries.last().value
            val magnitude = sqrt((v.x * v.x + v.y * v.y + v.z * v.z).toDouble())
            assertTrue(
                "board should be at rest: last |a| = $magnitude g, expected within 0.5 of 1 g",
                abs(magnitude - 1.0) < 0.5,
            )

            device.clearLog() // leave the flash clean for other suites
        }

    @Test
    fun logLength_zeroAfterClear() =
        HardwareSupport.withConnectedDevice { device ->
            device.clearLog()
            delay(500) // give the firmware a beat to finish the erase
            val length = device.read(LogLength()).value
            assertEquals("log should be empty after clearLog", 0L, length)
        }

    @Test
    fun logLength_increasesWhileLogging() =
        HardwareSupport.withConnectedDevice { device ->
            assumeBmi270Accel(device)
            val accel = AccelerometerBmi270(AccelerometerBmi270.Odr.HZ50, AccelerometerBmi270.Range.G2)

            device.clearLog()
            delay(500)
            val before = device.read(LogLength()).value

            device.startLogging(accel)
            delay(2_000)
            val during = device.read(LogLength()).value
            device.stopLogging(accel)

            assertTrue("entry count should grow while logging: before=$before during=$during", during > before + 1)
            device.clearLog()
        }

    @Test
    fun queryActiveLoggers_emptyAfterClear() =
        HardwareSupport.withConnectedDevice { device ->
            device.clearLog()
            delay(500)
            val loggers = device.queryActiveLoggers()
            assertTrue("expected no active loggers after clearLog, got $loggers", loggers.isEmpty())
        }
}
