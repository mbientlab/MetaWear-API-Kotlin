package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.core.SessionAxisStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Chart-style recovery for persisted sessions (kind + capture-time label). */
class SessionAxisStyleTest {

    @Test
    fun `quaternion kind labels wxyz in order`() {
        val style = SessionAxisStyle.forSession("quaternion", label = null, channelCount = 4)
        assertEquals(listOf("w", "x", "y", "z"), style.labels)
        assertEquals(4, style.chartChannels)
    }

    @Test
    fun `euler kind labels euler channels with degree unit`() {
        val style = SessionAxisStyle.forSession("euler", label = null, channelCount = 4)
        assertEquals(listOf("heading", "pitch", "roll", "yaw"), style.labels)
        assertEquals("°", style.unit)
    }

    @Test
    fun `gyro label restores unit and captured range`() {
        val style = SessionAxisStyle.forSession(
            "cartesian", label = "Gyroscope · ±500 dps · 25 Hz", channelCount = 3,
        )
        assertEquals("dps", style.unit)
        assertEquals(-500f..500f, style.yRange)
        assertEquals(listOf("x", "y", "z"), style.labels)
    }

    @Test
    fun `accel label without range chunk uses sensor default`() {
        val style = SessionAxisStyle.forSession(
            "cartesian", label = "Accelerometer · 50 Hz", channelCount = 3,
        )
        assertEquals("g", style.unit)
        assertEquals(-2f..2f, style.yRange)   // the sensor's default chart range
    }

    @Test
    fun `fusion gravity label resolves fusion output`() {
        val style = SessionAxisStyle.forSession(
            "cartesian", label = "Gravity · 100 Hz", channelCount = 3,
        )
        assertEquals("g", style.unit)
        assertEquals(-1f..1f, style.yRange)
    }

    @Test
    fun `temperature label resolves scalar style`() {
        val style = SessionAxisStyle.forSession(
            "float", label = "Temperature · every 1 s", channelCount = 1,
        )
        assertEquals("°C", style.unit)
        assertEquals(1, style.chartChannels)
    }

    @Test
    fun `unknown label falls back to generic`() {
        val style = SessionAxisStyle.forSession(
            "cartesian", label = "Mystery Sensor · 1 Hz", channelCount = 3,
        )
        assertEquals(listOf("x", "y", "z"), style.labels)
        assertEquals("", style.unit)
        assertNull(style.yRange)
    }

    @Test
    fun `legacy record with nil label falls back to generic`() {
        val style = SessionAxisStyle.forSession("cartesian", label = null, channelCount = 3)
        assertEquals(3, style.chartChannels)
        assertEquals("", style.unit)
    }

    @Test
    fun `generic channel count clamps to one through four`() {
        assertEquals(1, SessionAxisStyle.generic(0).chartChannels)
        assertEquals(4, SessionAxisStyle.generic(9).chartChannels)
        assertEquals(listOf("x", "y"), SessionAxisStyle.generic(2).labels)
    }
}
