package com.mbientlab.metawear

import com.mbientlab.metawear.model.DeviceInformation
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.model.ModuleInfo
import com.mbientlab.metawear.model.Timestamped
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.CommandSequence
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser
import com.mbientlab.metawear.protocol.Pollable
import com.mbientlab.metawear.protocol.ProtocolRouter
import com.mbientlab.metawear.protocol.Readable
import com.mbientlab.metawear.protocol.Streamable
import com.mbientlab.metawear.transport.BleTransport
import com.mbientlab.metawear.transport.Uuids
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

// Port of MetaWearDevice.swift — the Step 3 vertical slice: connection,
// state machine, streaming, commands, one-shot reads, and polling. Logging,
// download, macro, and anonymous-signal recovery land with the module fan-out.

/**
 * High-level lifecycle state for a single MetaWear connection.
 *
 * Used as a guardrail: operations that would conflict on the board (such as
 * streaming while downloading logs) fail early with
 * [MetaWearException.InvalidState] instead of racing firmware state.
 */
sealed class DeviceState {
    /** No active BLE connection is held by this device. */
    data object Disconnected : DeviceState()

    /** A connection attempt is in progress and initialization has not finished. */
    data object Connecting : DeviceState()

    /** Connected, initialized, and ready for reads, commands, streams, or logs. */
    data object Idle : DeviceState()

    /** One or more live BLE streams are active. */
    data object Streaming : DeviceState()

    /** The logging module is recording one or more configured signals to flash. */
    data object Logging : DeviceState()

    /** A flash-log readout is in progress; [progress] is `0.0..1.0`. */
    data class Downloading(val progress: Double) : DeviceState()
}

/**
 * The main entry point for communicating with a single MetaWear sensor.
 * Port of the Swift `MetaWearDevice` actor.
 *
 * Thread-safety: state transitions are guarded by an atomic compare-and-set on
 * the state flow (connection) and a [Mutex] serializing stream start/stop
 * command sequences — the Kotlin translation of actor isolation.
 *
 * Production code usually receives instances from [MetaWearScanner]. Tests
 * inject a `MockBleTransport` and the `runTest` scope for virtual time.
 */
class MetaWearDevice(
    /** Peripheral identifier (MAC address on Android). */
    val identifier: String,
    private val transport: BleTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val router = ProtocolRouter(transport, scope)

    // ---- Public state ----

    private val _state = MutableStateFlow<DeviceState>(DeviceState.Disconnected)

    /** Current lifecycle state. Observe before starting mutually-exclusive operations. */
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    /** Device Information Service values read during [connect]. */
    var deviceInfo: DeviceInformation? = null
        private set

    /** Module-discovery table populated during [connect]. */
    var modules: Map<Module, ModuleInfo> = emptyMap()
        private set

    /** Called when BLE drops unexpectedly (not via [disconnect]). */
    @Volatile var onUnexpectedDisconnect: ((Throwable) -> Unit)? = null

    // ---- Private state ----

    private data class ActiveStreamKey(val module: Module, val dataRegister: Int)

    /** Serializes stream start/stop so two sensors' command sequences can't interleave. */
    private val opMutex = Mutex()

    /** Signals currently streaming — duplicate and fusion/IMU conflict detection. */
    private val activeStreamKeys = mutableSetOf<ActiveStreamKey>()

    /**
     * Fusion config command (`[0x19, 0x02, mode, range]`) of the running
     * sensor-fusion engine, or `null` when no fusion output is active. All
     * simultaneous fusion outputs share one engine, so they must share this
     * mode/range.
     */
    private var activeFusionConfig: ByteArray? = null

    /** Wall-clock instant corresponding to device tick 0 (read during connect). */
    var logReferenceDate: Instant? = null
        private set

    // ---- Connection ----

    /**
     * Connect, start the protocol router, read device information, and discover
     * modules. A successful call leaves the device [DeviceState.Idle] with
     * [deviceInfo] and [modules] populated; on failure the device returns to
     * [DeviceState.Disconnected] and the underlying error is rethrown.
     */
    suspend fun connect() {
        // Atomic disconnected → connecting; a concurrent connect fails fast.
        if (!_state.compareAndSet(DeviceState.Disconnected, DeviceState.Connecting)) {
            throw MetaWearException.InvalidState("Already connected or connecting")
        }
        try {
            transport.connect(identifier)
            router.start()
            router.setDisconnectHandler(::handleUnexpectedDisconnect)
            initialize()
            _state.value = DeviceState.Idle
        } catch (e: Throwable) {
            _state.value = DeviceState.Disconnected
            throw e
        }
    }

    /** Reconnect after an unexpected BLE drop (device must be [DeviceState.Disconnected]). */
    suspend fun reconnect() = connect()

    /**
     * Disconnect intentionally and tear down local protocol subscriptions.
     * Suppresses [onUnexpectedDisconnect] for this drop.
     */
    suspend fun disconnect() {
        router.clearDisconnectHandler()
        router.stop()
        transport.disconnect()
        _state.value = DeviceState.Disconnected
        activeStreamKeys.clear()
        activeFusionConfig = null
    }

    // ---- Streaming ----

    /**
     * Stream a sensor signal continuously.
     *
     * ```kotlin
     * val stream = device.startStream(AccelerometerBmi160(Odr.HZ100, Range.G2))
     * stream.collect { sample -> println("${sample.time} ${sample.value}") }
     * ```
     *
     * With [usePacked] (default) and a sensor that supports it, the board packs
     * 3 samples per BLE packet; the returned flow still emits one sample at a time.
     */
    suspend fun <S> startStream(
        sensor: Streamable<S>,
        usePacked: Boolean = true,
    ): Flow<Timestamped<S>> = opMutex.withLock {
        when (_state.value) {
            DeviceState.Idle, DeviceState.Streaming -> Unit
            else -> throw MetaWearException.InvalidState("Device must be idle or streaming to add a sensor")
        }
        val streamKey = ActiveStreamKey(sensor.module, sensor.dataRegister)
        checkSensorConflict(streamKey)
        val moduleWasAlreadyStreaming = activeStreamKeys.any { it.module == sensor.module }

        // Sensor fusion runs a single shared engine; all outputs share one
        // mode/range. A second output with a different config would be silently
        // ignored by the board, so reject it with a clear error instead.
        if (sensor.module == Module.SENSOR_FUSION) {
            val newConfig = sensor.configureCommands.firstOrNull {
                it.size >= 2 && (it[0].toInt() and 0xFF) == Module.SENSOR_FUSION.value && it[1].toInt() == 0x02
            }
            if (moduleWasAlreadyStreaming) {
                val active = activeFusionConfig
                if (active != null && newConfig != null && !active.contentEquals(newConfig)) {
                    throw MetaWearException.InvalidState(
                        "Cannot add a sensor-fusion output with a different mode/range than the running fusion engine",
                    )
                }
            } else {
                activeFusionConfig = newConfig
            }
        }

        _state.value = DeviceState.Streaming
        activeStreamKeys.add(streamKey)

        val register = if (usePacked && sensor.packedDataRegister != null) {
            sensor.packedDataRegister!!
        } else {
            sensor.dataRegister
        }

        // C++ equivalent:
        //   (optional warmup)                        → warmupCommands + delay
        //   mbl_mw_acc_write_acceleration_config     → configureCommands
        //   mbl_mw_datasignal_subscribe              → [module, register, 0x01]
        //   mbl_mw_acc_enable_acceleration_sampling  → enableCommand
        //   mbl_mw_acc_start                         → startCommand
        try {
            for (cmd in sensor.warmupCommands) if (cmd.isNotEmpty()) router.write(cmd)
            if (sensor.warmupDelayNanos > 0) delay(sensor.warmupDelayNanos.nanoseconds)
            if (sensor.module == Module.SENSOR_FUSION && moduleWasAlreadyStreaming) {
                router.write(Packet.command(sensor.module, register, 0x01))
                if (sensor.enableCommand.isNotEmpty()) router.write(sensor.enableCommand)
            } else {
                for (cmd in sensor.configureCommands) if (cmd.isNotEmpty()) router.write(cmd)
                router.write(Packet.command(sensor.module, register, 0x01))
                for (cmd in sensor.enableCommands) if (cmd.isNotEmpty()) router.write(cmd)
                for (cmd in sensor.startCommands) if (cmd.isNotEmpty()) router.write(cmd)
            }
        } catch (e: Throwable) {
            // Roll back — the sensor never started. Leaving it marked active
            // would block any retry with "already streaming".
            activeStreamKeys.remove(streamKey)
            if (activeStreamKeys.none { it.module == Module.SENSOR_FUSION }) activeFusionConfig = null
            _state.value = if (activeStreamKeys.isEmpty()) DeviceState.Idle else DeviceState.Streaming
            throw e
        }

        val raw = router.subscribe(sensor.module, register)
        val isPacked = usePacked && sensor.packedDataRegister != null

        flow {
            raw.collect { packet ->
                val now = Clock.System.now()
                if (isPacked) {
                    for (sample in sensor.parsePackedSamples(packet)) emit(Timestamped(now, sample))
                } else {
                    emit(Timestamped(now, sensor.parseSample(packet)))
                }
            }
        }
    }

    /**
     * Stop one active stream and unsubscribe its BLE notifications. If other
     * streams remain the device stays [DeviceState.Streaming]; once the last
     * one stops it returns to [DeviceState.Idle]. Fusion outputs share one
     * engine, so stopping one output only disables that output bit while other
     * fusion outputs are running.
     */
    suspend fun <S> stopStreaming(sensor: Streamable<S>): Unit = opMutex.withLock {
        if (_state.value != DeviceState.Streaming) return
        val streamKey = ActiveStreamKey(sensor.module, sensor.dataRegister)
        val remainingKeys = activeStreamKeys - streamKey
        val hasOtherFusionStreams = sensor.module == Module.SENSOR_FUSION &&
            remainingKeys.any { it.module == Module.SENSOR_FUSION }

        if (hasOtherFusionStreams) {
            if (sensor.disableCommand.isNotEmpty()) router.write(sensor.disableCommand)
        } else {
            for (cmd in sensor.stopCommands) if (cmd.isNotEmpty()) router.write(cmd)
            for (cmd in sensor.disableCommands) if (cmd.isNotEmpty()) router.write(cmd)
        }
        router.write(Packet.command(sensor.module, sensor.dataRegister, 0x00))
        sensor.packedDataRegister?.let { router.write(Packet.command(sensor.module, it, 0x00)) }
        router.unsubscribe(sensor.module, sensor.dataRegister)
        sensor.packedDataRegister?.let { router.unsubscribe(sensor.module, it) }

        activeStreamKeys.remove(streamKey)
        if (activeStreamKeys.none { it.module == Module.SENSOR_FUSION }) activeFusionConfig = null
        _state.value = if (activeStreamKeys.isEmpty()) DeviceState.Idle else DeviceState.Streaming
    }

    // ---- Commands ----

    /** Send a single fire-and-forget command to the board. */
    suspend fun send(command: Command) {
        router.write(command.commandData)
    }

    /**
     * Issue a multi-write action (e.g. an enable/disable pair for a BMI270
     * feature). Writes go out in order; if one throws, the rest are skipped.
     */
    suspend fun send(sequence: CommandSequence) {
        for (cmd in sequence.commands) router.write(cmd)
    }

    // ---- One-shot reads and polling ----

    /**
     * Generic one-shot read for any [Readable]: writes its `readCommand`,
     * awaits the response on `(module, dataRegister)`, and returns the parsed
     * sample stamped with the arrival time.
     */
    suspend fun <S> read(readable: Readable<S>): Timestamped<S> {
        val packet = router.writeAndRead(
            command = readable.readCommand,
            awaitModule = readable.module,
            awaitRegister = readable.dataRegister,
        )
        return Timestamped(Clock.System.now(), readable.parseSample(packet))
    }

    /**
     * Poll a [Pollable] on a repeating interval. The first read fires
     * immediately; subsequent reads fire [every] after the previous response
     * completes. Cancel the collecting coroutine to stop; a read error
     * terminates the flow.
     */
    fun <S> poll(readable: Pollable<S>, every: Duration): Flow<Timestamped<S>> = flow {
        while (true) {
            emit(read(readable))
            delay(every)
        }
    }

    /** Read the current RSSI of the active connection, in dBm. */
    suspend fun readRSSI(): Int = transport.readRSSI()

    // ---- Module info convenience ----

    /** Discovery info for one module, or `null` if absent from the last discovery. */
    fun moduleInfo(module: Module): ModuleInfo? = modules[module]

    val hasGyroscope: Boolean get() = modules[Module.GYRO]?.isPresent ?: false
    val hasMagnetometer: Boolean get() = modules[Module.MAGNETOMETER]?.isPresent ?: false
    val hasBarometer: Boolean get() = modules[Module.BAROMETER]?.isPresent ?: false
    val hasSensorFusion: Boolean get() = modules[Module.SENSOR_FUSION]?.isPresent ?: false

    // ---- Initialization ----

    private suspend fun initialize() {
        deviceInfo = readDeviceInformation()
        modules = router.discoverModules()
        readLogTimeReference()
    }

    private suspend fun readDeviceInformation(): DeviceInformation = DeviceInformation(
        manufacturer = readDisString(Uuids.manufacturerName),
        modelNumber = readDisString(Uuids.modelNumber),
        serialNumber = readDisString(Uuids.serialNumber),
        firmwareRevision = readDisString(Uuids.firmwareRevision),
        hardwareRevision = readDisString(Uuids.hardwareRevision),
    )

    private suspend fun readDisString(uuid: UUID): String =
        transport.read(uuid).toString(Charsets.UTF_8)

    /**
     * Read the board's current tick and compute the wall-clock instant for
     * tick 0. Non-fatal: if the logging module is absent or the read times
     * out, the reference stays `null`.
     */
    private suspend fun readLogTimeReference() {
        val response = runCatching { router.read(Module.LOGGING, 0x04) }.getOrNull() ?: return
        if (response.size < 6) return
        val tick = PacketParser.parseUInt32LE(response, 2)
        val msElapsed = tick.toDouble() * PacketParser.MS_PER_TICK
        logReferenceDate = Clock.System.now() - msElapsed.milliseconds
    }

    // ---- Disconnect handling ----

    private fun handleUnexpectedDisconnect(error: Throwable) {
        _state.value = DeviceState.Disconnected
        activeStreamKeys.clear()
        activeFusionConfig = null
        logReferenceDate = null
        onUnexpectedDisconnect?.invoke(error)
    }

    // ---- Sensor conflict detection ----

    /**
     * Throws if the signal being added would conflict with active streams.
     * Rule: sensor fusion and individual IMU sensors are mutually exclusive.
     */
    private fun checkSensorConflict(key: ActiveStreamKey) {
        if (key in activeStreamKeys) {
            throw MetaWearException.InvalidState(
                "${key.module.name.lowercase()} register 0x${key.dataRegister.toString(16)} is already streaming",
            )
        }
        val imuModules = setOf(Module.ACCELEROMETER, Module.GYRO, Module.MAGNETOMETER)
        val activeModules = activeStreamKeys.map { it.module }.toSet()
        val addingFusion = key.module == Module.SENSOR_FUSION
        val addingImu = key.module in imuModules

        if (!addingFusion && key.module in activeModules) {
            throw MetaWearException.InvalidState("${key.module.name.lowercase()} is already streaming")
        }
        if (addingFusion && activeModules.intersect(imuModules).isNotEmpty()) {
            val active = activeModules.intersect(imuModules).joinToString(", ") { it.name.lowercase() }
            throw MetaWearException.InvalidState("Cannot start sensor fusion while $active is already streaming")
        }
        if (addingImu && Module.SENSOR_FUSION in activeModules) {
            throw MetaWearException.InvalidState(
                "Cannot stream ${key.module.name.lowercase()} while sensor fusion is active",
            )
        }
    }
}
