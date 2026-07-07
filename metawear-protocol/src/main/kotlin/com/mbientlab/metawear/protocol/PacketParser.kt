package com.mbientlab.metawear.protocol

import com.mbientlab.metawear.model.BatteryState
import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.model.CorrectedCartesianFloat
import com.mbientlab.metawear.model.EulerAngles
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.model.Quaternion

/**
 * Static helpers for parsing raw MetaWear notification bytes into typed values.
 * Direct port of `MWPacketParser` (Swift). All multi-byte values in the MetaWear
 * protocol are little-endian.
 *
 * Bytes are interpreted unsigned via `byte.toInt() and 0xFF`. Return types:
 * signed 16-/32-bit values come back as `Int`, unsigned 32-bit as `Long`.
 */
object PacketParser {

    // ---- Primitives ----

    /** Signed 16-bit little-endian value at [offset]. */
    fun parseInt16LE(data: ByteArray, offset: Int): Int {
        val lo = data[offset].toInt() and 0xFF
        val hi = data[offset + 1].toInt() and 0xFF
        return (lo or (hi shl 8)).toShort().toInt()
    }

    /** Unsigned 16-bit little-endian value (0..65535) at [offset]. */
    fun parseUInt16LE(data: ByteArray, offset: Int): Int {
        val lo = data[offset].toInt() and 0xFF
        val hi = data[offset + 1].toInt() and 0xFF
        return lo or (hi shl 8)
    }

    /** Unsigned 32-bit little-endian value (0..4294967295) at [offset]. */
    fun parseUInt32LE(data: ByteArray, offset: Int): Long {
        val b0 = (data[offset].toInt() and 0xFF).toLong()
        val b1 = (data[offset + 1].toInt() and 0xFF).toLong()
        val b2 = (data[offset + 2].toInt() and 0xFF).toLong()
        val b3 = (data[offset + 3].toInt() and 0xFF).toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    /** Signed 32-bit little-endian value at [offset]. */
    fun parseInt32LE(data: ByteArray, offset: Int): Int = parseUInt32LE(data, offset).toInt()

    /** IEEE-754 single-precision float, little-endian, at [offset]. */
    fun parseFloat32LE(data: ByteArray, offset: Int): Float =
        Float.fromBits(parseUInt32LE(data, offset).toInt())

    // ---- CartesianFloat from scaled int16 (accelerometer, gyroscope, magnetometer) ----
    // Packet layout: [module, register, x_lo, x_hi, y_lo, y_hi, z_lo, z_hi]

    fun parseCartesianFloat(packet: ByteArray, scale: Float): CartesianFloat {
        require8(packet, 8, "CartesianFloat")
        return CartesianFloat(
            x = parseInt16LE(packet, 2).toFloat() / scale,
            y = parseInt16LE(packet, 4).toFloat() / scale,
            z = parseInt16LE(packet, 6).toFloat() / scale,
        )
    }

    /** Unpack 3 consecutive XYZ samples from a packed-data notification. */
    fun parsePackedCartesianFloat(packet: ByteArray, scale: Float): List<CartesianFloat> {
        if (packet.size < 20) fail("Packed packet too short: ${packet.size} bytes")
        return (0 until 3).map { i ->
            val offset = 2 + i * 6
            CartesianFloat(
                x = parseInt16LE(packet, offset).toFloat() / scale,
                y = parseInt16LE(packet, offset + 2).toFloat() / scale,
                z = parseInt16LE(packet, offset + 4).toFloat() / scale,
            )
        }
    }

    // ---- CartesianFloat from float32 (sensor fusion corrected outputs) ----
    // Packet layout: [module, register, x_f32, y_f32, z_f32, accuracy]

    fun parseCorrectedCartesianFloat(packet: ByteArray, scale: Float = 1.0f): CorrectedCartesianFloat {
        if (packet.size < 15) fail("Packet too short for CorrectedCartesianFloat: ${packet.size} bytes")
        return CorrectedCartesianFloat(
            x = parseFloat32LE(packet, 2) / scale,
            y = parseFloat32LE(packet, 6) / scale,
            z = parseFloat32LE(packet, 10) / scale,
            accuracy = packet[14].toInt() and 0xFF,
        )
    }

    // ---- Quaternion (sensor fusion) ----
    // Packet layout: [module, register, w_f32, x_f32, y_f32, z_f32]

    fun parseQuaternion(packet: ByteArray): Quaternion {
        if (packet.size < 18) fail("Packet too short for Quaternion: ${packet.size} bytes")
        return Quaternion(
            w = parseFloat32LE(packet, 2),
            x = parseFloat32LE(packet, 6),
            y = parseFloat32LE(packet, 10),
            z = parseFloat32LE(packet, 14),
        )
    }

    // ---- Euler angles (sensor fusion) ----
    // Packet layout: [module, register, heading_f32, pitch_f32, roll_f32, yaw_f32]

    fun parseEulerAngles(packet: ByteArray): EulerAngles {
        if (packet.size < 18) fail("Packet too short for EulerAngles: ${packet.size} bytes")
        return EulerAngles(
            heading = parseFloat32LE(packet, 2),
            pitch = parseFloat32LE(packet, 6),
            roll = parseFloat32LE(packet, 10),
            yaw = parseFloat32LE(packet, 14),
        )
    }

    // ---- Gravity / linear acceleration (sensor fusion) — convert m/s² → g ----

    fun parseGravityVector(packet: ByteArray): CartesianFloat {
        if (packet.size < 14) fail("Packet too short for gravity vector: ${packet.size} bytes")
        val mssToG = 9.80665f
        return CartesianFloat(
            x = parseFloat32LE(packet, 2) / mssToG,
            y = parseFloat32LE(packet, 6) / mssToG,
            z = parseFloat32LE(packet, 10) / mssToG,
        )
    }

    // ---- Pressure / altitude (barometer) ----

    fun parsePressure(packet: ByteArray): Float {
        if (packet.size < 6) fail("Packet too short for pressure: ${packet.size} bytes")
        return parseUInt32LE(packet, 2).toFloat() / 256.0f
    }

    fun parseAltitude(packet: ByteArray): Float {
        if (packet.size < 6) fail("Packet too short for altitude: ${packet.size} bytes")
        return parseInt32LE(packet, 2).toFloat() / 256.0f
    }

    // ---- Temperature ----
    // Multi-channel response: [module=0x04, register=0x81, channel, lo, hi].
    // Signed 16-bit Celsius / 8 at offset 3; the channel byte at offset 2 is ignored here.

    fun parseTemperature(packet: ByteArray): Float {
        if (packet.size < 5) fail("Packet too short for temperature: ${packet.size} bytes")
        return parseInt16LE(packet, 3).toFloat() / 8.0f
    }

    // ---- Ambient light (LTR329) — raw is milli-lux (lux × 1000) ----

    fun parseIlluminanceRaw(packet: ByteArray): Long {
        if (packet.size < 6) fail("Packet too short for illuminance: ${packet.size} bytes")
        return parseUInt32LE(packet, 2)
    }

    fun parseIlluminance(packet: ByteArray): Float = parseIlluminanceRaw(packet).toFloat() / 1000.0f

    // ---- Humidity (BME280) — raw UInt32 / 1024 = relative humidity (%) ----

    fun parseHumidity(packet: ByteArray): Float {
        if (packet.size < 6) fail("Packet too short for humidity: ${packet.size} bytes")
        return parseUInt32LE(packet, 2).toFloat() / 1024.0f
    }

    // ---- MAC address (settings) ----
    // Payload is 6 bytes (older firmware) or 7 with a leading address-type byte.
    // The 6 MAC bytes are little-endian; the canonical form reverses them.

    fun parseMacAddress(packet: ByteArray): String {
        if (packet.size < 8) fail("Packet too short for MAC address: ${packet.size} bytes")
        // Skip the address-type byte when present (payload of 7+ bytes).
        val macOffset = if (packet.size >= 9) 3 else 2
        val macBytes = (0 until 6).map { packet[macOffset + it].toInt() and 0xFF }.reversed()
        return macBytes.joinToString(":") { "%02X".format(it) }
    }

    // ---- Battery ----

    fun parseBatteryState(packet: ByteArray): BatteryState {
        if (packet.size < 5) fail("Packet too short for battery: ${packet.size} bytes")
        val charge = packet[2].toInt() and 0xFF
        val voltage = parseUInt16LE(packet, 3)
        return BatteryState(voltage = voltage, charge = charge)
    }

    // ---- Little-endian encoding ----

    fun le32(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    // ---- Log entry (raw download) ----
    // 9-byte entry: [id_resetUID, tick(4 LE), data(4 LE)]. The firmware bundles
    // 1 or 2 entries per BLE notification (after a 2-byte header).

    /** Milliseconds per device tick (≈ 1.4648 ms). */
    val MS_PER_TICK: Double = (48.0 / 32768.0) * 1000.0

    /** One decoded raw log entry. */
    data class LogEntry(val id: Int, val resetUID: Int, val tick: Long, val rawData: Long)

    fun parseLogEntry(packet: ByteArray): LogEntry {
        if (packet.size < 9) fail("Log entry too short: ${packet.size} bytes")
        val idByte = packet[0].toInt() and 0xFF
        val id = idByte and 0x1F
        val resetUID = (idByte shr 5) and 0x07
        val tick = parseUInt32LE(packet, 1)
        val rawData = parseUInt32LE(packet, 5)
        return LogEntry(id, resetUID, tick, rawData)
    }

    // ---- helpers ----

    private fun require8(packet: ByteArray, min: Int, what: String) {
        if (packet.size < min) fail("Packet too short for $what: ${packet.size} bytes")
    }

    private fun fail(message: String): Nothing = throw MetaWearException.OperationFailed(message)
}
