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
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.model.ModuleInfo
import com.mbientlab.metawear.model.Quaternion
import com.mbientlab.metawear.persistence.CartesianFloatPersistable
import com.mbientlab.metawear.persistence.CorrectedCartesianFloatPersistable
import com.mbientlab.metawear.persistence.EulerAnglesPersistable
import com.mbientlab.metawear.persistence.FloatPersistable
import com.mbientlab.metawear.persistence.Persistable
import com.mbientlab.metawear.persistence.PersistenceStore
import com.mbientlab.metawear.persistence.QuaternionPersistable
import com.mbientlab.metawear.persistence.SessionSnapshot
import com.mbientlab.metawear.protocol.Loggable
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Pollable
import com.mbientlab.metawear.protocol.PolledLoggable
import com.mbientlab.metawear.protocol.PolledLogger
import com.mbientlab.metawear.protocol.PolledLoggerHandles
import com.mbientlab.metawear.protocol.Streamable
import com.mbientlab.metawear.recoverLoggers
import com.mbientlab.metawear.sensor.Accelerometer
import com.mbientlab.metawear.sensor.Altimeter
import com.mbientlab.metawear.sensor.AmbientLight
import com.mbientlab.metawear.sensor.Barometer
import com.mbientlab.metawear.sensor.Gyroscope
import com.mbientlab.metawear.sensor.Humidity
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
import com.mbientlab.metawear.sensor.Thermometer
import com.mbientlab.metawear.sensor.ThermometerSource
import com.mbientlab.metawear.startLogging
import com.mbientlab.metawear.stopLogging
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

/**
 * A [SensorSelection] resolved against the connected board's module table into
 * a typed SDK sensor. Centralizes the per-sensor typed dispatch in one place
 * so ViewModels stay monomorphic: streams surface as [AnyChartSample],
 * persistence goes through the matching [Persistable] codec.
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

    /**
     * Streamed scalar signal (barometer pressure, altitude). [loggable] is
     * `null` for stream-only signals (altitude), whose logging attempts fail
     * with a clear error.
     */
    class FloatKind(
        override val selection: SensorSelection,
        val stream: Streamable<Float>,
        val loggable: Loggable<Float>?,
    ) : ConfiguredSensor

    /**
     * LTR329 ambient light — streams/logs raw milli-lux `Long`s, surfaced to
     * the app as lux `Float`s.
     */
    class IlluminanceKind(
        override val selection: SensorSelection,
        val sensor: AmbientLight,
    ) : ConfiguredSensor

    /**
     * Timer-driven environmental readable (temperature / humidity / pressure).
     * [readable] and [pollable] are the same object viewed through the two SDK
     * interfaces; [prepareCommands]/[teardownCommands] bracket sensors that
     * must be running for one-shot reads to return fresh data (the barometer's
     * cyclic mode).
     */
    class PolledKind(
        override val selection: SensorSelection,
        val readable: PolledLoggable<Float>,
        val pollable: Pollable<Float>,
        val prepareCommands: List<ByteArray> = emptyList(),
        val teardownCommands: List<ByteArray> = emptyList(),
    ) : ConfiguredSensor {
        val periodMs: Long get() = selection.effectivePollIntervalMs
        val logger: PolledLogger<Float> get() = PolledLogger(readable, periodMs)
    }

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
                SensorKey.TEMPERATURE -> {
                    val thermometer = Thermometer(channel = thermometerChannel(modules))
                    PolledKind(selection, readable = thermometer, pollable = thermometer)
                }
                SensorKey.HUMIDITY -> {
                    val humidity = Humidity()
                    PolledKind(selection, readable = humidity, pollable = humidity)
                }
                SensorKey.PRESSURE -> {
                    // One-shot pressure reads return stale zeros unless the
                    // BMP280/BME280 is sampling — bracket the poll/log with
                    // the barometer's cyclic start/stop.
                    val pressure = PolledPressure()
                    val barometer = Barometer()
                    PolledKind(
                        selection,
                        readable = pressure,
                        pollable = pressure,
                        prepareCommands = barometer.configureCommands + listOf(barometer.startCommand),
                        teardownCommands = listOf(barometer.stopCommand),
                    )
                }
                SensorKey.PRESSURE_STREAMED -> {
                    val barometer = Barometer(standbyTime = barometerStandbyFor(selection.hz))
                    FloatKind(selection, stream = barometer, loggable = barometer)
                }
                SensorKey.ALTITUDE -> FloatKind(
                    selection,
                    stream = Altimeter(Barometer(standbyTime = barometerStandbyFor(selection.hz))),
                    loggable = null,   // stream-only: no SDK Loggable conformance
                )
                SensorKey.AMBIENT_LIGHT -> IlluminanceKind(
                    selection,
                    AmbientLight(measurementRate = lightRateFor(selection.hz)),
                )
            }
        }

        /** Nominal Hz → BMP280 standby time (~1 / 8 / 25 Hz notifications). Pure — unit-tested. */
        fun barometerStandbyFor(hz: Double): Barometer.BmpStandbyTime = when {
            hz <= 2.0 -> Barometer.BmpStandbyTime.MS1000
            hz <= 12.0 -> Barometer.BmpStandbyTime.MS125
            else -> Barometer.BmpStandbyTime.MS0_5
        }

        /** Nominal Hz → LTR329 measurement rate (nearest period). Pure — unit-tested. */
        fun lightRateFor(hz: Double): AmbientLight.MeasurementRate {
            val periodMs = 1000.0 / hz
            return AmbientLight.MeasurementRate.entries.minByOrNull { abs(it.milliseconds - periodMs) }!!
        }

        /**
         * Best temperature channel for the board: the module-info extra bytes
         * list each channel's source; prefer the on-board preset thermistor,
         * fall back to the nRF die (channel 0). Pure — unit-tested.
         */
        fun thermometerChannel(modules: Map<Module, ModuleInfo>): Int {
            val sources = modules[Module.TEMPERATURE]?.extra ?: return 0
            val preset = sources.indexOfFirst { it == ThermometerSource.PRESET_THERMISTOR.raw }
            return if (preset >= 0) preset else 0
        }
    }
}

// ---- Streaming / live polling ----

/**
 * Start producing live samples as [AnyChartSample]: BLE streaming for streamed
 * kinds; host-side `device.poll` one-shot reads for polled kinds (state stays
 * Idle — polling isn't a board-side stream).
 */
suspend fun ConfiguredSensor.openStream(device: MetaWearDevice): Flow<AnyChartSample> = when (this) {
    is ConfiguredSensor.CartesianKind -> device.startStream(sensor).map { AnyChartSample.from(it) }
    is ConfiguredSensor.QuaternionKind -> device.startStream(sensor).map { AnyChartSample.from(it) }
    is ConfiguredSensor.EulerKind -> device.startStream(sensor).map { AnyChartSample.from(it) }
    is ConfiguredSensor.CorrectedKind -> device.startStream(sensor).map { AnyChartSample.from(it) }
    is ConfiguredSensor.FloatKind ->
        device.startStream(stream).map { AnyChartSample.of(it.time, it.value, channelCount = 1) }
    is ConfiguredSensor.IlluminanceKind ->
        device.startStream(sensor).map { AnyChartSample.of(it.time, AmbientLight.lux(it.value), channelCount = 1) }
    is ConfiguredSensor.PolledKind -> {
        for (cmd in prepareCommands) device.send(RawCommand(cmd))
        device.poll(pollable, every = periodMs.milliseconds)
            .map { AnyChartSample.of(it.time, it.value, channelCount = 1) }
    }
}

suspend fun ConfiguredSensor.stopStream(device: MetaWearDevice) = when (this) {
    is ConfiguredSensor.CartesianKind -> device.stopStreaming(sensor)
    is ConfiguredSensor.QuaternionKind -> device.stopStreaming(sensor)
    is ConfiguredSensor.EulerKind -> device.stopStreaming(sensor)
    is ConfiguredSensor.CorrectedKind -> device.stopStreaming(sensor)
    is ConfiguredSensor.FloatKind -> device.stopStreaming(stream)
    is ConfiguredSensor.IlluminanceKind -> device.stopStreaming(sensor)
    is ConfiguredSensor.PolledKind -> {
        // The poll loop stops when its collecting coroutine is cancelled;
        // only the prepared hardware (barometer cyclic mode) needs stopping.
        for (cmd in teardownCommands) device.send(RawCommand(cmd))
    }
}

// ---- Logging ----

/**
 * Start on-device logging. Polled kinds build the on-board timer → event →
 * logger chain and return its resource handles (persist them on the session
 * record — `stopLoggingOn` needs them); streamed kinds return `null`.
 */
suspend fun ConfiguredSensor.startLoggingOn(device: MetaWearDevice): PolledLoggerHandles? = when (this) {
    is ConfiguredSensor.CartesianKind -> {
        device.startLogging(sensor)
        null
    }
    is ConfiguredSensor.QuaternionKind -> {
        device.startLogging(sensor)
        null
    }
    is ConfiguredSensor.EulerKind -> {
        device.startLogging(sensor)
        null
    }
    is ConfiguredSensor.CorrectedKind -> {
        device.startLogging(sensor)
        null
    }
    is ConfiguredSensor.FloatKind -> {
        val target = loggable ?: throw MetaWearException.InvalidState(
            "${selection.key.title} is stream-only and cannot be logged",
        )
        device.startLogging(target)
        null
    }
    is ConfiguredSensor.IlluminanceKind -> {
        device.startLogging(sensor)
        null
    }
    is ConfiguredSensor.PolledKind -> {
        for (cmd in prepareCommands) device.send(RawCommand(cmd))
        device.startLogging(logger)
    }
}

/**
 * Stop on-device logging. [handles] is required for polled kinds (dismantles
 * the timer/event chain) and ignored for streamed kinds.
 */
suspend fun ConfiguredSensor.stopLoggingOn(device: MetaWearDevice, handles: PolledLoggerHandles? = null) =
    when (this) {
        is ConfiguredSensor.CartesianKind -> device.stopLogging(sensor)
        is ConfiguredSensor.QuaternionKind -> device.stopLogging(sensor)
        is ConfiguredSensor.EulerKind -> device.stopLogging(sensor)
        is ConfiguredSensor.CorrectedKind -> device.stopLogging(sensor)
        is ConfiguredSensor.FloatKind -> loggable?.let { device.stopLogging(it) } ?: Unit
        is ConfiguredSensor.IlluminanceKind -> device.stopLogging(sensor)
        is ConfiguredSensor.PolledKind -> {
            if (handles != null) device.stopLogging(logger, handles)
            for (cmd in teardownCommands) device.send(RawCommand(cmd))
        }
    }

/**
 * Rebuild the device's in-memory logger registry from the board's active
 * trigger table — required before decoding a download when the app process
 * restarted since `startLogging` (the registry doesn't survive process death;
 * the board-side triggers do).
 */
suspend fun ConfiguredSensor.recoverLoggersOn(device: MetaWearDevice) {
    when (this) {
        is ConfiguredSensor.CartesianKind -> device.recoverLoggers(sensor)
        is ConfiguredSensor.QuaternionKind -> device.recoverLoggers(sensor)
        is ConfiguredSensor.EulerKind -> device.recoverLoggers(sensor)
        is ConfiguredSensor.CorrectedKind -> device.recoverLoggers(sensor)
        is ConfiguredSensor.FloatKind -> loggable?.let { device.recoverLoggers(it) }
        is ConfiguredSensor.IlluminanceKind -> device.recoverLoggers(sensor)
        is ConfiguredSensor.PolledKind -> device.recoverLoggers(logger)
    }
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

    // The registry entry is present when startLogging ran in this process;
    // after a process restart it must be recovered from the board's trigger
    // table before the entries can be decoded.
    suspend fun <S> decodeWithRecovery(decode: () -> List<LoggedSample<S>>): List<LoggedSample<S>> =
        try {
            decode()
        } catch (e: MetaWearException.InvalidState) {
            recoverLoggersOn(device)
            decode()
        }

    return when (this) {
        is ConfiguredSensor.CartesianKind ->
            save(decodeWithRecovery { device.decodeEntries(entries, sensor) }, CartesianFloatPersistable)
        is ConfiguredSensor.QuaternionKind ->
            save(decodeWithRecovery { device.decodeEntries(entries, sensor) }, QuaternionPersistable)
        is ConfiguredSensor.EulerKind ->
            save(decodeWithRecovery { device.decodeEntries(entries, sensor) }, EulerAnglesPersistable)
        is ConfiguredSensor.CorrectedKind ->
            save(decodeWithRecovery { device.decodeEntries(entries, sensor) }, CorrectedCartesianFloatPersistable)
        is ConfiguredSensor.FloatKind -> {
            val target = loggable ?: return null
            save(decodeWithRecovery { device.decodeEntries(entries, target) }, FloatPersistable)
        }
        is ConfiguredSensor.IlluminanceKind -> save(
            decodeWithRecovery { device.decodeEntries(entries, sensor) }
                .map { LoggedSample(it.date, it.tickMs, AmbientLight.lux(it.value)) },
            FloatPersistable,
        )
        is ConfiguredSensor.PolledKind ->
            save(decodeWithRecovery { device.decodeEntries(entries, logger) }, FloatPersistable)
    }
}

// ---- Live-buffer archiving ----

/**
 * Persist a live-stream capture buffer as a session (archive-to-history on
 * stop). Chart samples are rehydrated into their typed form via the
 * channel-count convention in [AnyChartSample].
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
        is ConfiguredSensor.FloatKind -> save(FloatPersistable) { it.f0 }
        is ConfiguredSensor.IlluminanceKind -> save(FloatPersistable) { it.f0 }
        is ConfiguredSensor.PolledKind -> save(FloatPersistable) { it.f0 }
    }
}

private fun Duration.elapsedMs(): Double = inWholeMicroseconds / 1000.0
