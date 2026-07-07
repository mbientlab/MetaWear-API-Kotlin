package com.mbientlab.metawear.core

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.sensor.AccelerometerBmi270
import com.mbientlab.metawear.sensor.Led
import com.mbientlab.metawear.sensor.LedPattern
import com.mbientlab.metawear.sensor.Settings
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hardware smoke suite for a nearby MetaMotion S (BMI270) — scan, connect,
 * battery read, LED, and accelerometer streaming essentials against a real
 * board.
 *
 * Needs a real phone (USB debugging) and a charged board in BLE range; when no
 * board answers the 10 s scan, every test self-skips via a JUnit assumption.
 * Run with `./gradlew :metawear-core:connectedAndroidTest`.
 *
 * Timeouts are real (`runBlocking`), never virtual (`runTest`) — a radio
 * doesn't run on a test dispatcher.
 */
@RunWith(AndroidJUnit4::class)
class HardwareSmokeTest {

    /**
     * Instrumented tests can't tap runtime-permission dialogs; grant the API
     * 31+ Bluetooth pair up front. (Test devices are assumed to run API 31+ —
     * on older images these permissions don't exist to grant.)
     */
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    // ---- (a) Scan ----

    @Test
    fun scan_findsMetaWear_withMacIdentifierAndSaneRssi() {
        val device = HardwareSupport.nearbyDevice()

        assertTrue(
            "identifier should be a MAC address, got ${device.identifier}",
            device.identifier.matches(Regex("(?i)([0-9A-F]{2}:){5}[0-9A-F]{2}")),
        )

        val rssi = HardwareSupport.advertisedRssi(device.identifier)
        assertNotNull("no advertisement RSSI captured during scan", rssi)
        // Live BLE RSSI is strictly negative; 0 is the "unknown" placeholder
        // some stacks report, and anything below -126 is off the protocol floor.
        assertTrue("RSSI out of plausible range: $rssi dBm", rssi!! in -126..-1)
    }

    // ---- (b) Connect ----

    @Test
    fun connect_reachesIdle_populatesDeviceInfoAndModules() =
        HardwareSupport.withConnectedDevice { device ->
            assertEquals(DeviceState.Idle, device.state.value)

            val info = device.deviceInfo
            assertNotNull("deviceInfo must be populated after connect()", info)
            assertEquals("MbientLab Inc", info!!.manufacturer)
            assertEquals("MetaMotion S expected (model 8)", "8", info.modelNumber)
            assertTrue("firmwareRevision empty", info.firmwareRevision.isNotEmpty())
            assertTrue("hardwareRevision empty", info.hardwareRevision.isNotEmpty())

            assertTrue("module discovery returned nothing", device.modules.isNotEmpty())
            val accelerometer = device.modules[Module.ACCELEROMETER]
            assertNotNull("accelerometer module missing from discovery", accelerometer)
            assertTrue("accelerometer not present", accelerometer!!.isPresent)
            // Implementation 4 = BMI270 (1 = BMI160).
            assertEquals("expected BMI270 implementation", 4, accelerometer.implementation)
        }

    // ---- (c) Battery ----

    @Test
    fun battery_readReturnsPlausibleState() =
        HardwareSupport.withConnectedDevice { device ->
            val battery = device.read(Settings.ReadBatteryState()).value
            assertTrue("charge out of range: ${battery.charge} %", battery.charge in 0..100)
            assertTrue("voltage should be positive, got ${battery.voltage} mV", battery.voltage > 0)
        }

    // ---- (d) LED ----

    @Test
    fun led_greenFlash_playsAndStops() =
        HardwareSupport.withConnectedDevice { device ->
            // Visual check only — assert nothing beyond "no throw".
            device.send(Led.SetPattern(Led.Color.GREEN, LedPattern.flash))
            device.send(Led.Play())
            delay(2_000)
            device.send(Led.Stop(clearPattern = true))
        }

    // ---- (e) Stream ----

    @Test
    fun accelerometer_streamsPackedSamples_atRestNearOneG() =
        HardwareSupport.withConnectedDevice { device ->
            val sensor = AccelerometerBmi270(
                AccelerometerBmi270.Odr.HZ100,
                AccelerometerBmi270.Range.G2,
            )

            val magnitudes = mutableListOf<Double>()
            val stream = device.startStream(sensor, usePacked = true)
            assertEquals(DeviceState.Streaming, device.state.value)

            // Single-threaded runBlocking event loop: collector and test body
            // share one thread, so the plain list needs no synchronization.
            val collector = launch {
                stream.collect { sample ->
                    val v = sample.value
                    magnitudes += sqrt((v.x * v.x + v.y * v.y + v.z * v.z).toDouble())
                }
            }
            delay(2_000)
            collector.cancel()
            collector.join()
            device.stopStreaming(sensor)

            // 100 Hz for ~2 s ≈ 200 samples; > 50 tolerates connection ramp-up.
            assertTrue(
                "expected > 50 samples in ~2 s at 100 Hz, got ${magnitudes.size}",
                magnitudes.size > 50,
            )
            val mean = magnitudes.average()
            assertTrue(
                "board should be at rest: mean |a| = $mean g, expected within 0.3 of 1 g",
                abs(mean - 1.0) < 0.3,
            )
            assertEquals(
                "device should return to Idle after the last stream stops",
                DeviceState.Idle,
                device.state.value,
            )
        }

    // ---- (f) Disconnect ----

    @Test
    fun disconnect_returnsToDisconnected() {
        val device = HardwareSupport.nearbyDevice()
        runBlocking {
            if (device.state.value != DeviceState.Disconnected) {
                runCatching { device.disconnect() }
            }
            withTimeout(30_000) { device.connect() }
            assertEquals(DeviceState.Idle, device.state.value)

            device.disconnect()
            assertEquals(DeviceState.Disconnected, device.state.value)
        }
    }
}
