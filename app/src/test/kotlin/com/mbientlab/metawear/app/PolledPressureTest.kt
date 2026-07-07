package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.data.PolledPressure
import com.mbientlab.metawear.protocol.LogChunk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The app-side polled-loggable adapter for barometer pressure. */
class PolledPressureTest {

    private val sensor = PolledPressure()

    @Test
    fun `read command targets the pressure register with the read bit`() {
        assertArrayEquals(byteArrayOf(0x12, 0x81.toByte()), sensor.readCommand)
        // Logger trigger register carries read + silent bits (0xC1) so the
        // timer-driven silent reads match — same rule as the SDK conformers.
        assertEquals(0xC1, sensor.loggerTriggerRegister)
        assertEquals(0xFF, sensor.loggerTriggerIndex)
    }

    @Test
    fun `one 4-byte chunk and Pa scaling round-trip`() {
        assertEquals(listOf(LogChunk(0, 4)), sensor.logDataChunks)

        // 101325 Pa × 256, little-endian — as replayed from the flash log.
        val raw = 101_325L * 256
        val bytes = byteArrayOf(
            (raw and 0xFF).toByte(),
            ((raw shr 8) and 0xFF).toByte(),
            ((raw shr 16) and 0xFF).toByte(),
            ((raw shr 24) and 0xFF).toByte(),
        )
        assertEquals(101_325f, sensor.parseLogSample(bytes), 0.01f)
        assertEquals(101_325f, sensor.parseSample(byteArrayOf(0x12, 0x81.toByte()) + bytes), 0.01f)
    }
}
