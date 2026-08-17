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
 * Instrumented logging smoke tests against real hardware — the on-device
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

    /**
     * Poll LOG_LENGTH every 2 s until the board reports committed entries
     * (up to 45 s), returning the count. Mirrors the firmware's page-commit
     * cadence: entries become visible in 512-entry NAND pages, the first
     * ~5-6 s after logging starts, and there is a short post-clearLog dead
     * zone while the flash finishes housekeeping.
     */
    private suspend fun awaitEntriesLanding(device: com.mbientlab.metawear.MetaWearDevice): Long {
        repeat(22) {
            delay(2_000)
            val n = device.read(LogLength()).value
            if (n > 0) return n
        }
        return device.read(LogLength()).value
    }

    @Test
    fun accelerometer_logsAndDownloads_roundTrip() =
        HardwareSupport.withConnectedDevice { device ->
            assumeBmi270Accel(device)
            val accel = AccelerometerBmi270(AccelerometerBmi270.Odr.HZ50, AccelerometerBmi270.Range.G2)

            device.clearLog()
            device.startLogging(accel)
            assertEquals(DeviceState.Logging, device.state.value)
            // The MMS commits logged entries to NAND in 512-entry pages and
            // reports LOG_LENGTH only for committed pages; the first page lands
            // ~5-6 s after logging starts at 50 Hz (2 chunks/sample). Wait for
            // it to land instead of assuming a fixed window.
            awaitEntriesLanding(device)
            delay(3_000) // a little more data on top of the first page
            device.stopLogging(accel)
            assertEquals(DeviceState.Idle, device.state.value)

            // Each downloadLogs emission is cumulative; the last one holds the
            // fully-reassembled sample list.
            val entries = device.downloadLogs(accel).last().data
            assertTrue("expected > 50 logged samples in ~5 s at 50 Hz, got ${entries.size}", entries.size > 50)

            // Board at rest → last sample's magnitude ≈ 1 g; ±0.5 g tolerance
            // covers flash quantization and bench vibration.
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
            // LOG_LENGTH is page-granular (512-entry NAND pages) and reads 0
            // until the first page commits ~5-6 s in; poll for it rather than
            // sampling a fixed 2 s window inside that dead zone.
            val during = awaitEntriesLanding(device)
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
