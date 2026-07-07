package com.mbientlab.metawear

import com.mbientlab.metawear.model.AnonymousSignal
import com.mbientlab.metawear.model.AnonymousSignalBuilder
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet

// Port of the anonymous-signal reconstruction surface of MetaWearDevice.swift
// (`createAnonymousDataSignals()` + `readSensorScales()`). Implemented as
// extension functions over the internal hooks in MetaWearDevice.kt, mirroring
// how DeviceLogging.kt hosts the logging surface.

/**
 * Reconstruct the full list of logger-backed signals from on-device state.
 *
 * Used when the host process has restarted (or never configured the loggers
 * itself) but the board still holds logger + data-processor metadata and
 * pending flash entries. Walks [queryActiveLoggers] and
 * [queryActiveProcessors], follows any processor chain back to its root
 * sensor, and produces a list of [AnonymousSignal] with canonical identifiers
 * and typed decode closures.
 *
 * Sensor scale factors are resolved at call time from the live board
 * (`[0x03, 0x83]` for accel, `[0x13, 0x83]` for gyro). Changing the range
 * on the board afterward invalidates the returned signals — call again to
 * refresh.
 *
 * @throws MetaWearException.Timeout if logger or processor enumeration can't
 *   be completed.
 */
suspend fun MetaWearDevice.createAnonymousDataSignals(): List<AnonymousSignal> {
    val loggers = queryActiveLoggers()
    val processors = queryActiveProcessors()
    val scales = readSensorScales()
    return AnonymousSignalBuilder.build(
        loggers = loggers, processors = processors, scales = scales,
    )
}

/**
 * Read live sensor range bytes and convert to LSB/unit scales.
 * Any individual read failure is treated as "sensor not present" → null scale.
 */
private suspend fun MetaWearDevice.readSensorScales(): AnonymousSignalBuilder.Scales {
    var accel: Float? = null
    var gyro: Float? = null

    // Accelerometer range — query 0x83 on accel module. Response:
    // [0x03, 0x83, odr, range_byte]. The range byte encoding differs between
    // BMI160 and BMI270 (and the 0x03 value collides — ±2g on BMI160 vs ±16g
    // on BMI270), so pass the implementation byte through so the scale lookup
    // can pick the right table.
    val accInfo = moduleInfo(Module.ACCELEROMETER)
    if (accInfo != null && accInfo.isPresent) {
        try {
            val resp = sendRead(
                command = Packet.read(Module.ACCELEROMETER, 0x03),
                awaitModule = Module.ACCELEROMETER,
                awaitRegister = 0x03,
            )
            if (resp.size >= 4) {
                accel = accelScaleFromRangeByte(
                    b = resp[3].toInt() and 0xFF,
                    implementation = accInfo.implementation,
                )
            }
        } catch (e: MetaWearException.Timeout) {
            // Leave as null
        }
    }

    // Gyro range — query 0x83 on gyro module. Response: [0x13, 0x83, odr, range_byte].
    if (moduleInfo(Module.GYRO)?.isPresent == true) {
        try {
            val resp = sendRead(
                command = Packet.read(Module.GYRO, 0x03),
                awaitModule = Module.GYRO,
                awaitRegister = 0x03,
            )
            if (resp.size >= 4) {
                gyro = gyroScaleFromRangeByte(resp[3].toInt() and 0xFF)
            }
        } catch (e: MetaWearException.Timeout) {
            // Leave as null
        }
    }

    return AnonymousSignalBuilder.Scales(accel = accel, gyro = gyro, mag = 16.0f)
}

/**
 * Map the live accelerometer range byte to LSB/g.
 *
 * BMI160 and BMI270 encode the range byte differently and both encodings
 * share the value `0x03` (±2g on BMI160, ±16g on BMI270). The chip is
 * identified by [implementation], drawn from the accelerometer module's
 * discovery info — `1` = BMI160, `4` = BMI270.
 */
private fun accelScaleFromRangeByte(b: Int, implementation: Int): Float = when (implementation) {
    // BMI270 — range byte is 0-based: 0x00=±2g, 0x01=±4g, 0x02=±8g, 0x03=±16g
    4 -> when (b) {
        0x00 -> 16384f // ±2g
        0x01 -> 8192f  // ±4g
        0x02 -> 4096f  // ±8g
        0x03 -> 2048f  // ±16g
        else -> 16384f
    }
    // BMI160 (impl 1) and anything unknown — fall through to BMI160 table
    else -> when (b) {
        0x03 -> 16384f // ±2g
        0x05 -> 8192f  // ±4g
        0x08 -> 4096f  // ±8g
        0x0C -> 2048f  // ±16g
        else -> 16384f
    }
}

/** Map the BMI160/BMI270 gyroscope range byte (0..4) to LSB/dps. */
private fun gyroScaleFromRangeByte(b: Int): Float = when (b) {
    0x00 -> 16.4f   // ±2000 dps
    0x01 -> 32.8f   // ±1000 dps
    0x02 -> 65.6f   // ±500 dps
    0x03 -> 131.2f  // ±250 dps
    0x04 -> 262.4f  // ±125 dps
    else -> 16.4f
}
