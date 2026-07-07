package com.mbientlab.metawear.app.data

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.RawLogEntry
import com.mbientlab.metawear.app.core.AnyChartSample
import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.decodeEntries
import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.model.CorrectedCartesianFloat
import com.mbientlab.metawear.model.DeviceInformation
import com.mbientlab.metawear.model.EulerAngles
import com.mbientlab.metawear.model.LoggedSample
import com.mbientlab.metawear.model.ModuleInfo
import com.mbientlab.metawear.model.Quaternion
import com.mbientlab.metawear.persistence.CartesianFloatPersistable
import com.mbientlab.metawear.persistence.CorrectedCartesianFloatPersistable
import com.mbientlab.metawear.persistence.EulerAnglesPersistable
import com.mbientlab.metawear.persistence.Persistable
import com.mbientlab.metawear.persistence.PersistenceStore
import com.mbientlab.metawear.persistence.QuaternionPersistable
import com.mbientlab.metawear.persistence.SessionSnapshot
import com.mbientlab.metawear.protocol.Loggable
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.sensor.Accelerometer
import com.mbientlab.metawear.sensor.Gyroscope
import com.mbientlab.metawear.sensor.Magnetometer
import com.mbientlab.metawear.sensor.SensorFusionChip
import com.mbientlab.metawear.sensor.SensorFusionCorrectedAcc
import com.mbientlab.metawear.sensor.SensorFusionCorrectedGyro
import com.mbientlab.metawear.sensor.SensorFusionCorrectedMag
import com.mbientlab.metawear.sensor.SensorFusionEuler
import com.mbientlab.metawear.sensor.SensorFusionGravity
import com.mbientlab.metawear.sensor.SensorFusionLinearAcceleration
import com.mbientlab.metawear.sensor.SensorFusionMode
import com.mbientlab.metawear.sensor.SensorFusionQuaternion
import com.mbientlab.metawear.startLogging
import com.mbientlab.metawear.stopLogging
import kotlin.math.abs
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

/**
 * A [SensorSelection] resolved against the connected board's module table into
 * a typed SDK sensor. Centralizes the typed dispatch (the Kotlin analogue of
 * the Swift app's `switch selection.id` blocks) so ViewModels stay
 * monomorphic: streams surface as [AnyChartSample], persistence goes through
 * the matching [Persistable] codec.
 */
sealed interface ConfiguredSensor {
    val selection: SensorSelection

    data class CartesianKind(
        override val selection: SensorSelection,
        val sensor: Loggable<CartesianFloat>,
    ) : ConfiguredSensor

    data class QuaternionKind(
        override val selection: SensorSelection,
        val sensor: Loggable<Quaternion>,
    ) : ConfiguredSensor

    data class EulerKind(
        override val selection: SensorSelection,
        val sensor: Loggable<EulerAngles>,
    ) : ConfiguredSensor

    data class CorrectedKind(
        override val selection: SensorSelection,
        val sensor: Loggable<CorrectedCartesianFloat>,
    ) : ConfiguredSensor

    companion object {

        /**
         * Resolve [selection] into a chip-appropriate sensor using the module
         * table populated during `connect()` (BMI160 vs BMI270 differences are
         * hidden by the SDK's type-erased factories).
         */
        fun make(selection: SensorSelection, modules: Map<Module, ModuleInfo>): ConfiguredSensor {
            val accImpl = modules[Module.ACCELEROMETER]?.implementation ?: 4
            val gyroImpl = modules[Module.GYRO]?.implementation ?: 1
            val fusionChip = SensorFusionChip.fromAccImpl(accImpl) ?: SensorFusionChip.BMI270

            fun fusionMode() = SensorFusionMode.NDOF

            return when (selection.key) {
                SensorKey.ACCELEROMETER -> CartesianKind(
                    selection,
                    Accelerometer.make(accImpl, odrHz = selection.hz, rangeG = selection.range ?: 8f)
                        ?: Accelerometer.make(4, selection.hz, selection.range ?: 8f)!!,
                )
                SensorKey.GYROSCOPE -> CartesianKind(
                    selection,
                    Gyroscope.make(gyroImpl, odrHz = selection.hz, rangeDps = selection.range ?: 2000f)
                        ?: Gyroscope.make(1, selection.hz, selection.range ?: 2000f)!!,
                )
                SensorKey.MAGNETOMETER -> CartesianKind(
                    selection,
                    Magnetometer(
                        xyReps = 9,
                        zReps = 15,
                        odr = Magnetometer.Odr.entries.minByOrNull { abs(it.hz - selection.hz) }!!,
                    ),
                )
                SensorKey.FUSION_QUATERNION ->
                    QuaternionKind(selection, SensorFusionQuaternion(mode = fusionMode(), chip = fusionChip))
                SensorKey.FUSION_EULER ->
                    EulerKind(selection, SensorFusionEuler(mode = fusionMode(), chip = fusionChip))
                SensorKey.FUSION_GRAVITY ->
                    CartesianKind(selection, SensorFusionGravity(mode = fusionMode(), chip = fusionChip))
                SensorKey.FUSION_LINEAR_ACCELERATION ->
                    CartesianKind(selection, SensorFusionLinearAcceleration(mode = fusionMode(), chip = fusionChip))
                SensorKey.FUSION_CORRECTED_ACC ->
                    CorrectedKind(selection, SensorFusionCorrectedAcc(mode = fusionMode(), chip = fusionChip))
                SensorKey.FUSION_CORRECTED_GYRO ->
                    CorrectedKind(selection, SensorFusionCorrectedGyro(mode = fusionMode(), chip = fusionChip))
                SensorKey.FUSION_CORRECTED_MAG ->
                    CorrectedKind(selection, SensorFusionCorrectedMag(mode = fusionMode(), chip = fusionChip))
            }
        }
    }
}

// ---- Streaming ----

/** Start streaming and surface every typed sample as an [AnyChartSample]. */
suspend fun ConfiguredSensor.openStream(device: MetaWearDevice): Flow<AnyChartSample> = when (this) {
    is ConfiguredSensor.CartesianKind -> device.startStream(sensor).map { AnyChartSample.from(it) }
    is ConfiguredSensor.QuaternionKind -> device.startStream(sensor).map { AnyChartSample.from(it) }
    is ConfiguredSensor.EulerKind -> device.startStream(sensor).map { AnyChartSample.from(it) }
    is ConfiguredSensor.CorrectedKind -> device.startStream(sensor).map { AnyChartSample.from(it) }
}

suspend fun ConfiguredSensor.stopStream(device: MetaWearDevice) = when (this) {
    is ConfiguredSensor.CartesianKind -> device.stopStreaming(sensor)
    is ConfiguredSensor.QuaternionKind -> device.stopStreaming(sensor)
    is ConfiguredSensor.EulerKind -> device.stopStreaming(sensor)
    is ConfiguredSensor.CorrectedKind -> device.stopStreaming(sensor)
}

// ---- Logging ----

suspend fun ConfiguredSensor.startLoggingOn(device: MetaWearDevice) = when (this) {
    is ConfiguredSensor.CartesianKind -> device.startLogging(sensor)
    is ConfiguredSensor.QuaternionKind -> device.startLogging(sensor)
    is ConfiguredSensor.EulerKind -> device.startLogging(sensor)
    is ConfiguredSensor.CorrectedKind -> device.startLogging(sensor)
}

suspend fun ConfiguredSensor.stopLoggingOn(device: MetaWearDevice) = when (this) {
    is ConfiguredSensor.CartesianKind -> device.stopLogging(sensor)
    is ConfiguredSensor.QuaternionKind -> device.stopLogging(sensor)
    is ConfiguredSensor.EulerKind -> device.stopLogging(sensor)
    is ConfiguredSensor.CorrectedKind -> device.stopLogging(sensor)
}

/**
 * Decode this sensor's samples from an already-drained raw entry list and
 * persist them as one session. Returns `null` when the log held no samples
 * for this sensor (nothing is saved).
 */
suspend fun ConfiguredSensor.decodeAndSave(
    device: MetaWearDevice,
    store: PersistenceStore,
    entries: List<RawLogEntry>,
    deviceInfo: DeviceInformation,
): SessionSnapshot? {
    suspend fun <S : Any> save(samples: List<LoggedSample<S>>, persistable: Persistable<S>): SessionSnapshot? {
        if (samples.isEmpty()) return null
        return store.saveSession(
            deviceID = device.identifier,
            deviceInfo = deviceInfo,
            sensorKind = persistable.persistenceKind,
            samples = samples,
            persistable = persistable,
            label = selection.displayLabel,
        )
    }
    return when (this) {
        is ConfiguredSensor.CartesianKind -> save(device.decodeEntries(entries, sensor), CartesianFloatPersistable)
        is ConfiguredSensor.QuaternionKind -> save(device.decodeEntries(entries, sensor), QuaternionPersistable)
        is ConfiguredSensor.EulerKind -> save(device.decodeEntries(entries, sensor), EulerAnglesPersistable)
        is ConfiguredSensor.CorrectedKind ->
            save(device.decodeEntries(entries, sensor), CorrectedCartesianFloatPersistable)
    }
}

// ---- Live-buffer archiving ----

/**
 * Persist a live-stream capture buffer as a session (the Swift app's
 * archive-to-history on stop). Chart samples are rehydrated into their typed
 * form via the channel-count convention in [AnyChartSample].
 */
suspend fun ConfiguredSensor.saveLiveBuffer(
    store: PersistenceStore,
    deviceId: String,
    deviceInfo: DeviceInformation,
    samples: List<AnyChartSample>,
    startedAt: Instant,
): SessionSnapshot? {
    if (samples.isEmpty()) return null

    fun tickMs(time: Instant): Double = (time - startedAt).elapsedMs()

    suspend fun <S : Any> save(persistable: Persistable<S>, value: (AnyChartSample) -> S): SessionSnapshot =
        store.saveSession(
            deviceID = deviceId,
            deviceInfo = deviceInfo,
            sensorKind = persistable.persistenceKind,
            samples = samples.map { LoggedSample(date = it.time, tickMs = tickMs(it.time), value = value(it)) },
            persistable = persistable,
            label = selection.displayLabel,
        )

    return when (this) {
        is ConfiguredSensor.CartesianKind ->
            save(CartesianFloatPersistable) { CartesianFloat(it.f0, it.f1, it.f2) }
        is ConfiguredSensor.QuaternionKind ->
            save(QuaternionPersistable) { Quaternion(it.f0, it.f1, it.f2, it.f3) }
        is ConfiguredSensor.EulerKind ->
            save(EulerAnglesPersistable) { EulerAngles(it.f0, it.f1, it.f2, it.f3) }
        is ConfiguredSensor.CorrectedKind ->
            save(CorrectedCartesianFloatPersistable) { CorrectedCartesianFloat(it.f0, it.f1, it.f2, it.f3.toInt()) }
    }
}

private fun Duration.elapsedMs(): Double = inWholeMicroseconds / 1000.0
