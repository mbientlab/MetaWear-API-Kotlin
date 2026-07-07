package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.data.ConfiguredSensor
import com.mbientlab.metawear.model.ModuleInfo
import com.mbientlab.metawear.protocol.Module
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure logic behind the environmental (polled) sensor picker. */
class PolledSelectionTest {

    @Test
    fun `interval labels collapse whole minutes`() {
        assertEquals("1 s", SensorSelection.formatPollInterval(1_000))
        assertEquals("10 s", SensorSelection.formatPollInterval(10_000))
        assertEquals("30 s", SensorSelection.formatPollInterval(30_000))
        assertEquals("1 m", SensorSelection.formatPollInterval(60_000))
        assertEquals("5 m", SensorSelection.formatPollInterval(300_000))
    }

    @Test
    fun `polled keys expose interval menus instead of rate and range`() {
        for (key in listOf(SensorKey.TEMPERATURE, SensorKey.HUMIDITY, SensorKey.PRESSURE)) {
            assertTrue(key.isPolled)
            assertFalse(key.isFusion)
            assertTrue(key.rateOptionsHz.isEmpty())
            assertTrue(key.rangeOptions.isEmpty())
            assertEquals(listOf(1_000L, 10_000L, 30_000L, 60_000L, 300_000L), key.pollIntervalOptionsMs)
        }
        // Streamed keys are untouched by the interval machinery.
        assertFalse(SensorKey.ACCELEROMETER.isPolled)
        assertTrue(SensorKey.ACCELEROMETER.pollIntervalOptionsMs.isEmpty())
        assertTrue(SensorKey.FUSION_QUATERNION.isFusion)
    }

    @Test
    fun `display label and rate follow the picked interval`() {
        val default = SensorSelection(SensorKey.TEMPERATURE)
        assertEquals("Temperature · every 1 s", default.displayLabel)
        assertEquals(1.0, default.samplesPerSecond)

        val slow = default.withPollInterval(10_000)
        assertEquals("Temperature · every 10 s", slow.displayLabel)
        assertEquals(0.1, slow.samplesPerSecond, 1e-9)
        assertEquals(10_000L, slow.effectivePollIntervalMs)
    }

    @Test
    fun `thermometer channel prefers the preset thermistor source`() {
        fun modulesWithTempSources(extra: List<Int>) = mapOf(
            Module.TEMPERATURE to ModuleInfo(Module.TEMPERATURE, implementation = 1, revision = 0, extra = extra),
        )
        // MMS layout: [die, preset, ext, bmp280] → preset at channel 1.
        assertEquals(1, ConfiguredSensor.thermometerChannel(modulesWithTempSources(listOf(0, 3, 1, 2))))
        // No preset thermistor → nRF die channel 0.
        assertEquals(0, ConfiguredSensor.thermometerChannel(modulesWithTempSources(listOf(0, 1))))
        // Module table missing entirely → channel 0.
        assertEquals(0, ConfiguredSensor.thermometerChannel(emptyMap()))
    }
}
