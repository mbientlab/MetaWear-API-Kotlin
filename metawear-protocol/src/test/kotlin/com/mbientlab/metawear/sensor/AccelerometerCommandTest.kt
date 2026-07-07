package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.bytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Ported from MWModuleCommandTests.swift — accelerometer command + data-handler vectors. */
class AccelerometerBmi160CommandTest {

    private val sensor = AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G2)

    @Test fun startCommand() = assertArrayEquals(bytes(0x03, 0x01, 0x01), sensor.startCommand)
    @Test fun stopCommand() = assertArrayEquals(bytes(0x03, 0x01, 0x00), sensor.stopCommand)
    @Test fun enableCommand() = assertArrayEquals(bytes(0x03, 0x02, 0x01, 0x00), sensor.enableCommand)
    @Test fun disableCommand() = assertArrayEquals(bytes(0x03, 0x02, 0x00, 0x01), sensor.disableCommand)

    @Test fun config_odr100_g2() {
        // confByte = us(0) | bwp(2<<4=0x20) | odr(7+1=8) = 0x28 ; ±2g BMI160 range = 0x03
        assertEquals(1, sensor.configureCommands.size)
        assertArrayEquals(bytes(0x03, 0x03, 0x28, 0x03), sensor.configureCommands[0])
    }

    @Test fun config_odr25_g16() =
        assertArrayEquals(
            bytes(0x03, 0x03, 0x26, 0x0C),
            AccelerometerBmi160(AccelerometerBmi160.Odr.HZ25, AccelerometerBmi160.Range.G16).configureCommands[0],
        )

    @Test fun config_odr0_78_underSampling() =
        assertEquals(0x81, confByte(AccelerometerBmi160(AccelerometerBmi160.Odr.HZ0_78)))

    @Test fun config_odr6_25_underSampling() =
        assertEquals(0x84, confByte(AccelerometerBmi160(AccelerometerBmi160.Odr.HZ6_25)))

    @Test fun config_odr12_5_normalMode() =
        assertEquals(0x25, confByte(AccelerometerBmi160(AccelerometerBmi160.Odr.HZ12_5)))

    @Test fun config_odr200_g8() =
        assertArrayEquals(
            bytes(0x03, 0x03, 0x29, 0x08),
            AccelerometerBmi160(AccelerometerBmi160.Odr.HZ200, AccelerometerBmi160.Range.G8).configureCommands[0],
        )

    @Test fun config_odr100_g16() =
        assertArrayEquals(
            bytes(0x03, 0x03, 0x28, 0x0C),
            AccelerometerBmi160(AccelerometerBmi160.Odr.HZ100, AccelerometerBmi160.Range.G16).configureCommands[0],
        )

    @Test fun dataRegister() = assertEquals(0x04, sensor.dataRegister)
    @Test fun packedDataRegister() = assertEquals(0x1C, sensor.packedDataRegister)

    private fun confByte(s: AccelerometerBmi160): Int = s.configureCommands[0][2].toInt() and 0xFF
}

class AccelerometerBmi270CommandTest {

    private fun acc(
        odr: AccelerometerBmi270.Odr = AccelerometerBmi270.Odr.HZ100,
        range: AccelerometerBmi270.Range = AccelerometerBmi270.Range.G2,
    ) = AccelerometerBmi270(odr, range)

    // Reference vectors from MetaWear-SDK-Cpp test_accelerometer_bmi270.py.
    // acc_conf: bits[3:0]=odr, bits[6:4]=bwp(always 2), bit7=filter_perf(1 for ODR≥12.5Hz).

    @Test fun config_odr100_g2() =
        assertArrayEquals(bytes(0x03, 0x03, 0xA8, 0x00), acc().configureCommands[0])

    @Test fun config_odr200_g8() =
        assertArrayEquals(
            bytes(0x03, 0x03, 0xA9, 0x02),
            acc(AccelerometerBmi270.Odr.HZ200, AccelerometerBmi270.Range.G8).configureCommands[0],
        )

    @Test fun config_odr6_25_g2() =
        assertArrayEquals(bytes(0x03, 0x03, 0x24, 0x00), acc(AccelerometerBmi270.Odr.HZ6_25).configureCommands[0])

    @Test fun config_odr0_78_g2() =
        assertArrayEquals(bytes(0x03, 0x03, 0x21, 0x00), acc(AccelerometerBmi270.Odr.HZ0_78).configureCommands[0])

    @Test fun config_g16_rangeByte() =
        assertEquals(0x03, acc(AccelerometerBmi270.Odr.HZ100, AccelerometerBmi270.Range.G16).configureCommands[0][3].toInt() and 0xFF)

    @Test fun config_odr12_5_defaultRange8G() =
        assertArrayEquals(
            bytes(0x03, 0x03, 0xA5, 0x02),
            acc(AccelerometerBmi270.Odr.HZ12_5, AccelerometerBmi270.Range.G8).configureCommands[0],
        )

    @Test fun packedDataRegister_is0x05() = assertEquals(0x05, acc().packedDataRegister)
    @Test fun enableCommand() = assertArrayEquals(bytes(0x03, 0x02, 0x01, 0x00), acc().enableCommand)
    @Test fun disableCommand() = assertArrayEquals(bytes(0x03, 0x02, 0x00, 0x01), acc().disableCommand)

    // TestBmi270AccelerationData.test_get_acceleration_data_g (range 4G)
    @Test fun parseSample_4G_pythonVector() {
        val s = acc(AccelerometerBmi270.Odr.HZ100, AccelerometerBmi270.Range.G4)
            .parseSample(bytes(0x03, 0x04, 0x16, 0xC4, 0x94, 0xA2, 0x2A, 0xD0))
        assertEquals(-1.872f, s.x, 0.001f)
        assertEquals(-2.919f, s.y, 0.001f)
        assertEquals(-1.495f, s.z, 0.001f)
    }

    // TestBmi270HighFreqAccData.test_get_acceleration_data_g (range 8G, packed)
    @Test fun parsePackedSamples_8G_pythonVector() {
        val s = acc(AccelerometerBmi270.Odr.HZ100, AccelerometerBmi270.Range.G8).parsePackedSamples(
            bytes(
                0x03, 0x05,
                0x62, 0xB7, 0x53, 0x0D, 0xE9, 0xFD,
                0x16, 0xD0, 0x4D, 0x0E, 0x57, 0x02,
                0x8A, 0xFF, 0xA1, 0x05, 0x0A, 0x01,
            ),
        )
        assertEquals(3, s.size)
        assertEquals(-4.539f, s[0].x, 0.001f); assertEquals(0.833f, s[0].y, 0.001f); assertEquals(-0.131f, s[0].z, 0.001f)
        assertEquals(-2.995f, s[1].x, 0.001f); assertEquals(0.894f, s[1].y, 0.001f); assertEquals(0.146f, s[1].z, 0.001f)
        assertEquals(-0.029f, s[2].x, 0.001f); assertEquals(0.352f, s[2].y, 0.001f); assertEquals(0.065f, s[2].z, 0.001f)
    }
}
