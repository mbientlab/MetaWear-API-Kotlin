package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet

// Port of MWSerial.swift — serial passthrough module (0x0D).
//
// Mirrors C++ `serialpassthrough.cpp` (declared across `i2c.h` + `spi.h`).
// The module id is 0x0D (`MBL_MW_MODULE_I2C` — shared I2C/SPI passthrough).
//
// Registers:
//   I2C_READ_WRITE = 0x01   write / read-with-id (read bit → 0xC1)
//   SPI_READ_WRITE = 0x02   write / read-with-id (read bit → 0xC2)
//
// Write shape:
//   I2C  [0x0D, 0x01, dev_addr, reg_addr, 0xFF, length, data...]
//   SPI  [0x0D, 0x02, ss_pin, clock_pin, mosi_pin, miso_pin, bitfield, data...]
//
// Read shape:
//   I2C  [0x0D, 0xC1, dev_addr, reg_addr, id, length]
//   SPI  [0x0D, 0xC2, ss_pin, clock_pin, mosi_pin, miso_pin, bitfield, (length-1)|(id<<4), optional_write_data...]
//
// SPI bitfield (1 byte, little-endian bit order, matches C++ `SpiBitFields`):
//   bit 0      lsb_first
//   bits 1–2   mode
//   bits 3–5   frequency
//   bit 6      use_nrf_pins
//   bit 7      reserved (0)

/**
 * Commands and read helpers for the MetaWear serial passthrough module.
 *
 * Supports two buses:
 * - **I2C** — up to 400 kHz; addressed by 7-bit device address + register address.
 * - **SPI** — configurable clock (125 kHz–8 MHz), mode (0–3), pin set, bit order.
 *
 * Use the `MetaWearDevice` convenience methods for reads; use the command
 * classes for writes:
 * ```kotlin
 * device.send(Serial.I2cWrite(deviceAddress = 0x68, registerAddress = 0x6B, data = byteArrayOf(0x00)))
 * val bytes = device.i2cRead(deviceAddress = 0x68, registerAddress = 0x75, length = 1, id = 0)
 * ```
 */
object Serial {

    // ---- I2C ----

    /**
     * Write bytes to an I2C peripheral.
     *
     * Command format matches C++ `mbl_mw_i2c_write`:
     *   `[0x0D, 0x01, dev_addr, reg_addr, 0xFF, length, data...]`
     *
     * The `0xFF` byte is a fixed signal-id placeholder the firmware ignores for
     * plain writes (it only matters when linking a read signal). Writes are
     * fire-and-forget — there is no `id` field.
     *
     * @throws MetaWearException.OperationFailed if [data] exceeds
     *   [MAX_PAYLOAD_LENGTH]. Bad input is surfaced as a recoverable error
     *   rather than a hard `require` crash in the host app.
     */
    class I2cWrite(
        /** 7-bit I2C device address. */
        val deviceAddress: Int,
        /** Register (sub-address) to write to. */
        val registerAddress: Int,
        /** Payload bytes to send after the register address. */
        val data: ByteArray,
    ) : Command {
        init {
            if (data.size > MAX_PAYLOAD_LENGTH) {
                throw MetaWearException.OperationFailed(
                    "I2C write payload is ${data.size} bytes; firmware maximum is $MAX_PAYLOAD_LENGTH",
                )
            }
        }

        override val commandData: ByteArray
            get() = Packet.command(
                Module.SERIAL, 0x01,
                byteArrayOf(
                    deviceAddress.toByte(), registerAddress.toByte(),
                    0xFF.toByte(), data.size.toByte(),
                ) + data,
            )

        companion object {
            /**
             * Firmware payload limit for a single I2C write. The register table
             * documents "Data Length (max 10)" — the on-wire length field is a
             * byte, but the firmware only buffers 10 data bytes per write.
             */
            const val MAX_PAYLOAD_LENGTH = 10
        }
    }

    // ---- SPI ----

    /**
     * Clock frequency for SPI transactions. [raw] is packed into 3 bits of the
     * SPI bitfield byte.
     */
    enum class SpiClock(val raw: Int) {
        F125_KHZ(0),
        F250_KHZ(1),
        F500_KHZ(2),
        F1_MHZ(3),
        F2_MHZ(4),
        F4_MHZ(5),
        F8_MHZ(6),
    }

    /** SPI mode (CPOL/CPHA). [raw] is packed into 2 bits of the SPI bitfield byte. */
    enum class SpiMode(val raw: Int) {
        /** CPOL=0, CPHA=0 — idle low, sample on rising edge. */
        MODE0(0),

        /** CPOL=0, CPHA=1 — idle low, sample on falling edge. */
        MODE1(1),

        /** CPOL=1, CPHA=0 — idle high, sample on falling edge. */
        MODE2(2),

        /** CPOL=1, CPHA=1 — idle high, sample on rising edge. */
        MODE3(3),
    }

    /**
     * Pin/mode/frequency parameter block matching C++ `MblMwSpiParameters`
     * (the subset that is actually sent on the wire).
     */
    class SpiParameters(
        val slaveSelectPin: Int,
        val clockPin: Int,
        val mosiPin: Int,
        val misoPin: Int,
        val mode: SpiMode,
        val frequency: SpiClock,
        /** When `true`, the least-significant bit is transmitted first. */
        val lsbFirst: Boolean = false,
        /** When `true`, use the nRF SPI pins instead of the board expansion header. */
        val useNrfPins: Boolean = false,
    ) {
        /**
         * Packed bitfield byte (matches C++ `SpiBitFields` memory layout):
         *   bit 0 lsb_first | bits 1-2 mode | bits 3-5 frequency | bit 6 use_nrf_pins | bit 7 pad(0)
         */
        val bitfield: Int
            get() {
                var b = 0
                if (lsbFirst) b = b or 0x01
                b = b or ((mode.raw and 0x03) shl 1)
                b = b or ((frequency.raw and 0x07) shl 3)
                if (useNrfPins) b = b or 0x40
                return b
            }

        /** The 5-byte pin/bitfield prefix: `[ss, clk, mosi, miso, bitfield]`. */
        val encodedBytes: ByteArray
            get() = byteArrayOf(
                slaveSelectPin.toByte(), clockPin.toByte(),
                mosiPin.toByte(), misoPin.toByte(), bitfield.toByte(),
            )
    }

    /**
     * Write bytes over SPI.
     *
     * Command format matches C++ `mbl_mw_spi_write`:
     *   `[0x0D, 0x02, ss, clk, mosi, miso, bitfield, data...]`
     */
    class SpiWrite(
        val parameters: SpiParameters,
        val data: ByteArray,
    ) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.SERIAL, 0x02, parameters.encodedBytes + data)
    }
}

// ---- MetaWearDevice serial convenience ----

/**
 * Read bytes from an I2C peripheral.
 *
 * Sends `[0x0D, 0xC1, dev_addr, reg_addr, id, length]` (matches C++
 * `MblMwI2cSignal::read` byte order). The board replies on register 0x01 with
 * `[0x0D, 0x01, id, byte0, byte1, ...]`; we strip the 3-byte prefix. The reply
 * is a plain notification — NOT a bit-7 read response — so this awaits via
 * [MetaWearDevice.sendAndAwaitNotification].
 *
 * @param deviceAddress 7-bit I2C address of the peripheral.
 * @param registerAddress Register (sub-address) to read from.
 * @param length Number of bytes to read (1–255).
 * @param id Caller-assigned identifier (echoed in the response).
 * @return The bytes returned by the peripheral.
 */
suspend fun MetaWearDevice.i2cRead(
    deviceAddress: Int,
    registerAddress: Int,
    length: Int,
    id: Int = 0,
): ByteArray {
    if (length !in 1..255) {
        throw MetaWearException.OperationFailed("I2C read length must be in 1...255")
    }
    // Request: [0x0D, 0xC1, dev, reg, id, length]
    // 0xC1 = 0x01 | 0x80 (read bit) | 0x40 (data_id bit)
    val cmd = Packet.command(Module.SERIAL, 0x01 or 0x80 or 0x40, deviceAddress, registerAddress, id, length)
    val packet = sendAndAwaitNotification(cmd, awaitModule = Module.SERIAL, awaitRegister = 0x01)
    if (packet.size < 3) {
        throw MetaWearException.OperationFailed("I2C read response too short (${packet.size} bytes)")
    }
    return packet.copyOfRange(3, packet.size) // strip [module, register, id]
}

/**
 * Read bytes from an SPI peripheral.
 *
 * Sends `[0x0D, 0xC2, ss, clk, mosi, miso, bitfield, (length-1)|(id<<4), writeData...]`.
 * The optional [writeData] bytes are transmitted before the read clocks data
 * back out. The reply is a plain notification on register 0x02 — NOT a bit-7
 * read response — so this awaits via [MetaWearDevice.sendAndAwaitNotification].
 *
 * @param parameters Pin/mode/frequency parameter block.
 * @param length Number of bytes to read (1–16 — fits in 4 bits).
 * @param id Caller-assigned identifier (0–15 — fits in 4 bits).
 * @param writeData Optional bytes to transmit before the read clocks data out.
 * @return The bytes returned by the peripheral.
 */
suspend fun MetaWearDevice.spiRead(
    parameters: Serial.SpiParameters,
    length: Int,
    id: Int = 0,
    writeData: ByteArray = ByteArray(0),
): ByteArray {
    if (length !in 1..16) {
        throw MetaWearException.OperationFailed("SPI read length must be in 1...16")
    }
    if (id !in 0..15) {
        throw MetaWearException.OperationFailed("SPI read id must be in 0...15")
    }
    // length and id share one byte: (length-1) in low nibble, id in high nibble.
    val packedLenId = ((length - 1) and 0x0F) or ((id and 0x0F) shl 4)
    // Request: [0x0D, 0xC2, fields(5), packedLenId, writeData...]
    val cmd = Packet.command(
        Module.SERIAL, 0x02 or 0x80 or 0x40,
        parameters.encodedBytes + byteArrayOf(packedLenId.toByte()) + writeData,
    )
    // Response: [0x0D, 0x02, id, byte0, byte1, ...]
    val packet = sendAndAwaitNotification(cmd, awaitModule = Module.SERIAL, awaitRegister = 0x02)
    if (packet.size < 3) {
        throw MetaWearException.OperationFailed("SPI read response too short (${packet.size} bytes)")
    }
    return packet.copyOfRange(3, packet.size)
}
