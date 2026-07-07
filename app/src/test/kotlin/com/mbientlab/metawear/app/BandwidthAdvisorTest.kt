package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.core.BandwidthAdvisor
import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Port of `BandwidthAdvisorTests.swift`. */
class BandwidthAdvisorTest {

    @Test
    fun `aggregate sums selections`() {
        val selections = listOf(
            SensorSelection(SensorKey.ACCELEROMETER, hz = 100.0),
            SensorSelection(SensorKey.GYROSCOPE, hz = 100.0),
            SensorSelection(SensorKey.MAGNETOMETER, hz = 25.0),
        )
        assertEquals(225.0, BandwidthAdvisor.aggregateHz(selections))
    }

    @Test
    fun `isOverCeiling detects overflow`() {
        val over = listOf(
            SensorSelection(SensorKey.ACCELEROMETER, hz = 100.0),
            SensorSelection(SensorKey.GYROSCOPE, hz = 50.0),
        )
        val under = listOf(SensorSelection(SensorKey.ACCELEROMETER, hz = 100.0))
        assertTrue(BandwidthAdvisor.isOverCeiling(over))
        assertFalse(BandwidthAdvisor.isOverCeiling(under))
    }

    @Test
    fun `halved reduces every rate and respects the floor`() {
        val selections = listOf(
            SensorSelection(SensorKey.ACCELEROMETER, hz = 100.0),
            SensorSelection(SensorKey.GYROSCOPE, hz = 50.0),
            SensorSelection(SensorKey.MAGNETOMETER, hz = 1.0),
        )
        val halved = BandwidthAdvisor.halved(selections)
        assertEquals(listOf(50.0, 25.0, BandwidthAdvisor.MIN_HZ), halved.map { it.hz })
    }
}
