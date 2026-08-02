package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.data.foreignLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Session labels for recovered anonymous-signal identifiers. */
class ForeignLabelTest {

    @Test
    fun `plain acceleration reads recovered`() {
        assertEquals("Accelerometer · Recovered", foreignLabel("acceleration"))
    }

    @Test
    fun `channel suffix is stripped for the head`() {
        assertEquals("Accelerometer · Recovered", foreignLabel("acceleration[0]"))
        assertEquals("Temperature · Recovered", foreignLabel("temperature[1]"))
    }

    @Test
    fun `imu signals map to their sensor names`() {
        assertEquals("Gyroscope · Recovered", foreignLabel("angular-velocity"))
        assertEquals("Magnetometer · Recovered", foreignLabel("magnetic-field"))
    }

    @Test
    fun `fusion signals carry the fusion prefix`() {
        assertEquals("Fusion · Quaternion · Recovered", foreignLabel("quaternion"))
        assertEquals("Fusion · Linear Acceleration · Recovered", foreignLabel("linear-acceleration"))
    }

    @Test
    fun `processor chains keep the full identifier`() {
        assertEquals(
            "Accelerometer · acceleration:rms?id=0:accumulate?id=1",
            foreignLabel("acceleration:rms?id=0:accumulate?id=1"),
        )
    }

    @Test
    fun `unknown roots read unknown`() {
        assertEquals("Unknown · mystery-signal", foreignLabel("mystery-signal"))
    }

    @Test
    fun `label drives the export tag prefix`() {
        assertTrue(foreignLabel("acceleration").startsWith("Accelerometer"))
        assertTrue(foreignLabel("quaternion").startsWith("Fusion · Quaternion"))
    }
}
