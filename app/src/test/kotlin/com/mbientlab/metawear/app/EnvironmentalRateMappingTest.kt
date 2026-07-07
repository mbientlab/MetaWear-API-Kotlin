package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.data.ConfiguredSensor
import com.mbientlab.metawear.sensor.AmbientLight
import com.mbientlab.metawear.sensor.Barometer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Nominal-Hz → chip-config mappings for the streamed environmental sensors. */
class EnvironmentalRateMappingTest {

    @Test
    fun `barometer standby follows the rate menu`() {
        assertEquals(Barometer.BmpStandbyTime.MS1000, ConfiguredSensor.barometerStandbyFor(1.0))
        assertEquals(Barometer.BmpStandbyTime.MS125, ConfiguredSensor.barometerStandbyFor(8.0))
        assertEquals(Barometer.BmpStandbyTime.MS0_5, ConfiguredSensor.barometerStandbyFor(25.0))
    }

    @Test
    fun `light measurement rate snaps to the nearest LTR329 period`() {
        assertEquals(AmbientLight.MeasurementRate.MS2000, ConfiguredSensor.lightRateFor(0.5))
        assertEquals(AmbientLight.MeasurementRate.MS1000, ConfiguredSensor.lightRateFor(1.0))
        assertEquals(AmbientLight.MeasurementRate.MS500, ConfiguredSensor.lightRateFor(2.0))
        assertEquals(AmbientLight.MeasurementRate.MS200, ConfiguredSensor.lightRateFor(5.0))
        assertEquals(AmbientLight.MeasurementRate.MS100, ConfiguredSensor.lightRateFor(10.0))
        assertEquals(AmbientLight.MeasurementRate.MS50, ConfiguredSensor.lightRateFor(20.0))
    }
}
