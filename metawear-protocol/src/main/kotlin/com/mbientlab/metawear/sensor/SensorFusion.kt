package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.model.CorrectedCartesianFloat
import com.mbientlab.metawear.model.EulerAngles
import com.mbientlab.metawear.model.Quaternion
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.LogChunk
import com.mbientlab.metawear.protocol.Loggable
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser

// Port of MWSensorFusion.swift — the on-board Bosch sensor-fusion module
// (module 0x19). Calibration types and commands live in
// SensorFusionCalibration.kt.

// ---- Sensor fusion mode ----

/**
 * Selects which underlying sensors the on-board fusion algorithm draws from,
 * and therefore which fused outputs are available and how power-hungry the
 * pipeline is.
 *
 * Different modes are appropriate for different jobs: absolute orientation
 * against magnetic north ([NDOF]), gyro-stable relative orientation
 * ([IMU_PLUS]), geographic heading only ([COMPASS]), or a low-power
 * orientation estimate without the gyro ([M4G]).
 */
enum class SensorFusionMode(val raw: Int) {
    /** Fusion disabled — underlying sensors are not driven. */
    SLEEP(0),

    /**
     * Nine-degrees-of-freedom: accelerometer + gyroscope + magnetometer.
     * Best absolute-orientation quality (true heading), highest power.
     */
    NDOF(1),

    /**
     * Accelerometer + gyroscope only — relative orientation, no magnetic heading.
     * Excellent short-term stability without the magnetic disturbances of NDOF.
     */
    IMU_PLUS(2),

    /** Magnetometer only — produces a compass heading. Lowest power orientation mode. */
    COMPASS(3),

    /**
     * Magnetometer + accelerometer (no gyro). Low-power relative orientation
     * for slow motion; gyro-free so drift-immune at the cost of fast dynamics.
     */
    M4G(4),
}

// ---- Sensor fusion accelerometer range ----
// Raw values match C++ `MblMwSensorFusionAccRange`.

/**
 * Full-scale range the accelerometer is driven at while feeding sensor fusion.
 *
 * Smaller ranges give better resolution for low-motion use; larger ranges
 * avoid clipping on impacts. Defaults to [G2] in the fusion constructors.
 */
enum class SensorFusionAccRange(val raw: Int) {
    /** ±2 g — finest resolution, easiest to clip. */
    G2(0),

    /** ±4 g. */
    G4(1),

    /** ±8 g. */
    G8(2),

    /** ±16 g — coarsest resolution, headroom for impacts. */
    G16(3),
}

// ---- Sensor fusion gyro range ----
// Raw values match C++ `MblMwSensorFusionGyroRange`.

/**
 * Full-scale range the gyroscope is driven at while feeding sensor fusion.
 *
 * Wider ranges ([DPS2000]) handle rapid rotation without clipping; narrower
 * ranges give finer resolution for slow motion.
 */
enum class SensorFusionGyroRange(val raw: Int) {
    /** ±2000 dps — widest, lowest resolution. */
    DPS2000(0),

    /** ±1000 dps. */
    DPS1000(1),

    /** ±500 dps. */
    DPS500(2),

    /** ±250 dps — narrowest, highest resolution. */
    DPS250(3),
}

// ---- Underlying chip family ----
//
// The sensor fusion algorithm is fed by the accelerometer + gyroscope
// (+ magnetometer) modules on the same board. Acc/gyro chip impl bytes differ
// between MetaMotion variants — BMI160 (older R/RL) and BMI270 (newer C/S)
// write slightly different config bytes. All other lifecycle commands are
// chip-agnostic.

/**
 * Underlying Bosch IMU on the MetaMotion board that drives sensor fusion.
 *
 * The fusion algorithm itself is chip-agnostic but the accelerometer config
 * bytes differ slightly between BMI160 (R / RL) and BMI270 (C / S). Use
 * [fromGyroImpl] or [fromAccImpl] to derive this from the module
 * implementation bytes reported during board discovery.
 */
enum class SensorFusionChip {
    /** Older Bosch BMI160 — found on MetaMotion R / RL boards. */
    BMI160,

    /** Newer Bosch BMI270 — found on MetaMotion C / S boards. */
    BMI270;

    companion object {
        /**
         * Resolve from the gyro module's `implementation` byte: 0 = BMI160,
         * 1 = BMI270. (Mirrors `MBL_MW_MODULE_GYRO_TYPE_BMI160 / BMI270`.)
         */
        fun fromGyroImpl(gyroImpl: Int): SensorFusionChip? = when (gyroImpl) {
            0 -> BMI160
            1 -> BMI270
            else -> null
        }

        /** Resolve from the accelerometer module's `implementation` byte: 1 = BMI160, 4 = BMI270. */
        fun fromAccImpl(accImpl: Int): SensorFusionChip? = when (accImpl) {
            1 -> BMI160
            4 -> BMI270
            else -> null
        }
    }
}

// ---- Sensor fusion output types ----

/**
 * Type-erased wrapper for any fused output value.
 *
 * Useful when handling fusion results through a single channel; the subtype
 * determines how the payload was scaled by the firmware.
 */
sealed class SensorFusionOutput {
    /** 4-axis (w, x, y, z) orientation quaternion. */
    data class QuaternionOutput(val value: Quaternion) : SensorFusionOutput()

    /** Heading / pitch / roll / yaw in degrees. */
    data class EulerAnglesOutput(val value: EulerAngles) : SensorFusionOutput()

    /** Bias-corrected accelerometer reading (g), with per-sample accuracy byte. */
    data class CorrectedAcceleration(val value: CorrectedCartesianFloat) : SensorFusionOutput()

    /** Bias-corrected gyroscope reading (dps), with per-sample accuracy byte. */
    data class CorrectedRotation(val value: CorrectedCartesianFloat) : SensorFusionOutput()

    /** Bias-corrected magnetometer reading (µT), with per-sample accuracy byte. */
    data class CorrectedMagneticField(val value: CorrectedCartesianFloat) : SensorFusionOutput()

    /** Gravity component of the current acceleration (g). */
    data class GravityVector(val value: CartesianFloat) : SensorFusionOutput()

    /** Acceleration with gravity removed (g) — pure motion impulse. */
    data class LinearAcceleration(val value: CartesianFloat) : SensorFusionOutput()
}

// ---- Config / lifecycle helpers ----
//
// These mirror `mbl_mw_sensor_fusion_write_config`, `mbl_mw_sensor_fusion_start`,
// and `mbl_mw_sensor_fusion_stop` in `sensor_fusion.cpp`. The byte sequences are
// asserted by `MetaWear-SDK-Cpp/test/test_sensor_fusion.py::test_sensor_control`
// and `test_sensor_fusion_config.py`.

/**
 * Fusion config command — `[0x19, 0x02, mode, rangeByte]`.
 * `rangeByte = accRange | ((gyroRange + 1) << 4)` matches the C++ bitfield layout.
 */
private fun fusionConfigCommand(mode: SensorFusionMode, accRange: Int, gyroRange: Int): ByteArray {
    val rangeByte = accRange or ((gyroRange + 1) shl 4)
    return Packet.command(Module.SENSOR_FUSION, 0x02, mode.raw, rangeByte)
}

/**
 * Underlying acc config — `[0x03, 0x03, confByte, rangeByte]`.
 * ODR depends on mode (NDOF/IMU+ → 100 Hz, COMPASS → 25 Hz, M4G → 50 Hz);
 * the upper bits of `confByte` (bwp/perf) and the range byte encoding differ
 * between BMI160 and BMI270.
 */
private fun fusionAccConfigCommand(mode: SensorFusionMode, accRange: Int, chip: SensorFusionChip): ByteArray {
    // BMI160/BMI270 ODR enum values from datasheet (1-based as written to register):
    //   25 Hz → 6, 50 Hz → 7, 100 Hz → 8.
    val odrCode = when (mode) {
        SensorFusionMode.NDOF, SensorFusionMode.IMU_PLUS -> 8   // 100 Hz
        SensorFusionMode.COMPASS -> 6                           // 25 Hz
        SensorFusionMode.M4G -> 7                               // 50 Hz
        SensorFusionMode.SLEEP -> 8                             // unused (start is gated on non-sleep)
    }
    val bwp = 2                                                 // normal averaging
    return when (chip) {
        SensorFusionChip.BMI160 -> {
            // BMI160 acc_conf: bits[3:0]=odr, bits[6:4]=bwp, bit[7]=us (0 for ODR >= 12.5 Hz)
            val confByte = (bwp shl 4) or odrCode                        // → 0x28 / 0x26 / 0x27
            val rangeByte = intArrayOf(0x03, 0x05, 0x08, 0x0C)[accRange] // BMI160 range bitmask
            Packet.command(Module.ACCELEROMETER, 0x03, confByte, rangeByte)
        }
        SensorFusionChip.BMI270 -> {
            // BMI270 acc_conf: bits[3:0]=odr, bits[6:4]=bwp, bit[7]=filter_perf (1 for ODR >= 12.5 Hz)
            val confByte = 0x80 or (bwp shl 4) or odrCode                // → 0xA8 / 0xA6 / 0xA7
            Packet.command(Module.ACCELEROMETER, 0x03, confByte, accRange)
        }
    }
}

/**
 * Underlying gyro config — `[0x13, 0x03, 0x28, gyroRange]`.
 * Always 100 Hz (gyro only runs in NDOF / IMU_PLUS). Both BMI160 and BMI270 use
 * the same register encoding, so the chip does not matter here.
 */
private fun fusionGyroConfigCommand(gyroRange: Int): ByteArray {
    val bwp = 2
    val odrCode = 8                              // 100 Hz
    val confByte = (bwp shl 4) or odrCode        // → 0x28
    return Packet.command(Module.GYRO, 0x03, confByte, gyroRange)
}

/**
 * Underlying mag config — two commands. Always xy_reps=9, z_reps=15, ODR=25 Hz
 * (matches `mbl_mw_mag_bmm150_configure(board, 9, 15, MBL_MW_MAG_BMM150_ODR_25Hz)`).
 */
private fun fusionMagConfigCommands(): List<ByteArray> =
    // xyByte = (9 - 1) / 2 = 4, zByte = 15 - 1 = 14 = 0x0E, ODR_25Hz raw = 6
    listOf(
        Packet.command(Module.MAGNETOMETER, 0x04, 0x04, 0x0E),
        Packet.command(Module.MAGNETOMETER, 0x03, 0x06),
    )

/**
 * All `configureCommands` for a fusion signal: fusion config followed by the
 * per-mode underlying acc / gyro / mag configs.
 */
private fun fusionConfigureCommands(
    mode: SensorFusionMode,
    accRange: Int,
    gyroRange: Int,
    chip: SensorFusionChip,
): List<ByteArray> {
    val cmds = mutableListOf(fusionConfigCommand(mode, accRange, gyroRange))
    when (mode) {
        SensorFusionMode.SLEEP -> Unit
        SensorFusionMode.NDOF -> {
            cmds.add(fusionAccConfigCommand(mode, accRange, chip))
            cmds.add(fusionGyroConfigCommand(gyroRange))
            cmds.addAll(fusionMagConfigCommands())
        }
        SensorFusionMode.IMU_PLUS -> {
            cmds.add(fusionAccConfigCommand(mode, accRange, chip))
            cmds.add(fusionGyroConfigCommand(gyroRange))
        }
        SensorFusionMode.COMPASS, SensorFusionMode.M4G -> {
            cmds.add(fusionAccConfigCommand(mode, accRange, chip))
            cmds.addAll(fusionMagConfigCommands())
        }
    }
    return cmds
}

// ---- Underlying lifecycle commands (chip-agnostic) ----
// Rebuilt per access so callers can never mutate a shared ByteArray instance.

private val accEnableSampling: ByteArray get() = Packet.command(Module.ACCELEROMETER, 0x02, 0x01, 0x00)
private val accStartSampling: ByteArray get() = Packet.command(Module.ACCELEROMETER, 0x01, 0x01)
private val accStopSampling: ByteArray get() = Packet.command(Module.ACCELEROMETER, 0x01, 0x00)
private val accDisableSampling: ByteArray get() = Packet.command(Module.ACCELEROMETER, 0x02, 0x00, 0x01)

private val gyroEnableSampling: ByteArray get() = Packet.command(Module.GYRO, 0x02, 0x01, 0x00)
private val gyroStartSampling: ByteArray get() = Packet.command(Module.GYRO, 0x01, 0x01)
private val gyroStopSampling: ByteArray get() = Packet.command(Module.GYRO, 0x01, 0x00)
private val gyroDisableSampling: ByteArray get() = Packet.command(Module.GYRO, 0x02, 0x00, 0x01)

private val magEnableSampling: ByteArray get() = Packet.command(Module.MAGNETOMETER, 0x02, 0x01, 0x00)
private val magStartSampling: ByteArray get() = Packet.command(Module.MAGNETOMETER, 0x01, 0x01)
private val magStopSampling: ByteArray get() = Packet.command(Module.MAGNETOMETER, 0x01, 0x00)
private val magDisableSampling: ByteArray get() = Packet.command(Module.MAGNETOMETER, 0x02, 0x00, 0x01)

private val fusionStartFusion: ByteArray get() = Packet.command(Module.SENSOR_FUSION, 0x01, 0x01)
private val fusionStopFusion: ByteArray get() = Packet.command(Module.SENSOR_FUSION, 0x01, 0x00)
private val fusionClearMask: ByteArray get() = Packet.command(Module.SENSOR_FUSION, 0x03, 0x00, 0x7F)

/**
 * Underlying-sensor enable_sampling commands — issued before the underlying
 * sensors are started. Order: acc, gyro (NDOF/IMU+ only), mag (NDOF/COMPASS/M4G only).
 */
private fun fusionEnableSamplingCommands(mode: SensorFusionMode): List<ByteArray> = when (mode) {
    SensorFusionMode.SLEEP -> emptyList()
    SensorFusionMode.NDOF -> listOf(accEnableSampling, gyroEnableSampling, magEnableSampling)
    SensorFusionMode.IMU_PLUS -> listOf(accEnableSampling, gyroEnableSampling)
    SensorFusionMode.COMPASS -> listOf(accEnableSampling, magEnableSampling)
    SensorFusionMode.M4G -> listOf(accEnableSampling, magEnableSampling)
}

/** Underlying-sensor start commands. Order: acc, gyro (NDOF/IMU+), mag (NDOF/COMPASS/M4G). */
private fun fusionStartSamplingCommands(mode: SensorFusionMode): List<ByteArray> = when (mode) {
    SensorFusionMode.SLEEP -> emptyList()
    SensorFusionMode.NDOF -> listOf(accStartSampling, gyroStartSampling, magStartSampling)
    SensorFusionMode.IMU_PLUS -> listOf(accStartSampling, gyroStartSampling)
    SensorFusionMode.COMPASS -> listOf(accStartSampling, magStartSampling)
    SensorFusionMode.M4G -> listOf(accStartSampling, magStartSampling)
}

/** Underlying-sensor stop commands. Order: acc, gyro (NDOF/IMU+), mag (NDOF/COMPASS/M4G). */
private fun fusionStopSamplingCommands(mode: SensorFusionMode): List<ByteArray> = when (mode) {
    SensorFusionMode.SLEEP -> emptyList()
    SensorFusionMode.NDOF -> listOf(accStopSampling, gyroStopSampling, magStopSampling)
    SensorFusionMode.IMU_PLUS -> listOf(accStopSampling, gyroStopSampling)
    SensorFusionMode.COMPASS -> listOf(accStopSampling, magStopSampling)
    SensorFusionMode.M4G -> listOf(accStopSampling, magStopSampling)
}

/** Underlying-sensor disable_sampling commands. Order: acc, gyro (NDOF/IMU+), mag (NDOF/COMPASS/M4G). */
private fun fusionDisableSamplingCommands(mode: SensorFusionMode): List<ByteArray> = when (mode) {
    SensorFusionMode.SLEEP -> emptyList()
    SensorFusionMode.NDOF -> listOf(accDisableSampling, gyroDisableSampling, magDisableSampling)
    SensorFusionMode.IMU_PLUS -> listOf(accDisableSampling, gyroDisableSampling)
    SensorFusionMode.COMPASS -> listOf(accDisableSampling, magDisableSampling)
    SensorFusionMode.M4G -> listOf(accDisableSampling, magDisableSampling)
}

/**
 * Full per-signal `startCommands` — underlying sensor starts, then the fusion
 * output-enable mask write, then the fusion start byte.
 * Mirrors `mbl_mw_sensor_fusion_start`.
 */
private fun fusionStartCommands(mode: SensorFusionMode, fusionEnableMaskCmd: ByteArray): List<ByteArray> =
    fusionStartSamplingCommands(mode) + listOf(fusionEnableMaskCmd, fusionStartFusion)

/**
 * Full per-signal `stopCommands` — fusion stop, fusion clear-mask, then
 * underlying sensor stops. Mirrors `mbl_mw_sensor_fusion_stop`.
 */
private fun fusionStopCommands(mode: SensorFusionMode): List<ByteArray> =
    listOf(fusionStopFusion, fusionClearMask) + fusionStopSamplingCommands(mode)

// ---- Sensor fusion signal definitions ----
// Each signal is a separate Streamable — subscribe to one or more simultaneously.
// Output-enable bits (register 0x03) in MblMwSensorFusionData order:
//   0 = CORRECTED_ACC, 1 = CORRECTED_GYRO, 2 = CORRECTED_MAG,
//   3 = QUATERNION, 4 = EULER_ANGLES, 5 = GRAVITY, 6 = LINEAR_ACC

/**
 * Shared base for the seven sensor-fusion output signals. Port of the common
 * body of the `MWSensorFusion*` structs (Swift).
 *
 * All fusion outputs share one on-board engine, so they share the same
 * mode/range config and the same underlying acc/gyro/mag lifecycle commands;
 * each concrete signal contributes only its data register, output-enable bit,
 * parser, and log layout.
 *
 * The single-command forms keep their fusion-only meaning (enable/disable one
 * output bit, start/stop the fusion engine); the multi-command forms drive the
 * underlying chips per [mode] and are what a full start/stop must flush.
 */
abstract class SensorFusionSignal<S>(
    val mode: SensorFusionMode,
    val accRange: SensorFusionAccRange,
    val gyroRange: SensorFusionGyroRange,
    val chip: SensorFusionChip,
) : Loggable<S> {

    /** Output-enable bit for register 0x03, in `MblMwSensorFusionData` order. */
    protected abstract val enableBit: Int

    final override val module: Module = Module.SENSOR_FUSION
    final override val packedDataRegister: Int? = null

    final override val configureCommands: List<ByteArray>
        get() = fusionConfigureCommands(mode, accRange.raw, gyroRange.raw, chip)

    final override val enableCommand: ByteArray
        get() = Packet.command(Module.SENSOR_FUSION, 0x03, enableBit, 0x00)
    final override val startCommand: ByteArray get() = fusionStartFusion
    final override val stopCommand: ByteArray get() = fusionStopFusion
    final override val disableCommand: ByteArray
        get() = Packet.command(Module.SENSOR_FUSION, 0x03, 0x00, enableBit)

    final override val enableCommands: List<ByteArray> get() = fusionEnableSamplingCommands(mode)
    final override val startCommands: List<ByteArray> get() = fusionStartCommands(mode, enableCommand)
    final override val stopCommands: List<ByteArray> get() = fusionStopCommands(mode)
    final override val disableCommands: List<ByteArray> get() = fusionDisableSamplingCommands(mode)
}

/**
 * Quaternion output (w, x, y, z) of the on-board sensor fusion algorithm.
 *
 * The most numerically stable orientation representation — no gimbal lock, no
 * angular discontinuities. Use as the input to your own rotation math; convert
 * to Euler angles only at the display layer.
 *
 * Pick `mode = NDOF` for absolute orientation, `IMU_PLUS` for drift-free
 * relative rotation.
 */
class SensorFusionQuaternion(
    mode: SensorFusionMode = SensorFusionMode.NDOF,
    accRange: SensorFusionAccRange = SensorFusionAccRange.G2,
    gyroRange: SensorFusionGyroRange = SensorFusionGyroRange.DPS2000,
    chip: SensorFusionChip = SensorFusionChip.BMI160,
) : SensorFusionSignal<Quaternion>(mode, accRange, gyroRange, chip) {

    override val enableBit: Int = 0x08              // bit 3 = QUATERNION
    override val dataRegister: Int = 0x07           // QUATERNION
    override val loggerKey: String = "quaternion"
    override val logDataChunks: List<LogChunk> =
        listOf(LogChunk(0, 4), LogChunk(4, 4), LogChunk(8, 4), LogChunk(12, 4))

    override fun parseSample(packet: ByteArray): Quaternion =
        PacketParser.parseQuaternion(packet)
}

/**
 * Euler-angles output (heading / pitch / roll / yaw, degrees) of sensor fusion.
 *
 * Human-readable orientation — convenient for UI and CSV export but prone to
 * gimbal lock near ±90° pitch. For long-term integration prefer
 * [SensorFusionQuaternion].
 */
class SensorFusionEuler(
    mode: SensorFusionMode = SensorFusionMode.NDOF,
    accRange: SensorFusionAccRange = SensorFusionAccRange.G2,
    gyroRange: SensorFusionGyroRange = SensorFusionGyroRange.DPS2000,
    chip: SensorFusionChip = SensorFusionChip.BMI160,
) : SensorFusionSignal<EulerAngles>(mode, accRange, gyroRange, chip) {

    override val enableBit: Int = 0x10              // bit 4 = EULER_ANGLES
    override val dataRegister: Int = 0x08           // EULER_ANGLES
    override val loggerKey: String = "euler-angles"
    override val logDataChunks: List<LogChunk> =
        listOf(LogChunk(0, 4), LogChunk(4, 4), LogChunk(8, 4), LogChunk(12, 4))

    override fun parseSample(packet: ByteArray): EulerAngles =
        PacketParser.parseEulerAngles(packet)
}

/**
 * Estimated gravity vector (g) — the component of acceleration attributable
 * to gravity, separated from device motion by the fusion algorithm.
 *
 * Together with [SensorFusionLinearAcceleration] (which is the complementary
 * "motion only" half), this gives an honest split of what the accelerometer
 * sees into orientation + motion.
 */
class SensorFusionGravity(
    mode: SensorFusionMode = SensorFusionMode.NDOF,
    accRange: SensorFusionAccRange = SensorFusionAccRange.G2,
    gyroRange: SensorFusionGyroRange = SensorFusionGyroRange.DPS2000,
    chip: SensorFusionChip = SensorFusionChip.BMI160,
) : SensorFusionSignal<CartesianFloat>(mode, accRange, gyroRange, chip) {

    override val enableBit: Int = 0x20              // bit 5 = GRAVITY
    override val dataRegister: Int = 0x09           // GRAVITY_VECTOR
    override val loggerKey: String = "gravity"
    override val logDataChunks: List<LogChunk> =
        listOf(LogChunk(0, 4), LogChunk(4, 4), LogChunk(8, 4))

    override fun parseSample(packet: ByteArray): CartesianFloat =
        PacketParser.parseGravityVector(packet)
}

/**
 * Linear acceleration (g) — raw acceleration minus the estimated gravity vector.
 *
 * "What the user actually did" — punches, drops, taps, vibration. Pair with
 * an RSS + Pulse/Threshold data-processor chain to detect motion events
 * without writing your own filter on the host.
 */
class SensorFusionLinearAcceleration(
    mode: SensorFusionMode = SensorFusionMode.NDOF,
    accRange: SensorFusionAccRange = SensorFusionAccRange.G2,
    gyroRange: SensorFusionGyroRange = SensorFusionGyroRange.DPS2000,
    chip: SensorFusionChip = SensorFusionChip.BMI160,
) : SensorFusionSignal<CartesianFloat>(mode, accRange, gyroRange, chip) {

    override val enableBit: Int = 0x40              // bit 6 = LINEAR_ACC
    override val dataRegister: Int = 0x0A           // LINEAR_ACC
    override val loggerKey: String = "linear-acceleration"
    override val logDataChunks: List<LogChunk> =
        listOf(LogChunk(0, 4), LogChunk(4, 4), LogChunk(8, 4))

    override fun parseSample(packet: ByteArray): CartesianFloat =
        PacketParser.parseGravityVector(packet)     // same float32 layout
}

// ---- Corrected data signals (bits 0/1/2, registers 0x04/0x05/0x06) ----
//
// Each produces a CorrectedCartesianFloat (x, y, z, accuracy). Per
// `datainterpreter.cpp`:
//   - CORRECTED_ACC  (register 0x04, bit 0) divides x/y/z by SENSOR_FUSION_ACC_SCALE = 1000
//   - CORRECTED_GYRO (register 0x05, bit 1) no scaling — raw float32 (dps)
//   - CORRECTED_MAG  (register 0x06, bit 2) no scaling — raw float32 (µT)

/**
 * Bias-corrected accelerometer output (g) with a per-sample accuracy byte.
 *
 * Same units as the raw accelerometer, but with the fusion algorithm's
 * estimate of zero-g bias subtracted. Use when you want raw accel data plus
 * fusion's quality metric, without the orientation/gravity decomposition.
 */
class SensorFusionCorrectedAcc(
    mode: SensorFusionMode = SensorFusionMode.NDOF,
    accRange: SensorFusionAccRange = SensorFusionAccRange.G2,
    gyroRange: SensorFusionGyroRange = SensorFusionGyroRange.DPS2000,
    chip: SensorFusionChip = SensorFusionChip.BMI160,
) : SensorFusionSignal<CorrectedCartesianFloat>(mode, accRange, gyroRange, chip) {

    override val enableBit: Int = 0x01              // bit 0 = CORRECTED_ACC
    override val dataRegister: Int = 0x04           // CORRECTED_ACC
    override val loggerKey: String = "corrected-acceleration"

    // Corrected data is 3×float32 + 1 byte accuracy = 13 bytes → 4 chunks
    override val logDataChunks: List<LogChunk> =
        listOf(LogChunk(0, 4), LogChunk(4, 4), LogChunk(8, 4), LogChunk(12, 1))

    override fun parseSample(packet: ByteArray): CorrectedCartesianFloat =
        PacketParser.parseCorrectedCartesianFloat(packet, 1000f)
}

/**
 * Bias-corrected gyroscope output (dps) with a per-sample accuracy byte.
 *
 * Raw gyro reading with the algorithm's drift estimate subtracted out. The
 * accuracy byte (0–3) reports the gyro calibration confidence at that sample.
 */
class SensorFusionCorrectedGyro(
    mode: SensorFusionMode = SensorFusionMode.NDOF,
    accRange: SensorFusionAccRange = SensorFusionAccRange.G2,
    gyroRange: SensorFusionGyroRange = SensorFusionGyroRange.DPS2000,
    chip: SensorFusionChip = SensorFusionChip.BMI160,
) : SensorFusionSignal<CorrectedCartesianFloat>(mode, accRange, gyroRange, chip) {

    override val enableBit: Int = 0x02              // bit 1 = CORRECTED_GYRO
    override val dataRegister: Int = 0x05           // CORRECTED_GYRO
    override val loggerKey: String = "corrected-angular-velocity"
    override val logDataChunks: List<LogChunk> =
        listOf(LogChunk(0, 4), LogChunk(4, 4), LogChunk(8, 4), LogChunk(12, 1))

    override fun parseSample(packet: ByteArray): CorrectedCartesianFloat =
        PacketParser.parseCorrectedCartesianFloat(packet, 1.0f)
}

/**
 * Bias-corrected magnetometer output (µT) with a per-sample accuracy byte.
 *
 * Hard-iron offset removed by the fusion algorithm. The accuracy byte goes
 * to 3 only after a full figure-8 calibration motion.
 */
class SensorFusionCorrectedMag(
    mode: SensorFusionMode = SensorFusionMode.NDOF,
    accRange: SensorFusionAccRange = SensorFusionAccRange.G2,
    gyroRange: SensorFusionGyroRange = SensorFusionGyroRange.DPS2000,
    chip: SensorFusionChip = SensorFusionChip.BMI160,
) : SensorFusionSignal<CorrectedCartesianFloat>(mode, accRange, gyroRange, chip) {

    override val enableBit: Int = 0x04              // bit 2 = CORRECTED_MAG
    override val dataRegister: Int = 0x06           // CORRECTED_MAG
    override val loggerKey: String = "corrected-magnetic-field"
    override val logDataChunks: List<LogChunk> =
        listOf(LogChunk(0, 4), LogChunk(4, 4), LogChunk(8, 4), LogChunk(12, 1))

    override fun parseSample(packet: ByteArray): CorrectedCartesianFloat =
        PacketParser.parseCorrectedCartesianFloat(packet, 1.0f)
}

// ---- Fire-and-forget sensor fusion commands ----

/**
 * Clear all data enable bits. Mirrors C++ `mbl_mw_sensor_fusion_clear_enabled_mask`.
 * `[0x19, 0x03, 0x00, 0x7F]` — disables all 7 output streams.
 */
class SensorFusionClearEnabledMask : Command {
    override val commandData: ByteArray
        get() = Packet.command(Module.SENSOR_FUSION, 0x03, 0x00, 0x7F)
}

/**
 * Reset the default orientation. Mirrors C++ `mbl_mw_sensor_fusion_reset_orientation`.
 * `[0x19, 0x0F, 0x01]` — only available on sensor fusion revision >= 3
 * (RESET_ORIENTATION_REVISION). Callers should check
 * `moduleInfo(Module.SENSOR_FUSION)?.revision` before sending.
 */
class SensorFusionResetOrientation : Command {
    override val commandData: ByteArray
        get() = Packet.command(Module.SENSOR_FUSION, 0x0F, 0x01)
}
