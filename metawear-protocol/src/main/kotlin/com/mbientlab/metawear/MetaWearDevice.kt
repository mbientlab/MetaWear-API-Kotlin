package com.mbientlab.metawear

import com.mbientlab.metawear.model.BoardState
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
import com.mbientlab.metawear.sensor.Debug
import com.mbientlab.metawear.sensor.eraseAllMacros
import com.mbientlab.metawear.sensor.removeAllEvents
import com.mbientlab.metawear.sensor.removeAllProcessors
import com.mbientlab.metawear.transport.BleTransport
import com.mbientlab.metawear.transport.Uuids
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

// Port of MetaWearDevice.swift — connection, state machine, streaming,
// commands, one-shot reads, polling, factory reset, and board-state
// capture/restore. The logging + download surface (startLogging, downloadLogs,
// clearLog, logger recovery, …) lives in DeviceLogging.kt as extension
// functions over the internal hooks exposed below.

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
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val router = ProtocolRouter(transport, scope)

    // ---- Public state ----

    /** Backing state flow. Internal so DeviceLogging.kt can drive transitions. */
    internal val _state = MutableStateFlow<DeviceState>(DeviceState.Disconnected)

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

    /**
     * Serializes stream/log start/stop so two sensors' command sequences can't
     * interleave. Internal so DeviceLogging.kt shares the same critical section.
     */
    internal val opMutex = Mutex()

    /**
     * Logger IDs assigned by the board per registered signal key, with the byte
     * count of each chunk. Written by `startLogging` (DeviceLogging.kt), read
     * during download to reassemble chunk entries into full samples.
     * Intentionally preserved across unexpected disconnects — the board may
     * still hold the flash entries, so a reconnect can download without
     * re-registering.
     */
    internal val loggerRegistry = mutableMapOf<String, List<LoggerChunk>>()

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

    /**
     * Send a command that is expected to reboot the board or otherwise drop the
     * BLE link (`Debug.Reset`, `Debug.JumpToBootloader`, …), then wait for the
     * drop and converge on [DeviceState.Disconnected] without firing
     * [onUnexpectedDisconnect].
     *
     * If the drop doesn't arrive within [timeout], the connection is torn down
     * locally as a fallback so the device still converges on disconnected.
     *
     * The Swift original races an `AsyncStream` drop signal against a sleep in
     * a task group; here the one-shot signal is a [CompletableDeferred] awaited
     * under [withTimeoutOrNull].
     */
    suspend fun sendExpectingDisconnect(command: Command, timeout: Duration = 5.seconds) {
        // Swap the unexpected-disconnect hook for a one-shot signal BEFORE
        // sending, so a fast reboot can't race the handler installation.
        val dropSignal = CompletableDeferred<Unit>()
        router.setDisconnectHandler { dropSignal.complete(Unit) }
        try {
            send(command)
        } catch (e: Throwable) {
            // The command never went out — restore normal disconnect handling
            // so a later real drop still reaches onUnexpectedDisconnect.
            router.setDisconnectHandler(::handleUnexpectedDisconnect)
            throw e
        }
        val dropped = withTimeoutOrNull(timeout) { dropSignal.await() } != null
        if (dropped) {
            // Link already gone; converge local state the way disconnect() does.
            router.clearDisconnectHandler()
            router.stop()
        } else {
            runCatching { disconnect() }
        }
        _state.value = DeviceState.Disconnected
        activeStreamKeys.clear()
        activeFusionConfig = null
        logReferenceDate = null
    }

    // ---- Factory reset ----

    /**
     * Scrub all on-device runtime state and reboot the board.
     *
     * Equivalent to the C-API call sequence:
     * ```
     * mbl_mw_logging_stop(board);
     * mbl_mw_logging_clear_entries(board);
     * mbl_mw_event_remove_all(board);
     * mbl_mw_dataprocessor_remove_all(board);
     * mbl_mw_macro_erase_all(board);
     * mbl_mw_debug_reset_after_gc(board);
     * ```
     *
     * The wire sequence (write-without-response, in order) is:
     * 1. `[0x0B, 0x01, 0x00]` — stop logging
     * 2. `[0x0B, 0x09, 0xFF, 0xFF, 0xFF, 0xFF]` — drop all log entries
     * 3. `[0x0B, 0x0A]` — remove all logger triggers
     * 4. `[0x0A, 0x05]` — remove all event bindings
     * 5. `[0x09, 0x08]` — remove all data processors
     * 6. `[0x0F, 0x08]` — erase all macros
     * 7. `[0xFE, 0x05]` — reset after garbage collection (preferred reboot trigger)
     * 8. `[0xFE, 0x01]` — immediate reset (fallback: some firmware revisions —
     *    notably MMS fw 1.5.0 — silently ignore `[0xFE, 0x05]` if the board has
     *    nothing pending in flash GC, leaving the resetUID unincremented and the
     *    boot counter unchanged. Step 8 forces the reboot. If step 7 already
     *    triggered a reset, the BLE link is gone and step 8 is dropped, which is
     *    exactly what we want.)
     *
     * After steps 7-8 the BLE link drops and the device transitions to
     * [DeviceState.Disconnected]. [onUnexpectedDisconnect] is suppressed because
     * this disconnect is intentional. Call [connect] (or [reconnect]) again
     * after a short delay (~1s) to bring the device back up.
     *
     * Active timers and currently-streaming sensor outputs aren't stopped
     * explicitly — the reboot in step 7 clears all volatile state, including
     * sensor output enables and timer handles, so they're swept up automatically.
     *
     * @throws MetaWearException.InvalidState if the device is already
     *   disconnected, or any underlying transport error if a write fails before
     *   the reset command lands. Once a write fails the sequence aborts —
     *   partial resets are possible but rare in practice (each step is a single
     *   write).
     */
    suspend fun factoryReset() {
        if (_state.value == DeviceState.Disconnected) {
            throw MetaWearException.InvalidState("Cannot factory-reset a disconnected device")
        }

        // Suppress the unexpected-disconnect callback: the reset we're about to
        // trigger will drop BLE, but it's intentional, not an unexpected drop.
        router.clearDisconnectHandler()

        // 1. Stop active logging. Use a raw write rather than `stopLogging(...)`
        //    because that overload requires a specific Loggable handle, and we
        //    don't (and shouldn't) need to know which sensors are running.
        router.write(Packet.command(Module.LOGGING, 0x01, 0x00))

        // 2. Drop all log entries from flash. Mirrors `mbl_mw_logging_clear_entries`.
        router.write(Packet.command(Module.LOGGING, 0x09, 0xFF, 0xFF, 0xFF, 0xFF))

        // 3. Remove all logger triggers (subscriptions assigned via [0x0B, 0x02, ...]).
        router.write(Packet.command(Module.LOGGING, 0x0A))

        // 4. Remove all event bindings. Mirrors `mbl_mw_event_remove_all`.
        removeAllEvents()

        // 5. Remove all data processors. Mirrors `mbl_mw_dataprocessor_remove_all`.
        removeAllProcessors()

        // 6. Erase all macros. Mirrors `mbl_mw_macro_erase_all`.
        eraseAllMacros()

        // 7. Reset after GC — the firmware finishes garbage collection of the
        //    flash regions we just freed, then reboots. BLE drops momentarily.
        send(Debug.ResetAfterGc())

        // 8. Immediate reset fallback — see the doc comment above. On MMS
        //    firmware revisions where step 7 is a no-op, this guarantees the
        //    reboot. We swallow any error here because the link may already
        //    be gone (which is what success looks like).
        runCatching { send(Debug.Reset()) }

        // Local cleanup. The wire side is done; the link will drop on the
        // next BLE event. Tear down the protocol layer and zero out the in-
        // memory caches that won't survive the reboot.
        router.stop()
        _state.value = DeviceState.Disconnected
        activeStreamKeys.clear()
        activeFusionConfig = null
        loggerRegistry.clear()
        logReferenceDate = null
        // deviceInfo and `modules` describe immutable hardware — preserved so
        // the caller can decide whether to reuse them after `reconnect()`.
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

    // ---- Internal hooks for module extension functions ----
    //
    // Port of the Swift internal MetaWearDevice extension surface that module
    // files (Timer, Event, Macro, GPIO, Serial, DataProcessor, …) build on.

    /** Write raw bytes to the command characteristic (write-without-response). */
    internal suspend fun writeRaw(data: ByteArray) = router.write(data)

    /** Write raw bytes using write-with-response (macro commands). */
    internal suspend fun writeMacroRaw(data: ByteArray) = router.writeMacro(data)

    /**
     * Write [command] and await a bit-7 response on `(awaitModule, awaitRegister)`.
     * Used by reads whose request and response registers differ (e.g. GPIO).
     */
    internal suspend fun sendRead(
        command: ByteArray,
        awaitModule: Module,
        awaitRegister: Int,
        timeout: Duration = ProtocolRouter.READ_TIMEOUT,
    ): ByteArray = router.writeAndRead(command, awaitModule, awaitRegister, timeout)

    /**
     * Write [command] and await a plain (non-read-bit) notification — I2C/SPI
     * reads respond with an unsolicited data packet.
     */
    internal suspend fun sendAndAwaitNotification(
        command: ByteArray,
        awaitModule: Module,
        awaitRegister: Int,
    ): ByteArray = router.writeAndAwaitNotification(command, awaitModule, awaitRegister)

    /** Subscribe to notifications from `(module, register)`. */
    internal fun subscribeRaw(module: Module, register: Int): Flow<ByteArray> =
        router.subscribe(module, register)

    /** Remove a `(module, register)` subscription. */
    internal fun unsubscribeRaw(module: Module, register: Int) =
        router.unsubscribe(module, register)

    // ---- Module info convenience ----

    /** Discovery info for one module, or `null` if absent from the last discovery. */
    fun moduleInfo(module: Module): ModuleInfo? = modules[module]

    val hasGyroscope: Boolean get() = modules[Module.GYRO]?.isPresent ?: false
    val hasMagnetometer: Boolean get() = modules[Module.MAGNETOMETER]?.isPresent ?: false
    val hasBarometer: Boolean get() = modules[Module.BAROMETER]?.isPresent ?: false
    val hasSensorFusion: Boolean get() = modules[Module.SENSOR_FUSION]?.isPresent ?: false

    // ---- Board state (serialize / deserialize) ----

    /**
     * Capture the current board state for persistence. Call after [connect] has
     * completed at least once. Returns `null` if initialization has not yet run.
     */
    fun captureBoardState(): BoardState? {
        val info = deviceInfo ?: return null
        return BoardState(
            deviceInformation = info,
            modules = Module.entries.mapNotNull { modules[it] },
            logReferenceDate = logReferenceDate,
        )
    }

    /**
     * Restore a previously-captured state to skip module discovery on the next
     * connect. Must be called while the device is [DeviceState.Disconnected].
     *
     * The caller is responsible for verifying firmware/hardware compatibility
     * via [BoardState.isCompatible] before calling — this method performs no
     * validation of its own beyond state.
     */
    fun restoreBoardState(state: BoardState) {
        if (_state.value != DeviceState.Disconnected) {
            throw MetaWearException.OperationFailed("restoreBoardState requires disconnected state")
        }
        deviceInfo = state.deviceInformation
        modules = state.modulesByOpcode
        logReferenceDate = state.logReferenceDate
    }

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
        // loggerRegistry is intentionally preserved — the device may still have
        // active loggers. After reconnect the caller can download without re-starting.
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
