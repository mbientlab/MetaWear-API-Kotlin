package com.mbientlab.metawear.core

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.sensor.Barometer
import com.mbientlab.metawear.sensor.LastResetTime
import com.mbientlab.metawear.sensor.LogLength
import com.mbientlab.metawear.sensor.MacAddress
import com.mbientlab.metawear.sensor.Settings
import com.mbientlab.metawear.sensor.Thermometer
import com.mbientlab.metawear.sensor.ThermometerSource
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke tests for the one-shot readable surface on real hardware:
 * every temperature channel, battery state, last-reset time, log length, and
 * the MAC address (which also proves the `MacAddress` →
 * `Settings.ReadMacAddress` alias reads the right register on real firmware).
 */
@RunWith(AndroidJUnit4::class)
class ReadHardwareTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    @Test
    fun temperature_eachAvailableChannel_readsCelsius() =
        HardwareSupport.withConnectedDevice { device ->
            val info = device.moduleInfo(Module.TEMPERATURE)
            assumeTrue("temperature module absent — skipping", info?.isPresent == true)
            val sources = info!!.extra.map { raw ->
                ThermometerSource.entries.firstOrNull { it.raw == raw } ?: ThermometerSource.INVALID
            }
            assumeTrue("no temperature channels advertised", sources.isNotEmpty())

            sources.forEachIndexed { channel, source ->
                // The BMP280's thermometer only samples while the barometer
                // is running — wake it briefly for that channel.
                val needsBarometer = source == ThermometerSource.BMP280 && device.hasBarometer
                val barometer = Barometer()
                if (needsBarometer) {
                    device.startStream(barometer)
                    delay(200)
                }
                val celsius = device.read(Thermometer(channel)).value
                if (needsBarometer) device.stopStreaming(barometer)

                assertTrue(
                    "channel $channel ($source) temperature implausible: $celsius °C",
                    celsius.isFinite() && celsius in -50f..125f,
                )
            }
        }

    @Test
    fun battery_readReturnsRealisticState() =
        HardwareSupport.withConnectedDevice { device ->
            val battery = device.read(Settings.ReadBatteryState()).value
            assertTrue("charge out of range: ${battery.charge} %", battery.charge in 0..100)
            assertTrue(
                "voltage implausible for a Li-Po cell: ${battery.voltage} mV",
                battery.voltage in 2500..5000,
            )
        }

    @Test
    fun lastResetTime_readReturnsPlausibleEpochAndUid() =
        HardwareSupport.withConnectedDevice { device ->
            val reset = device.read(LastResetTime()).value
            // The boot moment is in the past; allow 1 s of clock/BLE jitter.
            assertTrue(
                "reset epoch is in the future: ${reset.epoch}",
                reset.epoch <= Clock.System.now() + 1.seconds,
            )
            assertTrue("resetUID out of 3-bit range: ${reset.resetUID}", reset.resetUID in 0..7)
        }

    @Test
    fun logLength_readReturnsCount() =
        HardwareSupport.withConnectedDevice { device ->
            // Any UInt32 is valid — this is a wiring smoke test.
            val length = device.read(LogLength()).value
            assertTrue("log length should be non-negative: $length", length >= 0L)
        }

    @Test
    fun macAddress_readReturnsColonSeparatedString() =
        HardwareSupport.withConnectedDevice { device ->
            val mac = device.read(MacAddress()).value
            assertTrue(
                "MAC should be six colon-separated hex pairs, got '$mac'",
                mac.matches(Regex("(?i)([0-9A-F]{2}:){5}[0-9A-F]{2}")),
            )
        }
}
