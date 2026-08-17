package com.mbientlab.metawear.app

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.app.data.PolledPressure
import com.mbientlab.metawear.app.demo.DemoBleTransport
import com.mbientlab.metawear.downloadLogs
import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.model.LoggedSample
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.PolledLogger
import com.mbientlab.metawear.recoverLoggers
import com.mbientlab.metawear.sensor.Accelerometer
import com.mbientlab.metawear.sensor.Altimeter
import com.mbientlab.metawear.sensor.AmbientLight
import com.mbientlab.metawear.sensor.Humidity
import com.mbientlab.metawear.sensor.SensorFusionChip
import com.mbientlab.metawear.sensor.SensorFusionMode
import com.mbientlab.metawear.sensor.SensorFusionQuaternion
import com.mbientlab.metawear.sensor.Settings
import com.mbientlab.metawear.sensor.Thermometer
import com.mbientlab.metawear.startLogging
import com.mbientlab.metawear.stopLogging
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end exercises of the demo emulator through the real
 * [MetaWearDevice] protocol stack — the same path the app takes against a
 * physical board, with no Android or Bluetooth anywhere.
 */
class DemoBleTransportTest {

    private fun <T> withDemoDevice(block: suspend (MetaWearDevice) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return try {
            runBlocking {
                val device = MetaWearDevice(
                    identifier = DemoBleTransport.DEVICE_IDENTIFIER,
                    transport = DemoBleTransport(scope),
                    scope = scope,
                )
                withTimeout(30_000) { block(device) }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `connect populates MetaMotion S identity and module table`() = withDemoDevice { device ->
        device.connect()

        val info = requireNotNull(device.deviceInfo)
        assertEquals("MbientLab Inc", info.manufacturer)
        assertEquals("8", info.modelNumber)          // MetaMotion S
        assertEquals("1.7.3", info.firmwareRevision)

        assertEquals(4, device.modules[Module.ACCELEROMETER]?.implementation)  // BMI270
        assertEquals(1, device.modules[Module.GYRO]?.implementation)           // BMI270
        assertTrue(device.hasSensorFusion)
        assertTrue(device.hasMagnetometer)

        device.disconnect()
    }

    @Test
    fun `battery and MAC reads answer like the demo device`() = withDemoDevice { device ->
        device.connect()

        val battery = device.read(Settings.ReadBatteryState()).value
        assertEquals(87, battery.charge)
        assertEquals(0x0FF0, battery.voltage)

        val mac = device.read(Settings.ReadMacAddress()).value
        assertEquals("DE:3D:0E:0D:E0:01", mac)

        device.disconnect()
    }

    @Test
    fun `accelerometer stream produces plausible 1g waveform`() = withDemoDevice { device ->
        device.connect()

        val sensor = Accelerometer.make(impl = 4, odrHz = 100.0, rangeG = 8f)!!
        val stream = device.startStream(sensor)
        val samples = stream.take(6).toList()

        assertEquals(6, samples.size)
        // Demo waveform: z ≈ 1 g with a ±0.02 wobble, x/y small.
        samples.forEach { sample ->
            assertTrue(abs(sample.value.z - 1f) < 0.1f, "z=${sample.value.z} should be ~1 g")
            assertTrue(abs(sample.value.x) < 0.2f)
        }

        device.stopStreaming(sensor)
        device.disconnect()
    }

    @Test
    fun `sensor fusion quaternion stream stays normalized`() = withDemoDevice { device ->
        device.connect()

        val sensor = SensorFusionQuaternion(
            mode = SensorFusionMode.NDOF,
            chip = SensorFusionChip.BMI270,
        )
        val stream = device.startStream(sensor)
        val samples = stream.take(4).toList()

        samples.forEach { sample ->
            val q = sample.value
            val norm = sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z)
            assertTrue(abs(norm - 1f) < 0.05f, "quaternion norm $norm should be ~1")
        }

        device.stopStreaming(sensor)
        device.disconnect()
    }

    @Test
    fun `logging round trip records, downloads, and decodes samples`() = withDemoDevice { device ->
        device.connect()

        val sensor = Accelerometer.make(impl = 4, odrHz = 100.0, rangeG = 8f)!!
        device.startLogging(sensor)
        delay(1_500)   // let the emulator "record" (~50 entries/s wall clock)
        device.stopLogging(sensor)

        var samples: List<LoggedSample<CartesianFloat>> = emptyList()
        var finalProgress = 0.0
        device.downloadLogs(sensor).collect { progress ->
            samples = progress.data
            finalProgress = progress.percentComplete
        }

        assertEquals(1.0, finalProgress, 1e-9)
        assertTrue(samples.isNotEmpty(), "download should decode at least one sample")
        // Decoded waveform is the same z ≈ 1 g accel shape.
        samples.forEach { assertTrue(abs(it.value.z - 1f) < 0.1f) }
        // Ticks advance monotonically.
        assertTrue(samples.zipWithNext().all { (a, b) -> b.tickMs >= a.tickMs })

        device.disconnect()
    }

    // ---- Polled (environmental) surface ----

    @Test
    fun `humidity and pressure one-shot reads answer plausible values`() = withDemoDevice { device ->
        device.connect()

        val humidity = device.read(Humidity()).value
        assertTrue(abs(humidity - 45f) < 8f, "humidity $humidity should be ~45 %")

        val pressure = device.read(PolledPressure()).value
        assertTrue(abs(pressure - 101_325f) < 50f, "pressure $pressure should be ~1 atm")

        device.disconnect()
    }

    @Test
    fun `poll emits periodic thermometer readings for the live tile`() = withDemoDevice { device ->
        device.connect()

        val readings = device.poll(Thermometer(channel = 1), every = 50.milliseconds)
            .take(3)
            .toList()

        assertEquals(3, readings.size)
        readings.forEach { assertTrue(abs(it.value - 22.5f) < 1f, "temp ${it.value} should be ~22.5 °C") }
        assertTrue(readings.zipWithNext().all { (a, b) -> b.time >= a.time })

        device.disconnect()
    }

    @Test
    fun `polled thermometer log round trip decodes via the timer-event chain`() = withDemoDevice { device ->
        device.connect()

        val logger = PolledLogger(Thermometer(channel = 1), periodMs = 1_000)
        val handles = device.startLogging(logger)
        assertEquals(1, handles.loggerIDs.size)   // one 2-byte chunk

        delay(1_200)   // demo "records" while LOG_ENABLE is on
        device.stopLogging(logger, handles)

        var samples: List<LoggedSample<Float>> = emptyList()
        var finalProgress = 0.0
        device.downloadLogs(logger).collect { progress ->
            samples = progress.data
            finalProgress = progress.percentComplete
        }

        assertEquals(1.0, finalProgress, 1e-9)
        assertTrue(samples.isNotEmpty(), "polled download should decode at least one sample")
        samples.forEach { assertTrue(abs(it.value - 22.5f) < 1f, "temp ${it.value} should be ~22.5 °C") }
        assertTrue(samples.zipWithNext().all { (a, b) -> b.tickMs >= a.tickMs })

        device.disconnect()
    }

    @Test
    fun `polled humidity log round trip decodes percent values`() = withDemoDevice { device ->
        device.connect()

        val logger = PolledLogger(Humidity(), periodMs = 1_000)
        val handles = device.startLogging(logger)

        delay(1_200)
        device.stopLogging(logger, handles)

        var samples: List<LoggedSample<Float>> = emptyList()
        device.downloadLogs(logger).collect { samples = it.data }

        assertTrue(samples.isNotEmpty(), "polled humidity download should decode samples")
        samples.forEach { assertTrue(abs(it.value - 45f) < 8f, "humidity ${it.value} should be ~45 %") }

        device.disconnect()
    }

    // ---- Streamed environmental surface ----

    @Test
    fun `ambient light stream produces plausible lux waveform`() = withDemoDevice { device ->
        device.connect()

        val sensor = AmbientLight()
        val samples = device.startStream(sensor).take(4).toList()

        // Demo waveform: (320 ± 90) lux, streamed as raw milli-lux Longs.
        samples.forEach { sample ->
            val lux = AmbientLight.lux(sample.value)
            assertTrue(abs(lux - 320f) < 120f, "lux $lux should be ~320")
        }

        device.stopStreaming(sensor)
        device.disconnect()
    }

    @Test
    fun `altimeter stream produces plausible altitude`() = withDemoDevice { device ->
        device.connect()

        val sensor = Altimeter()
        val samples = device.startStream(sensor).take(4).toList()

        // Demo waveform: (112 ± 2) m.
        samples.forEach { assertTrue(abs(it.value - 112f) < 4f, "altitude ${it.value} should be ~112 m") }

        device.stopStreaming(sensor)
        device.disconnect()
    }

    // ---- Pending sessions across a "process restart" ----
    // A second MetaWearDevice over the SAME transport models an app relaunch:
    // fresh in-memory state on the host, board-side loggers still armed.

    @Test
    fun `streamed log survives restart via recoverLoggers`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                withTimeout(30_000) {
                    val transport = DemoBleTransport(scope)
                    val sensor = Accelerometer.make(impl = 4, odrHz = 100.0, rangeG = 8f)!!

                    // "First launch": arm the logger, then die without stopping.
                    val first = MetaWearDevice(DemoBleTransport.DEVICE_IDENTIFIER, transport, scope)
                    first.connect()
                    first.startLogging(sensor)
                    delay(1_200)
                    first.disconnect()

                    // "Relaunch": fresh device, empty in-memory registry.
                    val second = MetaWearDevice(DemoBleTransport.DEVICE_IDENTIFIER, transport, scope)
                    second.connect()
                    second.stopLogging(sensor)
                    second.recoverLoggers(sensor)

                    var samples: List<LoggedSample<CartesianFloat>> = emptyList()
                    second.downloadLogs(sensor).collect { samples = it.data }

                    assertTrue(samples.isNotEmpty(), "restart download should decode samples")
                    samples.forEach { assertTrue(abs(it.value.z - 1f) < 0.1f) }
                    second.disconnect()
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `polled log survives restart via persisted handles and recoverLoggers`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                withTimeout(30_000) {
                    val transport = DemoBleTransport(scope)
                    val logger = PolledLogger(Thermometer(channel = 1), periodMs = 1_000)

                    val first = MetaWearDevice(DemoBleTransport.DEVICE_IDENTIFIER, transport, scope)
                    first.connect()
                    val handles = first.startLogging(logger)   // ← the app persists these
                    delay(1_200)
                    first.disconnect()

                    val second = MetaWearDevice(DemoBleTransport.DEVICE_IDENTIFIER, transport, scope)
                    second.connect()
                    second.recoverLoggers(logger)
                    second.stopLogging(logger, handles)        // handles round-tripped the codec

                    var samples: List<LoggedSample<Float>> = emptyList()
                    second.downloadLogs(logger).collect { samples = it.data }

                    assertTrue(samples.isNotEmpty(), "polled restart download should decode samples")
                    samples.forEach { assertTrue(abs(it.value - 22.5f) < 1f) }
                    second.disconnect()
                }
            }
        } finally {
            scope.cancel()
        }
    }
}
