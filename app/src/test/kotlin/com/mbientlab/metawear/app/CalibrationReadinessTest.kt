package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.core.CalibrationReadiness
import com.mbientlab.metawear.sensor.SensorFusionCalibration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** "Medium is the bar" readiness logic behind the calibration badge. */
class CalibrationReadinessTest {

    @Test
    fun `all high reads fully calibrated with no coaching`() {
        val calibration = SensorFusionCalibration(3, 3, 3)
        assertEquals(CalibrationReadiness.State.FULLY_CALIBRATED, CalibrationReadiness.state(calibration))
        assertTrue(CalibrationReadiness.coaching(calibration).isEmpty())
    }

    @Test
    fun `medium everywhere is ready - the bar is medium, not high`() {
        val calibration = SensorFusionCalibration(2, 3, 2)
        assertEquals(CalibrationReadiness.State.READY, CalibrationReadiness.state(calibration))
        assertTrue(CalibrationReadiness.coaching(calibration).isEmpty())
    }

    @Test
    fun `any sensor below medium is calibrating with coaching for it alone`() {
        val calibration = SensorFusionCalibration(3, 3, 1)   // mag demoted indoors
        assertEquals(CalibrationReadiness.State.CALIBRATING, CalibrationReadiness.state(calibration))
        val coaching = CalibrationReadiness.coaching(calibration)
        assertEquals(listOf("M"), coaching.map { it.sensor })
        assertTrue(coaching[0].advice.contains("figure-8"))
    }

    @Test
    fun `every below-bar sensor gets its own line`() {
        val coaching = CalibrationReadiness.coaching(SensorFusionCalibration(0, 1, 0))
        assertEquals(listOf("A", "G", "M"), coaching.map { it.sensor })
    }

    @Test
    fun `level names span unreliable to high`() {
        assertEquals("unreliable", CalibrationReadiness.levelName(0))
        assertEquals("low", CalibrationReadiness.levelName(1))
        assertEquals("medium", CalibrationReadiness.levelName(2))
        assertEquals("high", CalibrationReadiness.levelName(3))
    }
}
