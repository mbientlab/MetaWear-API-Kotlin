package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.autoReplyModuleDiscovery
import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.makeConnectableTransport
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.transport.MockBleTransport
import com.mbientlab.metawear.transport.Uuids
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Ported from MWSerialTests.swift.
//
// Reference vectors from:
//   MetaWear-SDK-Cpp/src/metawear/peripheral/cpp/serialpassthrough.cpp
//   MetaWear-SDK-Cpp/test/backup/test_i2c.py
// C++ layouts:
//   I2C write: [0x0D, 0x01, dev, reg, 0xFF, length, data...]
//   I2C read : [0x0D, 0xC1, dev, reg, id,   length]
//   SPI write: [0x0D, 0x02, ss, clk, mosi, miso, bitfield, data...]
//   SPI read : [0x0D, 0xC2, ss, clk, mosi, miso, bitfield, (length-1)|(id<<4), writeData...]
// SPI bitfield: bit0 lsb_first | bits1-2 mode | bits3-5 frequency | bit6 use_nrf | bit7 pad(0)

class SerialI2cWriteTest {

    @Test
    fun `module and register bytes`() {
        val cmd = Serial.I2cWrite(deviceAddress = 0x68, registerAddress = 0x6B, data = bytes(0xAA))
        assertEquals(0x0D, cmd.commandData[0].toInt() and 0xFF)  // module
        assertEquals(0x01, cmd.commandData[1].toInt() and 0xFF)  // register I2C_READ_WRITE
    }

    @Test
    fun `address bytes`() {
        val cmd = Serial.I2cWrite(deviceAddress = 0x68, registerAddress = 0x6B, data = bytes(0xAA))
        assertEquals(0x68, cmd.commandData[2].toInt() and 0xFF)  // device address
        assertEquals(0x6B, cmd.commandData[3].toInt() and 0xFF)  // register address
    }

    // The 0xFF at offset 4 is the firmware's signal-id placeholder for plain
    // writes; it's ignored by firmware on write paths but must be present.
    @Test
    fun `placeholder byte is 0xFF`() {
        val cmd = Serial.I2cWrite(deviceAddress = 0x68, registerAddress = 0x6B, data = bytes(0xAA))
        assertEquals(0xFF, cmd.commandData[4].toInt() and 0xFF)
    }

    @Test
    fun `length byte`() {
        val cmd = Serial.I2cWrite(deviceAddress = 0x68, registerAddress = 0x6B, data = bytes(0xAA, 0xBB))
        assertEquals(2, cmd.commandData[5].toInt() and 0xFF)
    }

    @Test
    fun `data bytes`() {
        val cmd = Serial.I2cWrite(deviceAddress = 0x68, registerAddress = 0x6B, data = bytes(0xAA, 0xBB))
        assertEquals(0xAA, cmd.commandData[6].toInt() and 0xFF)
        assertEquals(0xBB, cmd.commandData[7].toInt() and 0xFF)
    }

    @Test
    fun `total length with one data byte`() {
        val cmd = Serial.I2cWrite(deviceAddress = 0x68, registerAddress = 0x6B, data = bytes(0x00))
        // [module, register, dev, reg, 0xFF, length, data] = 7 bytes
        assertEquals(7, cmd.commandData.size)
    }

    @Test
    fun `total length with three data bytes`() {
        val cmd = Serial.I2cWrite(deviceAddress = 0x68, registerAddress = 0x6B, data = bytes(0x01, 0x02, 0x03))
        assertEquals(9, cmd.commandData.size)
    }

    @Test
    fun `empty data keeps placeholder and zero length`() {
        val cmd = Serial.I2cWrite(deviceAddress = 0x10, registerAddress = 0x00, data = ByteArray(0))
        assertEquals(6, cmd.commandData.size)
        assertEquals(0xFF, cmd.commandData[4].toInt() and 0xFF)  // placeholder still present
        assertEquals(0, cmd.commandData[5].toInt() and 0xFF)     // length = 0
    }

    // Exact byte-vector check covering the full I2C write shape.
    @Test
    fun `full vector`() {
        val cmd = Serial.I2cWrite(deviceAddress = 0x68, registerAddress = 0x6B, data = bytes(0xDE, 0xAD))
        assertArrayEquals(bytes(0x0D, 0x01, 0x68, 0x6B, 0xFF, 0x02, 0xDE, 0xAD), cmd.commandData)
    }

    // Firmware buffers at most 10 data bytes per write (register table:
    // "Data Length (max 10)"). Exactly 10 is allowed; 11 must throw.
    @Test
    fun `max payload of ten bytes allowed`() {
        val cmd = Serial.I2cWrite(
            deviceAddress = 0x68, registerAddress = 0x6B,
            data = ByteArray(10) { 0xAA.toByte() },
        )
        assertEquals(10, cmd.commandData[5].toInt() and 0xFF)
        assertEquals(16, cmd.commandData.size)
    }

    @Test
    fun `overlong payload throws`() {
        val error = runCatching {
            Serial.I2cWrite(
                deviceAddress = 0x68, registerAddress = 0x6B,
                data = ByteArray(11) { 0xAA.toByte() },
            )
        }.exceptionOrNull()
        assertTrue(error is MetaWearException.OperationFailed)
    }
}

class SerialSpiBitfieldTest {

    // Base parameter set used for bitfield-only variations.
    private fun base(
        mode: Serial.SpiMode = Serial.SpiMode.MODE0,
        frequency: Serial.SpiClock = Serial.SpiClock.F1_MHZ,
        lsbFirst: Boolean = false,
        useNrfPins: Boolean = false,
    ) = Serial.SpiParameters(
        slaveSelectPin = 10, clockPin = 11, mosiPin = 12, misoPin = 13,
        mode = mode, frequency = frequency,
        lsbFirst = lsbFirst, useNrfPins = useNrfPins,
    )

    @Test
    fun `defaults lsbFirst false, nrf false, mode0, 1 MHz`() {
        // mode0=0<<1=0, f1MHz=3<<3=0x18, lsbFirst=0, nrf=0 → 0x18
        assertEquals(0x18, base().bitfield)
    }

    @Test
    fun `lsbFirst sets bit 0`() {
        assertEquals(0x01, base(lsbFirst = true).bitfield and 0x01)
    }

    @Test
    fun `mode occupies bits 1 and 2`() {
        assertEquals(0x00, base(mode = Serial.SpiMode.MODE0).bitfield and 0x06)
        assertEquals(0x02, base(mode = Serial.SpiMode.MODE1).bitfield and 0x06)
        assertEquals(0x04, base(mode = Serial.SpiMode.MODE2).bitfield and 0x06)
        assertEquals(0x06, base(mode = Serial.SpiMode.MODE3).bitfield and 0x06)
    }

    @Test
    fun `frequency occupies bits 3 to 5`() {
        assertEquals(0x00, base(frequency = Serial.SpiClock.F125_KHZ).bitfield and 0x38)
        assertEquals(0x08, base(frequency = Serial.SpiClock.F250_KHZ).bitfield and 0x38)
        assertEquals(0x10, base(frequency = Serial.SpiClock.F500_KHZ).bitfield and 0x38)
        assertEquals(0x18, base(frequency = Serial.SpiClock.F1_MHZ).bitfield and 0x38)
        assertEquals(0x20, base(frequency = Serial.SpiClock.F2_MHZ).bitfield and 0x38)
        assertEquals(0x28, base(frequency = Serial.SpiClock.F4_MHZ).bitfield and 0x38)
        assertEquals(0x30, base(frequency = Serial.SpiClock.F8_MHZ).bitfield and 0x38)
    }

    @Test
    fun `useNrfPins sets bit 6`() {
        assertEquals(0x40, base(useNrfPins = true).bitfield and 0x40)
    }

    @Test
    fun `bit 7 always zero`() {
        // Any combination of the public options must leave bit 7 clear.
        val p = Serial.SpiParameters(
            slaveSelectPin = 0, clockPin = 0, mosiPin = 0, misoPin = 0,
            mode = Serial.SpiMode.MODE3, frequency = Serial.SpiClock.F8_MHZ,
            lsbFirst = true, useNrfPins = true,
        )
        assertEquals(0, p.bitfield and 0x80)
    }

    @Test
    fun `all bits set combined`() {
        // lsbFirst | mode3 | f8MHz | useNRF = 0x01 | 0x06 | 0x30 | 0x40 = 0x77
        val p = Serial.SpiParameters(
            slaveSelectPin = 0, clockPin = 0, mosiPin = 0, misoPin = 0,
            mode = Serial.SpiMode.MODE3, frequency = Serial.SpiClock.F8_MHZ,
            lsbFirst = true, useNrfPins = true,
        )
        assertEquals(0x77, p.bitfield)
    }

    @Test
    fun `encodedBytes layout`() {
        val p = Serial.SpiParameters(
            slaveSelectPin = 10, clockPin = 11, mosiPin = 12, misoPin = 13,
            mode = Serial.SpiMode.MODE0, frequency = Serial.SpiClock.F1_MHZ,
        )
        assertArrayEquals(bytes(10, 11, 12, 13, 0x18), p.encodedBytes)
    }
}

class SerialSpiWriteTest {

    private val params = Serial.SpiParameters(
        slaveSelectPin = 10, clockPin = 11, mosiPin = 12, misoPin = 13,
        mode = Serial.SpiMode.MODE0, frequency = Serial.SpiClock.F1_MHZ,
    )

    @Test
    fun `module and register bytes`() {
        val cmd = Serial.SpiWrite(parameters = params, data = bytes(0x9F))
        assertEquals(0x0D, cmd.commandData[0].toInt() and 0xFF)  // module
        assertEquals(0x02, cmd.commandData[1].toInt() and 0xFF)  // register SPI_READ_WRITE
    }

    @Test
    fun `pin bytes`() {
        val cmd = Serial.SpiWrite(parameters = params, data = bytes(0x00))
        assertEquals(10, cmd.commandData[2].toInt() and 0xFF)
        assertEquals(11, cmd.commandData[3].toInt() and 0xFF)
        assertEquals(12, cmd.commandData[4].toInt() and 0xFF)
        assertEquals(13, cmd.commandData[5].toInt() and 0xFF)
    }

    @Test
    fun `bitfield byte`() {
        val cmd = Serial.SpiWrite(parameters = params, data = bytes(0x00))
        // mode0=0, f1MHz=3, lsbFirst=false, nrf=false → 0x18
        assertEquals(0x18, cmd.commandData[6].toInt() and 0xFF)
    }

    @Test
    fun `data bytes`() {
        val cmd = Serial.SpiWrite(parameters = params, data = bytes(0xDE, 0xAD))
        assertEquals(0xDE, cmd.commandData[7].toInt() and 0xFF)
        assertEquals(0xAD, cmd.commandData[8].toInt() and 0xFF)
    }

    @Test
    fun `total length`() {
        val cmd = Serial.SpiWrite(parameters = params, data = bytes(0x00))
        // [module, register, ss, clk, mosi, miso, bitfield, data] = 8
        assertEquals(8, cmd.commandData.size)
    }

    @Test
    fun `full vector`() {
        val cmd = Serial.SpiWrite(parameters = params, data = bytes(0xAA, 0xBB))
        assertArrayEquals(bytes(0x0D, 0x02, 10, 11, 12, 13, 0x18, 0xAA, 0xBB), cmd.commandData)
    }
}

/** Device-level read tests — I2C/SPI reads respond with plain notifications. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent
class SerialReadTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    private val params = Serial.SpiParameters(
        slaveSelectPin = 10, clockPin = 11, mosiPin = 12, misoPin = 13,
        mode = Serial.SpiMode.MODE0, frequency = Serial.SpiClock.F1_MHZ,
    )

    private suspend fun TestScope.connectedDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice(mac, transport, backgroundScope)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport)
        device.connect()
        discovery.cancel()
        return device to transport
    }

    // Python `test_i2c.py::test_read_who_am_i` exact vector.
    // [0x0d, 0xc1, 0x1c, 0x0d, 0x0a, 0x01] — dev=0x1C, reg=0x0D, id=0x0A, length=1
    // 0xC1 = 0x01 | 0x80 (read bit) | 0x40 (data_id bit); id sits BEFORE length.
    @Test
    fun `i2cRead sends the python who-am-i vector and strips the response prefix`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()

        val read = async { device.i2cRead(deviceAddress = 0x1C, registerAddress = 0x0D, length = 1, id = 0x0A) }
        runCurrent() // command written; notify waiter parked

        assertArrayEquals(bytes(0x0D, 0xC1, 0x1C, 0x0D, 0x0A, 0x01), transport.writtenCommands.first())

        // Board replies on register 0x01 with [0x0D, 0x01, id, data...] —
        // a plain notification, NOT a bit-7 read response.
        transport.inject(bytes(0x0D, 0x01, 0x0A, 0x2A), Uuids.notify)
        runCurrent()
        assertArrayEquals(bytes(0x2A), read.await())
    }

    @Test
    fun `i2cRead register byte is 0xC1 not 0x81`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()

        val read = async { device.i2cRead(deviceAddress = 0x68, registerAddress = 0x75, length = 1) }
        runCurrent()

        val cmd = transport.writtenCommands.first()
        assertEquals(0xC1, cmd[1].toInt() and 0xFF)
        // id BEFORE length, per C++ source.
        assertEquals(0x00, cmd[4].toInt() and 0xFF)  // id
        assertEquals(0x01, cmd[5].toInt() and 0xFF)  // length

        transport.inject(bytes(0x0D, 0x01, 0x00, 0x71), Uuids.notify)
        runCurrent()
        assertArrayEquals(bytes(0x71), read.await())
    }

    @Test
    fun `i2cRead rejects zero length`() = runTest {
        val (device, _) = connectedDevice()
        val error = runCatching {
            device.i2cRead(deviceAddress = 0x68, registerAddress = 0x75, length = 0)
        }.exceptionOrNull()
        assertTrue(error is MetaWearException.OperationFailed)
    }

    // ---- SPI read ----

    @Test
    fun `spiRead register byte is 0xC2 and packs length minus one in the low nibble`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()

        // length=4, id=0 → ((4-1) & 0x0F) | (0<<4) = 0x03
        val read = async { device.spiRead(parameters = params, length = 4, id = 0) }
        runCurrent()

        assertArrayEquals(bytes(0x0D, 0xC2, 10, 11, 12, 13, 0x18, 0x03), transport.writtenCommands.first())

        // Response: [0x0D, 0x02, id, data...] — plain notification on register 0x02.
        transport.inject(bytes(0x0D, 0x02, 0x00, 0xDE, 0xAD, 0xBE, 0xEF), Uuids.notify)
        runCurrent()
        assertArrayEquals(bytes(0xDE, 0xAD, 0xBE, 0xEF), read.await())
    }

    @Test
    fun `spiRead packs the id into the high nibble`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()

        // length=1, id=5 → ((1-1) & 0x0F) | (5<<4) = 0x50
        val read = async { device.spiRead(parameters = params, length = 1, id = 5) }
        runCurrent()

        assertArrayEquals(bytes(0x0D, 0xC2, 10, 11, 12, 13, 0x18, 0x50), transport.writtenCommands.first())

        transport.inject(bytes(0x0D, 0x02, 0x05, 0x2A), Uuids.notify)
        runCurrent()
        assertArrayEquals(bytes(0x2A), read.await())
    }

    @Test
    fun `spiRead max length and id pack to 0xFF`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()

        // length=16, id=15 → ((16-1) & 0x0F) | (15<<4) = 0x0F | 0xF0 = 0xFF
        val read = async { device.spiRead(parameters = params, length = 16, id = 15) }
        runCurrent()

        assertArrayEquals(bytes(0x0D, 0xC2, 10, 11, 12, 13, 0x18, 0xFF), transport.writtenCommands.first())

        transport.inject(bytes(0x0D, 0x02, 0x0F), Uuids.notify)
        runCurrent()
        assertArrayEquals(ByteArray(0), read.await())
    }

    @Test
    fun `spiRead appends optional write data after the packed byte`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()

        val read = async {
            device.spiRead(parameters = params, length = 5, id = 0x0E, writeData = bytes(0xDA))
        }
        runCurrent()

        // packed = ((5-1) & 0x0F) | (0x0E << 4) = 0x04 | 0xE0 = 0xE4
        assertArrayEquals(bytes(0x0D, 0xC2, 10, 11, 12, 13, 0x18, 0xE4, 0xDA), transport.writtenCommands.first())

        transport.inject(bytes(0x0D, 0x02, 0x0E, 0x01, 0x02, 0x03, 0x04, 0x05), Uuids.notify)
        runCurrent()
        assertArrayEquals(bytes(0x01, 0x02, 0x03, 0x04, 0x05), read.await())
    }

    @Test
    fun `spiRead rejects out-of-range length and id`() = runTest {
        val (device, _) = connectedDevice()

        val tooShort = runCatching { device.spiRead(parameters = params, length = 0) }.exceptionOrNull()
        val tooLong = runCatching { device.spiRead(parameters = params, length = 17) }.exceptionOrNull()
        val badId = runCatching { device.spiRead(parameters = params, length = 1, id = 16) }.exceptionOrNull()

        assertTrue(tooShort is MetaWearException.OperationFailed)
        assertTrue(tooLong is MetaWearException.OperationFailed)
        assertTrue(badId is MetaWearException.OperationFailed)
    }
}

class SerialEnumTest {

    @Test
    fun `spi clock raw values`() {
        assertEquals(0, Serial.SpiClock.F125_KHZ.raw)
        assertEquals(1, Serial.SpiClock.F250_KHZ.raw)
        assertEquals(2, Serial.SpiClock.F500_KHZ.raw)
        assertEquals(3, Serial.SpiClock.F1_MHZ.raw)
        assertEquals(4, Serial.SpiClock.F2_MHZ.raw)
        assertEquals(5, Serial.SpiClock.F4_MHZ.raw)
        assertEquals(6, Serial.SpiClock.F8_MHZ.raw)
    }

    @Test
    fun `spi mode raw values`() {
        assertEquals(0, Serial.SpiMode.MODE0.raw)
        assertEquals(1, Serial.SpiMode.MODE1.raw)
        assertEquals(2, Serial.SpiMode.MODE2.raw)
        assertEquals(3, Serial.SpiMode.MODE3.raw)
    }

    @Test
    fun `serial module opcode is 0x0D`() {
        assertEquals(0x0D, Module.SERIAL.value)
    }
}
