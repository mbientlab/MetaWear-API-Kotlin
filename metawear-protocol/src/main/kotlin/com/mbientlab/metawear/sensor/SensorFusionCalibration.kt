package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.Pollable

// Port of the calibration types and commands from MWSensorFusion.swift
// (MWSensorFusionCalibration / MWSensorFusionCalibrationData /
// MWSensorFusionWrite*Calibration / MWSensorFusionCalibrationState).

// ---- Calibration state ----

/**
 * Per-sensor calibration accuracy reported by the fusion algorithm.
 *
 * Each value ranges from 0 (uncalibrated / unreliable) to 3 (fully calibrated /
 * high accuracy). Poll until each is at least 2 before recording orientation
 * data to ensure usable accuracy. Returned by [SensorFusionCalibrationState].
 */
data class SensorFusionCalibration(
    /** Accelerometer calibration accuracy: 0 (UNRELIABLE) … 3 (HIGH). */
    val accelerometer: Int,
    /** Gyroscope calibration accuracy: 0 (UNRELIABLE) … 3 (HIGH). */
    val gyroscope: Int,
    /** Magnetometer calibration accuracy: 0 (UNRELIABLE) … 3 (HIGH). */
    val magnetometer: Int,
)

// ---- Calibration data ----
//
// Mirrors C++ `MblMwCalibrationData`: 10 bytes each for acc / gyro / mag.
// Only usable on firmware v1.4.3+ / sensor fusion revision >= 2.

/** Throws with the Swift-parity message unless [data] is exactly 10 bytes. */
private fun requireCalibrationBlob(data: ByteArray, sensor: String) {
    if (data.size != 10) {
        throw MetaWearException.OperationFailed(
            "$sensor calibration data must be 10 bytes; got ${data.size}",
        )
    }
}

/**
 * Persisted calibration blobs that can be loaded back into the board, skipping
 * the manual figure-8 / orientation dance on next boot.
 *
 * Each blob is exactly 10 bytes (opaque to the host — Bosch-defined layout).
 * Read with the corresponding read signal once calibration accuracy reaches
 * HIGH; restore via [SensorFusionWriteAccCalibration] /
 * [SensorFusionWriteGyroCalibration] / [SensorFusionWriteMagCalibration].
 * Requires sensor fusion revision ≥ 2 (firmware v1.4.3+).
 *
 * @throws MetaWearException.OperationFailed if any blob is not exactly 10 bytes.
 */
class SensorFusionCalibrationData(
    /** 10-byte accelerometer calibration blob. */
    val acc: ByteArray,
    /** 10-byte gyroscope calibration blob. */
    val gyro: ByteArray,
    /** 10-byte magnetometer calibration blob. */
    val mag: ByteArray,
) {
    init {
        requireCalibrationBlob(acc, "acc")
        requireCalibrationBlob(gyro, "gyro")
        requireCalibrationBlob(mag, "mag")
    }

    override fun equals(other: Any?): Boolean = other is SensorFusionCalibrationData &&
        acc.contentEquals(other.acc) && gyro.contentEquals(other.gyro) && mag.contentEquals(other.mag)

    override fun hashCode(): Int {
        var result = acc.contentHashCode()
        result = 31 * result + gyro.contentHashCode()
        result = 31 * result + mag.contentHashCode()
        return result
    }
}

// ---- Write-calibration commands ----

/**
 * Write accelerometer calibration data (10 bytes). Register 0x0C. Requires
 * sensor fusion revision >= 2 (CALIB_DATA_REVISION) and firmware v1.4.3+.
 *
 * @throws MetaWearException.OperationFailed if [data] is not exactly 10 bytes.
 */
class SensorFusionWriteAccCalibration(val data: ByteArray) : Command {
    init {
        requireCalibrationBlob(data, "acc")
    }

    override val commandData: ByteArray
        get() = Packet.command(Module.SENSOR_FUSION, 0x0C, data)
}

/**
 * Write gyroscope calibration data (10 bytes). Register 0x0D.
 *
 * @throws MetaWearException.OperationFailed if [data] is not exactly 10 bytes.
 */
class SensorFusionWriteGyroCalibration(val data: ByteArray) : Command {
    init {
        requireCalibrationBlob(data, "gyro")
    }

    override val commandData: ByteArray
        get() = Packet.command(Module.SENSOR_FUSION, 0x0D, data)
}

/**
 * Write magnetometer calibration data (10 bytes). Register 0x0E.
 *
 * @throws MetaWearException.OperationFailed if [data] is not exactly 10 bytes.
 */
class SensorFusionWriteMagCalibration(val data: ByteArray) : Command {
    init {
        requireCalibrationBlob(data, "mag")
    }

    override val commandData: ByteArray
        get() = Packet.command(Module.SENSOR_FUSION, 0x0E, data)
}

// ---- Calibration state read signal ----
//
// Mirrors C++ `mbl_mw_sensor_fusion_calibration_state_data_signal`.
// Available on sensor fusion revision >= 1 (CALIBRATION_REVISION).
// Issue the read via `Packet.read(SENSOR_FUSION, 0x0B)` → `[0x19, 0x8B]`.
// The board responds with `[0x19, 0x8B, acc, gyro, mag]` (3 accuracy bytes, 0–3 each).

/**
 * Read signal for the current fusion calibration accuracy ([SensorFusionCalibration]).
 *
 * One-shot read — issue with `read(...)` on `MetaWearDevice`, or poll on an
 * interval with `poll(...)`. Returns the per-sensor accuracy (0 = unreliable,
 * 3 = high). Poll periodically while guiding the user through calibration
 * motion; persist [SensorFusionCalibrationData] once all three reach HIGH so
 * future sessions can skip the calibration dance.
 *
 * Available on sensor fusion revision ≥ 1 (CALIBRATION_REVISION).
 */
class SensorFusionCalibrationState : Pollable<SensorFusionCalibration> {

    override val module: Module = Module.SENSOR_FUSION
    override val dataRegister: Int = 0x0B

    override val readCommand: ByteArray
        get() = Packet.read(Module.SENSOR_FUSION, 0x0B)

    override fun parseSample(packet: ByteArray): SensorFusionCalibration {
        if (packet.size < 5) {
            throw MetaWearException.OperationFailed(
                "Calibration-state packet too short: ${packet.size} bytes",
            )
        }
        return SensorFusionCalibration(
            accelerometer = packet[2].toInt() and 0xFF,
            gyroscope = packet[3].toInt() and 0xFF,
            magnetometer = packet[4].toInt() and 0xFF,
        )
    }
}
