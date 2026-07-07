package com.mbientlab.metawear.core

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.sensor.Altimeter
import com.mbientlab.metawear.sensor.Barometer
import com.mbientlab.metawear.sensor.Thermometer
import com.mbientlab.metawear.sensor.readHumidity
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented port of the Swift `EnvironmentSensorTests` — die temperature,
 * barometric pressure, derived altitude, and (where fitted) relative humidity.
 *
 * Value bands are wide on purpose: they catch decode/scale bugs (wrong unit,
 * wrong endianness) rather than judging the weather.
 */
@RunWith(AndroidJUnit4::class)
class EnvironmentSensorHardwareTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    @Test
    fun temperature_nrfDie_returnsPlausibleCelsius() =
        HardwareSupport.withConnectedDevice { device ->
            assumeTrue(
                "temperature module absent — skipping",
                device.moduleInfo(Module.TEMPERATURE)?.isPresent == true,
            )
            // Channel 0 is always the NRF SoC die, which runs warm (Swift
            // temperature_nrfDie_returnsPlausibleValue accepts 15–75 °C).
            val celsius = device.read(Thermometer(channel = 0)).value
            assertTrue("NRF die temperature implausible: $celsius °C", celsius in 15f..75f)
        }

    @Test
    fun barometer_pressure_returnsPlausiblePascals() =
        HardwareSupport.withConnectedDevice { device ->
            assumeTrue("barometer absent — skipping", device.hasBarometer)
            val barometer = Barometer()

            val stream = device.startStream(barometer)
            val readings = collectStreamFor(stream, millis = 3_000)
            device.stopStreaming(barometer)

            assertTrue("expected pressure readings within ~3 s, got none", readings.isNotEmpty())
            // 54–110 kPa spans sea level (~101.3 kPa) up to ~5000 m altitude.
            val pascals = readings.last()
            assertTrue("pressure implausible: $pascals Pa", pascals in 54_000f..110_000f)
        }

    @Test
    fun altimeter_altitude_returnsPlausibleMeters() =
        HardwareSupport.withConnectedDevice { device ->
            assumeTrue("barometer absent — skipping", device.hasBarometer)
            val altimeter = Altimeter()

            val stream = device.startStream(altimeter)
            val readings = collectStreamFor(stream, millis = 3_000)
            device.stopStreaming(altimeter)

            assertTrue("expected altitude readings within ~3 s, got none", readings.isNotEmpty())
            // Firmware-derived altitude referenced to standard pressure; −500 m
            // (deep valley / high-pressure system) to +5000 m covers any bench.
            val meters = readings.last()
            assertTrue("altitude implausible: $meters m", meters in -500f..5_000f)
        }

    @Test
    fun humidity_read_returnsPercent_whenFitted() =
        HardwareSupport.withConnectedDevice { device ->
            // BME280-equipped boards only (absent on MetaMotion S) — self-skips.
            assumeTrue(
                "humidity module absent — skipping",
                device.moduleInfo(Module.HUMIDITY)?.isPresent == true,
            )
            val percent = device.readHumidity()
            assertTrue("relative humidity out of range: $percent %", percent in 0f..100f)
        }
}
