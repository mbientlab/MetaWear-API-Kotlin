package com.mbientlab.metawear

import com.mbientlab.metawear.model.ActiveLogger
import com.mbientlab.metawear.model.ActiveProcessor
import com.mbientlab.metawear.model.Download
import com.mbientlab.metawear.model.LoggedSample
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Loggable
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser
import com.mbientlab.metawear.protocol.PolledLogger
import com.mbientlab.metawear.protocol.PolledLoggerHandles
import com.mbientlab.metawear.sensor.Event
import com.mbientlab.metawear.sensor.EventAction
import com.mbientlab.metawear.sensor.EventSource
import com.mbientlab.metawear.sensor.MetaWearTimer
import com.mbientlab.metawear.sensor.ProcessorHandle
import com.mbientlab.metawear.sensor.createEvent
import com.mbientlab.metawear.sensor.createTimer
import com.mbientlab.metawear.sensor.removeEvent
import com.mbientlab.metawear.sensor.removeTimer
import com.mbientlab.metawear.sensor.startTimer
import com.mbientlab.metawear.sensor.stopTimer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

// Port of the logging half of MetaWearDevice.swift: startLogging/stopLogging
// (streamed and polled), raw + typed + processor-handle downloads, clearLog,
// flushLogPage, active-logger/processor enumeration, and logger recovery.
// Implemented as extension functions over the internal hooks in
// MetaWearDevice.kt so the device file stays readable.

// ---- Logging register map (module 0x0B) ----

private const val LOG_ENABLE = 0x01
private const val LOG_TRIGGER = 0x02
private const val LOG_LENGTH = 0x05
private const val LOG_READOUT = 0x06
private const val LOG_READOUT_NOTIFY = 0x07
private const val LOG_READOUT_PROGRESS = 0x08
private const val LOG_REMOVE_ENTRIES = 0x09
private const val LOG_REMOVE_ALL_TRIGGERS = 0x0A
private const val LOG_CIRCULAR_BUFFER = 0x0B
private const val LOG_READOUT_PAGE_COMPLETED = 0x0D
private const val LOG_READOUT_PAGE_CONFIRM = 0x0E
private const val LOG_FLUSH_PAGE = 0x10

/** One registered logger chunk: the board-assigned ID plus its byte count. */
internal data class LoggerChunk(val id: Int, val byteCount: Int)

/**
 * Timeout for slot-enumeration probes ([queryActiveLoggers] /
 * [queryActiveProcessors]). The firmware does not respond at all for an empty
 * slot, so every enumeration *ends* with one timed-out probe — at the default
 * 5 s read timeout that put a 5-second stall in every connect/recovery flow.
 * 1 s is still ~20× a typical connection interval, so a populated slot's
 * response can't realistically miss it.
 */
internal val PROBE_TIMEOUT: Duration = 1.seconds

/**
 * How long a download may go without any BLE activity (raw entries,
 * page-completed notices, or progress updates) before it is aborted with
 * [MetaWearException.Timeout]. Without this, a firmware that stops sending
 * progress (drained battery, radio glitch) leaves `downloadLogs()` suspended
 * forever.
 */
internal val DOWNLOAD_INACTIVITY_TIMEOUT: Duration = 60.seconds

// ---- Raw log entry ----

/**
 * A single 8-byte on-device flash entry returned during log download.
 * Port of `RawLogEntry` (Swift).
 */
data class RawLogEntry(
    /** Logger ID that produced this 4-byte chunk. */
    val id: Int,
    /** Reset epoch marker carried in the upper three bits of byte 0. */
    val resetUID: Int,
    /** Device tick count since reset. */
    val tick: Long,
    /** Raw 32-bit little-endian payload captured from flash. */
    val rawData: Long,
) {
    /** Elapsed milliseconds since the MetaWear last reset (tick × ms/tick). */
    val epochMs: Double get() = tick.toDouble() * PacketParser.MS_PER_TICK

    companion object {
        private const val HEADER_LEN = 2 // [module, register]
        private const val ENTRY_LEN = 9

        /** Parse one entry from raw entry bytes (without the 2-byte BLE notification header). */
        fun fromEntryBytes(entryBytes: ByteArray): RawLogEntry {
            val e = PacketParser.parseLogEntry(entryBytes)
            return RawLogEntry(id = e.id, resetUID = e.resetUID, tick = e.tick, rawData = e.rawData)
        }

        /** Parse all log entries from a single BLE notification packet (1 or 2 entries per packet). */
        fun parseAll(notification: ByteArray): List<RawLogEntry> {
            // Entry layout: 1 byte id/resetUID + 4 byte tick (LE) + 4 byte data
            // = 9 bytes — see `PacketParser.parseLogEntry`. A paired BLE
            // notification is therefore 2 (header) + 9 + 9 = 20 bytes; a
            // single-entry one is 11.
            val result = mutableListOf<RawLogEntry>()
            if (notification.size >= HEADER_LEN + ENTRY_LEN) {
                result.add(fromEntryBytes(notification.copyOfRange(HEADER_LEN, notification.size)))
            }
            if (notification.size >= HEADER_LEN + ENTRY_LEN + ENTRY_LEN) {
                result.add(fromEntryBytes(notification.copyOfRange(HEADER_LEN + ENTRY_LEN, notification.size)))
            }
            return result
        }
    }
}

// ---- Logging (streamed sensors) ----

/**
 * Start logging a streamable sensor to on-device flash.
 *
 * Multiple distinct sensors may be registered in one logging session by
 * calling this while the device is already [DeviceState.Logging]. Each signal
 * is split into firmware-sized chunks and registered under `loggerKey` for
 * later typed download via [downloadLogs].
 */
suspend fun <S> MetaWearDevice.startLogging(loggable: Loggable<S>): Unit = opMutex.withLock {
    // Allow stacking — multiple distinct sensors can be added to one logging
    // session by calling this once per sensor while the device is already in
    // `Logging`. But the same signal cannot be subscribed twice: doing so
    // would allocate a duplicate logger-ID on flash, leak the first
    // subscription's chunk entries, and silently overwrite
    // `loggerRegistry[loggerKey]`. Mirrors the conflict check in
    // `checkSensorConflict` for the streaming path, and the duplicate-key
    // guard in the processor-handle `startLogging(handle, key)` overload.
    when (state.value) {
        DeviceState.Idle, DeviceState.Logging -> Unit
        else -> throw MetaWearException.InvalidState("Device must be idle or already logging")
    }
    if (loggerRegistry.containsKey(loggable.loggerKey)) {
        throw MetaWearException.InvalidState("${loggable.loggerKey} is already being logged")
    }
    val priorState = state.value
    _state.value = DeviceState.Logging
    try {
        // Cold-boot warmup (e.g. BMM150 SLEEP-then-settle). Must precede
        // configureCommands — without this the magnetometer silently produces
        // zero samples on a freshly-powered MetaMotion (matches the streaming
        // path). See `Streamable.warmupCommands`.
        for (cmd in loggable.warmupCommands) if (cmd.isNotEmpty()) writeRaw(cmd)
        if (loggable.warmupDelayNanos > 0) delay(loggable.warmupDelayNanos.nanoseconds)

        // Configure sensor hardware
        for (cmd in loggable.configureCommands) writeRaw(cmd)

        // Subscribe each data chunk to the logger and collect the assigned IDs.
        // Command:  [0x0B, 0x02, module, register, 0xFF, ((length-1)<<5 | offset)]
        // Response: [0x0B, 0x02, logger_id]   ← plain notification, NOT a read
        //                                       response (high bit stays clear)
        //
        // Must use `sendAndAwaitNotification` here, not `sendRead` — the proto
        // layer's read waiters only fire on responses with bit-7 set on the
        // register byte, but the firmware's TRIGGER_DATA_PROC reply comes back
        // as a plain notification.
        //
        // Encoding matches MetaWear-SDK-Cpp datasignal.cpp `get_data_ubyte()`
        // (`((length() - 1) << 5) | offset`) and logging.cpp:868
        // (`((entry_size - 1) << 5) | entry_offset`). The packed byte is
        // decoded back the same way in `queryActiveLoggers()` below.
        val chunks = mutableListOf<LoggerChunk>()
        for (chunk in loggable.logDataChunks) {
            val packedByte = ((chunk.length - 1) shl 5) or chunk.offset
            val cmd = Packet.command(
                Module.LOGGING, LOG_TRIGGER,
                loggable.module.value, loggable.dataRegister, 0xFF, packedByte,
            )
            val response = sendAndAwaitNotification(cmd, awaitModule = Module.LOGGING, awaitRegister = LOG_TRIGGER)
            if (response.size < 3) {
                throw MetaWearException.OperationFailed("Logger subscription returned short response")
            }
            chunks.add(LoggerChunk(id = response[2].toInt() and 0xFF, byteCount = chunk.length))
        }
        loggerRegistry[loggable.loggerKey] = chunks

        // Enable sensor output and start hardware
        for (cmd in loggable.enableCommands) if (cmd.isNotEmpty()) writeRaw(cmd)
        for (cmd in loggable.startCommands) if (cmd.isNotEmpty()) writeRaw(cmd)

        // Enable circular buffer and start logging
        writeRaw(Packet.command(Module.LOGGING, LOG_CIRCULAR_BUFFER, 0x01)) // circular buffer on
        writeRaw(Packet.command(Module.LOGGING, LOG_ENABLE, 0x01))         // enable logging
    } catch (e: Throwable) {
        // Roll back — the logging session never started. Loggers already
        // subscribed on the board are orphaned (cleared by `clearLog()` /
        // `factoryReset()`), but the local state must not claim an active
        // session, or every retry fails with "already being logged".
        loggerRegistry.remove(loggable.loggerKey)
        _state.value = priorState
        throw e
    }
}

/**
 * Stop sensor sampling for a loggable signal and stop the logging module.
 *
 * Logger IDs stay in the local registry so [downloadLogs] can decode entries
 * afterward. Call [clearLog] after a successful download to remove the
 * board-side log entries and subscriptions.
 */
suspend fun <S> MetaWearDevice.stopLogging(loggable: Loggable<S>): Unit = opMutex.withLock {
    // No guard on `state == Logging`: the first call in a multi-sensor stop
    // sequence drops state to `Idle`, but each subsequent sensor still needs
    // its own stop + disable writes — otherwise the board keeps sampling that
    // sensor (and `downloadLogs` returns no entries because the logger never
    // sees a fresh session marker).
    writeRaw(Packet.command(Module.LOGGING, LOG_ENABLE, 0x00)) // stop logging
    for (cmd in loggable.stopCommands) if (cmd.isNotEmpty()) writeRaw(cmd)
    for (cmd in loggable.disableCommands) if (cmd.isNotEmpty()) writeRaw(cmd)
    _state.value = DeviceState.Idle
}

// ---- Polled-readable logging ----

/**
 * Start logging a read-only ([com.mbientlab.metawear.protocol.Readable])
 * sensor by pairing an on-board timer with an event that triggers the read,
 * then subscribing a logger to the read response. The board fires reads at
 * `logger.periodMs` intervals and writes each result to flash without host
 * involvement — keeps working across disconnects, app close, etc.
 *
 * @return The board-allocated `timerID`, `eventID`, and logger IDs. Persist
 *   these so a later `stopLogging(logger, handles)` can dismantle the chain
 *   and `recoverLoggers(logger)` can rebuild the registry after a fresh app
 *   launch.
 */
suspend fun <S> MetaWearDevice.startLogging(logger: PolledLogger<S>): PolledLoggerHandles = opMutex.withLock {
    when (state.value) {
        DeviceState.Idle, DeviceState.Logging -> Unit
        else -> throw MetaWearException.InvalidState("Device must be idle or already logging")
    }
    // Same rationale as the `Loggable` overload: a duplicate subscription
    // would orphan the first logger ID on flash and clobber the registry
    // entry, making download incomplete. The check is before the state write
    // so a rejected duplicate leaves the device in its prior state intact.
    if (loggerRegistry.containsKey(logger.loggerKey)) {
        throw MetaWearException.InvalidState("${logger.loggerKey} is already being logged")
    }
    val priorState = state.value
    _state.value = DeviceState.Logging
    try {
        // 1. Create the on-board timer that drives the reads.
        val timer = createTimer(periodMs = logger.periodMs)

        // 2. Record an event: when the timer fires, execute the readable's
        //    readCommand. The board issues the read internally; the response
        //    flows back out through the readable's data register as a normal
        //    notification, where the logger picks it up below.
        val readData = logger.readable.readCommand
        // Force the SILENT bit (0x40) onto the read register: silent responses
        // route to the board's internal data path — which is what the logger
        // taps. A loud read (0x80 only) goes out over BLE to the host and the
        // logger captures nothing (verified on MMS firmware 1.7.2).
        val action = EventAction(
            module = Module.from(readData[0].toInt() and 0xFF) ?: Module.DEBUG,
            register = (if (readData.size > 1) readData[1].toInt() and 0xFF else 0) or 0x40,
            params = if (readData.size > 2) readData.copyOfRange(2, readData.size) else ByteArray(0),
        )
        val event = createEvent(source = EventSource.timerFired(timer), action = action)

        // 3. Subscribe a logger per chunk on the readable's data register.
        //    Identical wire pattern to Loggable subscription (see
        //    `startLogging(loggable)` above for the protocol detail). Logger
        //    ID is returned in the reply notification.
        //
        //    The trigger names the readable's FULL register byte (read +
        //    silent bits, e.g. temperature = 0xC1) and its channel index —
        //    matching how the C++ SDK builds logger triggers from the signal
        //    header. A bare register byte (0x01) with index 0xFF never matches
        //    the silent read responses and the log stays empty.
        val chunks = mutableListOf<LoggerChunk>()
        for (chunk in logger.readable.logDataChunks) {
            val packedByte = ((chunk.length - 1) shl 5) or chunk.offset
            val cmd = Packet.command(
                Module.LOGGING, LOG_TRIGGER,
                logger.readable.module.value,
                logger.readable.loggerTriggerRegister,
                logger.readable.loggerTriggerIndex,
                packedByte,
            )
            val response = sendAndAwaitNotification(cmd, awaitModule = Module.LOGGING, awaitRegister = LOG_TRIGGER)
            if (response.size < 3) {
                throw MetaWearException.OperationFailed("Polled logger subscription returned short response")
            }
            chunks.add(LoggerChunk(id = response[2].toInt() and 0xFF, byteCount = chunk.length))
        }
        loggerRegistry[logger.loggerKey] = chunks

        // 4. Enable the logging module + circular buffer, then kick off the
        //    timer. Order matters — start the timer last so the first read
        //    fires into a ready logger.
        writeRaw(Packet.command(Module.LOGGING, LOG_CIRCULAR_BUFFER, 0x01))
        writeRaw(Packet.command(Module.LOGGING, LOG_ENABLE, 0x01))
        startTimer(timer)

        PolledLoggerHandles(
            timerID = timer.id,
            eventID = event.id,
            loggerIDs = chunks.map { it.id },
        )
    } catch (e: Throwable) {
        // Roll back — the polled session never started. Board-side resources
        // allocated before the failure (timer, event, loggers) are orphaned;
        // `clearLog()`/`factoryReset()` reclaims them.
        loggerRegistry.remove(logger.loggerKey)
        _state.value = priorState
        throw e
    }
}

/**
 * Tear down a polled logger: stop and remove the timer, remove the event
 * binding, stop the logging module. Logger registrations stay in the registry
 * so a subsequent [downloadLogs] can drain the flash entries before they're
 * cleared.
 */
suspend fun <S> MetaWearDevice.stopLogging(
    logger: PolledLogger<S>,
    handles: PolledLoggerHandles,
): Unit = opMutex.withLock {
    // Same reasoning as the Loggable overload: don't gate on
    // `state == Logging` because the first sensor stopped in a multi-sensor
    // session has already moved state to Idle.
    val timer = MetaWearTimer(
        id = handles.timerID,
        periodMs = logger.periodMs,
        repetitions = MetaWearTimer.INFINITE,
        immediate = false,
    )
    // Best-effort teardown — if a single sub-step fails we still want to try
    // the others rather than leaving half a chain on the board.
    runCatching { stopTimer(timer) }
    runCatching { removeTimer(timer) }
    runCatching { removeEvent(Event(id = handles.eventID)) }
    writeRaw(Packet.command(Module.LOGGING, LOG_ENABLE, 0x00))
    _state.value = DeviceState.Idle
}

/**
 * Drain the typed log stream for a polled logger. Reuses the existing
 * closure-based `downloadLogs(key, decode)` so all the chunk-reassembly logic
 * stays in one place.
 */
suspend fun <S> MetaWearDevice.downloadLogs(
    logger: PolledLogger<S>,
): Flow<Download<List<LoggedSample<S>>>> {
    val readable = logger.readable
    return downloadLogs(key = logger.loggerKey) { data -> readable.parseLogSample(data) }
}

/**
 * Refresh the logger registry for a polled logger by matching the board's
 * active loggers against the readable's module + data register. Used after
 * app restart when the registry was lost but the board's timer + event +
 * logger are still running.
 */
suspend fun <S> MetaWearDevice.recoverLoggers(logger: PolledLogger<S>) {
    recoverLoggers(logger, queryActiveLoggers())
}

/**
 * Variant of the polled-logger [recoverLoggers] that matches against a
 * pre-fetched enumeration — see the [Loggable] overload for rationale.
 */
fun <S> MetaWearDevice.recoverLoggers(logger: PolledLogger<S>, active: List<ActiveLogger>) {
    // The board echoes the trigger's FULL register byte (read + silent bits) —
    // normalize both sides so 0xC1 matches dataRegister 0x01.
    val matched = active
        .filter {
            it.module == logger.readable.module &&
                (it.register and 0x3F) == (logger.readable.dataRegister and 0x3F) &&
                it.channel == logger.readable.loggerTriggerIndex
        }
        .sortedBy { it.loggerID }
    if (matched.isEmpty()) {
        throw MetaWearException.OperationFailed(
            "No active polled logger found for '${logger.loggerKey}' on " +
                "${logger.readable.module.name.lowercase()}/${logger.readable.dataRegister}",
        )
    }
    loggerRegistry[logger.loggerKey] = matched.zip(logger.readable.logDataChunks) { activeLogger, chunk ->
        LoggerChunk(id = activeLogger.loggerID, byteCount = chunk.length)
    }
}

// ---- Download ----

/**
 * Download raw log entries from the device. Returns a flow of progress
 * snapshots, each containing all entries received so far. The readout starts
 * immediately (mirroring the Swift `AsyncThrowingStream` semantics) and
 * snapshots buffer until collected; cancelling the collection aborts the
 * readout.
 *
 * On MMS boards (logging revision ≥ 3) the firmware buffers the active log
 * page in RAM and only commits to flash when the page fills, so a short
 * session (a few seconds at low ODR — small enough that the page never
 * completes before stop) leaves its samples stranded. `LOG_LENGTH` then reads
 * 0 and the download finishes empty even though the sensor produced data. We
 * force-flush the active page here so that workflow shape always works
 * without the caller having to remember [flushLogPage]. The flush is a no-op
 * on MMRL (logging revision < 3).
 */
suspend fun MetaWearDevice.downloadLogs(): Flow<Download<List<RawLogEntry>>> = opMutex.withLock {
    if (state.value != DeviceState.Idle) {
        throw MetaWearException.InvalidState("Device must be idle to download")
    }
    _state.value = DeviceState.Downloading(progress = 0.0)

    try {
        // Force-flush any partial page sitting in RAM so the LOG_LENGTH read
        // below reflects every captured sample. Idempotent — safe to call even
        // if the user already invoked `flushLogPage()` explicitly. No-op on
        // pre-MMS firmware (revision < 3).
        flushLogPage()

        // Enable readout-notify and progress channels, then read the entry count.
        writeRaw(Packet.command(Module.LOGGING, LOG_READOUT_NOTIFY, 0x01))         // enable readout notify
        writeRaw(Packet.command(Module.LOGGING, LOG_READOUT_PAGE_COMPLETED, 0x01)) // enable page-completed
        writeRaw(Packet.command(Module.LOGGING, LOG_READOUT_PROGRESS, 0x01))       // enable progress

        val rawFlow = subscribeRaw(Module.LOGGING, LOG_READOUT_NOTIFY)
        val progressFlow = subscribeRaw(Module.LOGGING, LOG_READOUT_PROGRESS)
        val pageFlow = subscribeRaw(Module.LOGGING, LOG_READOUT_PAGE_COMPLETED)

        // Read entry count, then start the download
        val lengthResponse = sendRead(
            command = Packet.read(Module.LOGGING, LOG_LENGTH),
            awaitModule = Module.LOGGING,
            awaitRegister = LOG_LENGTH,
        )
        if (lengthResponse.size < 6) {
            throw MetaWearException.OperationFailed("Log length response too short")
        }
        val nEntries = PacketParser.parseUInt32LE(lengthResponse, 2)

        // Empty log buffer: short-circuit. Issuing the readout with count=0
        // produces no `0x07` raw entries, no `0x0D` page-completed notice, and
        // no `0x08` progress update — `runDownload` would block on the
        // progress flow forever. Yield a single 100% snapshot with no data and
        // finish.
        if (nEntries == 0L) {
            cleanupLogDownloadSetup()
            _state.value = DeviceState.Idle
            return flowOf(
                Download(data = emptyList(), percentComplete = 1.0, totalEntries = 0, entriesDownloaded = 0),
            )
        }

        // Readout: [0x0B, 0x06, n_entries(4 LE), n_notify(4 LE)]
        // n_notify = 0 means one progress update per page.
        writeRaw(Packet.command(Module.LOGGING, LOG_READOUT, PacketParser.le32(nEntries) + PacketParser.le32(0)))

        val channel = Channel<Download<List<RawLogEntry>>>(Channel.UNLIMITED)
        val downloadJob = scope.launch {
            runDownload(rawFlow, progressFlow, pageFlow, nEntries, channel)
        }
        return channel.receiveAsFlow().onCompletion { downloadJob.cancel() }
    } catch (e: Throwable) {
        cleanupLogDownloadSetup()
        _state.value = DeviceState.Idle
        throw e
    }
}

/**
 * Download and decode log entries for a specific loggable sensor. Requires
 * that `startLogging(loggable)` was called for this sensor in the same
 * session so that logger IDs are known.
 */
suspend fun <S> MetaWearDevice.downloadLogs(
    loggable: Loggable<S>,
): Flow<Download<List<LoggedSample<S>>>> {
    val chunks = loggerRegistry[loggable.loggerKey]
        ?: throw MetaWearException.InvalidState(
            "No logger registered for ${loggable.loggerKey}. Call startLogging first.",
        )
    return downloadLogs().map { progress ->
        Download(
            data = decodeEntries(progress.data, chunks) { loggable.parseLogSample(it) },
            percentComplete = progress.percentComplete,
            totalEntries = progress.totalEntries,
            entriesDownloaded = progress.entriesDownloaded,
        )
    }
}

// ---- Logging — processor handle ----
//
// Logging the *output* of a data processor (rather than a raw sensor) is a
// common pattern: throttle a 100 Hz fusion stream down to 1 Hz before
// committing it to flash, accumulate axis magnitudes, gate on a comparator,
// etc. The wire shape is the same as sensor logging — `[0x0B, 0x02, src_mod,
// src_reg, src_id, packed]` — except the source triple is the processor's
// NOTIFY register: `(0x09, 0x03, processorID)`.
//
// The caller passes a string `key` to identify the logger registration so it
// can be matched up with a `downloadLogs` call later. Sensor lifecycle
// (configure / enable / start / stop the source signal feeding the processor
// chain) is the caller's responsibility — `startLogging(handle, key)` only
// wires the processor output to flash.

/**
 * Start logging the output of a data processor.
 *
 * The handle's full output (per `nChannels × channelSize`) is split into
 * chunks of up to 4 bytes — the firmware's per-entry byte limit — and one
 * logger ID is allocated per chunk. IDs are stored under [key] so a later
 * `downloadLogs(key, decode)` call can reassemble the chunks back into full
 * samples.
 *
 * The caller must already have started the source sensor(s) feeding the
 * processor chain. This method does not touch any sensor lifecycle — it only
 * enables the logging module and circular buffer.
 *
 * Typical flow:
 * ```kotlin
 * // Configure + start the source sensor that feeds the processor chain.
 * val euler = SensorFusionEuler(mode = Mode.NDOF)
 * for (cmd in euler.configureCommands) device.writeRaw(cmd)
 * for (cmd in euler.enableCommands) device.writeRaw(cmd)
 * for (cmd in euler.startCommands) device.writeRaw(cmd)
 *
 * // Throttle euler to 1 Hz, log the throttled output.
 * val throttle = device.createProcessor(
 *     DataProcessor.Time(periodMs = 1000),
 *     source = SensorFusionEulerSignal(),
 * )
 * device.startLogging(throttle, key = "euler-1hz")
 *
 * // ...later...
 * device.stopLogging(key = "euler-1hz")
 * for (cmd in euler.stopCommands) device.writeRaw(cmd)
 * for (cmd in euler.disableCommands) device.writeRaw(cmd)
 * device.flushLogPage()
 *
 * val download = device.downloadLogs(key = "euler-1hz") { data ->
 *     PacketParser.parseEulerAngles(byteArrayOf(0x19, 0x08) + data)
 * }
 * download.collect { progress -> /* ... */ }
 * ```
 *
 * @param handle A processor handle returned from `createProcessor(config, source)`.
 * @param key A unique string under which to register the logger IDs. Use the
 *   same `key` later in `downloadLogs(key, decode)`.
 */
suspend fun MetaWearDevice.startLogging(handle: ProcessorHandle, key: String): Unit = opMutex.withLock {
    if (state.value != DeviceState.Idle) {
        throw MetaWearException.InvalidState("Device must be idle to log")
    }
    if (loggerRegistry.containsKey(key)) {
        throw MetaWearException.InvalidState("Logger key '$key' already registered")
    }
    _state.value = DeviceState.Logging
    try {
        // Slice the processor's output into <=4-byte chunks. The firmware's
        // per-flash-entry limit is 4 bytes (LOG_ENTRY_DATA_SIZE). Sample size
        // is nChannels × channelSize — e.g. 16 bytes for euler/quat
        // (4 × float32), 12 bytes for gravity/linear-acc (3 × float32).
        val totalLen = handle.dataLength
        val chunkOffsets = mutableListOf<Pair<Int, Int>>() // (offset, length)
        var pos = 0
        while (pos < totalLen) {
            val len = minOf(4, totalLen - pos)
            chunkOffsets.add(pos to len)
            pos += len
        }

        // Subscribe each chunk and collect the assigned logger IDs.
        // Wire:     [0x0B, 0x02, 0x09, 0x03, proc_id, packed]
        // Response: [0x0B, 0x02, logger_id]  ← plain notification (not a read
        //                                      response — high bit stays clear)
        // packed = ((length-1) << 5) | offset (matches C++ datasignal.cpp:162)
        val chunks = mutableListOf<LoggerChunk>()
        for ((offset, length) in chunkOffsets) {
            val packed = ((length - 1) shl 5) or offset
            val cmd = Packet.command(
                Module.LOGGING, LOG_TRIGGER,
                Module.DATA_PROCESSOR.value, 0x03, handle.id, packed,
            )
            val response = sendAndAwaitNotification(cmd, awaitModule = Module.LOGGING, awaitRegister = LOG_TRIGGER)
            if (response.size < 3) {
                throw MetaWearException.OperationFailed("Logger subscription returned short response")
            }
            chunks.add(LoggerChunk(id = response[2].toInt() and 0xFF, byteCount = length))
        }
        loggerRegistry[key] = chunks

        // Enable circular buffer and start logging.
        writeRaw(Packet.command(Module.LOGGING, LOG_CIRCULAR_BUFFER, 0x01))
        writeRaw(Packet.command(Module.LOGGING, LOG_ENABLE, 0x01))
    } catch (e: Throwable) {
        // Roll back — the session never started (guard above ensures the
        // prior state was Idle). Board-side loggers subscribed before the
        // failure are orphaned; `clearLog()` reclaims them.
        loggerRegistry.remove(key)
        _state.value = DeviceState.Idle
        throw e
    }
}

/**
 * Stop logging for a processor-handle registration.
 *
 * Disables the global logging module (mirrors `stopLogging(loggable)`) but
 * does not touch any source-sensor lifecycle — the caller is responsible for
 * stopping the source sensors that feed the processor chain.
 *
 * The logger registration under [key] is left in the registry so that
 * `downloadLogs(key, decode)` can still find the chunk IDs. Use [clearLog]
 * (which wipes the registry) once the download is complete.
 */
suspend fun MetaWearDevice.stopLogging(key: String): Unit = opMutex.withLock {
    if (state.value != DeviceState.Logging) return
    if (!loggerRegistry.containsKey(key)) {
        throw MetaWearException.InvalidState("No logger registered for key '$key'")
    }
    writeRaw(Packet.command(Module.LOGGING, LOG_ENABLE, 0x00))
    _state.value = DeviceState.Idle
}

/**
 * Download and decode log entries for a processor-handle registration.
 *
 * Requires that `startLogging(handle, key)` was called with the same [key]
 * (in this session, or in a previous one if the registry was rebuilt via
 * `recoverLoggers`). The user-supplied [decode] closure runs on the
 * reassembled chunk bytes — i.e. for a 16-byte euler signal the closure
 * receives all 16 bytes in chunk order.
 *
 * @param key The same string passed to `startLogging(handle, key)`.
 * @param decode A pure decoder run against the reassembled per-sample bytes.
 *   For built-in fusion signals, prepend a fake [module, register] header and
 *   call the matching [PacketParser] method (see [Loggable]'s default
 *   `parseLogSample`).
 */
suspend fun <S> MetaWearDevice.downloadLogs(
    key: String,
    decode: (ByteArray) -> S,
): Flow<Download<List<LoggedSample<S>>>> {
    val chunks = loggerRegistry[key]
        ?: throw MetaWearException.InvalidState(
            "No logger registered for '$key'. Call startLogging(handle, key) first.",
        )
    return downloadLogs().map { progress ->
        Download(
            data = decodeEntries(progress.data, chunks, decode),
            percentComplete = progress.percentComplete,
            totalEntries = progress.totalEntries,
            entriesDownloaded = progress.entriesDownloaded,
        )
    }
}

// ---- Log maintenance ----

/**
 * Clear all log entries from the device flash and remove all logger
 * subscriptions.
 *
 * Stops logging first (`[0x0B, 0x01, 0x00]`) because the firmware ignores
 * `CLEAR_ENTRIES` (`[0x0B, 0x09, …]`) and `REMOVE_ALL_LOGGERS`
 * (`[0x0B, 0x0A]`) while sampling is enabled — silently, without an error.
 * This matters for the orphan-log discard flow where the board can still be
 * actively logging at the moment we call this. Mirrors steps 1-3 of
 * `factoryReset()`.
 */
suspend fun MetaWearDevice.clearLog(): Unit = opMutex.withLock {
    if (state.value != DeviceState.Idle) {
        throw MetaWearException.InvalidState("Device must be idle to clear the log")
    }
    writeRaw(Packet.command(Module.LOGGING, LOG_ENABLE, 0x00))                          // stop logging
    writeRaw(Packet.command(Module.LOGGING, LOG_REMOVE_ENTRIES, 0xFF, 0xFF, 0xFF, 0xFF))
    writeRaw(Packet.command(Module.LOGGING, LOG_REMOVE_ALL_TRIGGERS))                   // remove all loggers
    loggerRegistry.clear()
}

/**
 * Flush the active logging page to flash so in-flight samples become readable.
 *
 * Only valid on MMS boards — firmware ignores this command on MMRL, so this
 * no-ops when the logging module revision is below 3 (`MMS_REVISION` in the
 * C++ SDK). Safe to call on any device.
 *
 * **You almost never need to call this directly.** [downloadLogs] (and every
 * overload built on it) auto-flushes before reading `LOG_LENGTH`, so the
 * standard `startLogging → stop → download` flow works without it. The
 * remaining use case is a non-download read of
 * [com.mbientlab.metawear.sensor.LogLength] mid-session where you want the
 * count to include the in-RAM partial page.
 *
 * Wire format: `[0x0B, 0x10, 0x01]`.
 *
 * @return `true` if the command was sent, `false` if the board is not MMS.
 */
suspend fun MetaWearDevice.flushLogPage(): Boolean {
    val info = moduleInfo(Module.LOGGING)
    if (info == null || info.revision < 3) return false
    writeRaw(Packet.command(Module.LOGGING, LOG_FLUSH_PAGE, 0x01))
    return true
}

// ---- Logger recovery ----

/**
 * Query the board for all currently active logger subscriptions. Returns one
 * entry per chunk (logger ID) in the order the board assigned them. Useful
 * for rebuilding the logger registry after an app restart.
 */
suspend fun MetaWearDevice.queryActiveLoggers(): List<ActiveLogger> {
    val result = mutableListOf<ActiveLogger>()
    for (id in 0 until 32) {
        // READ request for TRIGGER register: [0x0B, 0x82, logger_id]
        val response = try {
            sendRead(
                command = Packet.read(Module.LOGGING, LOG_TRIGGER, id),
                awaitModule = Module.LOGGING,
                awaitRegister = LOG_TRIGGER,
                timeout = PROBE_TIMEOUT,
            )
        } catch (e: MetaWearException.Timeout) {
            break // No more loggers at this ID
        }
        // Response: [0x0B, 0x82, source_module, source_register, source_data_id, packed_byte]
        // The firmware does NOT echo the logger_id back — the queried `id` IS
        // the logger ID. An empty slot is signalled by a short response or a
        // sentinel 0xFF in the source-module byte.
        if (response.size < 6) break
        val sourceModuleByte = response[2].toInt() and 0xFF
        if (sourceModuleByte == 0xFF) break
        val module = Module.from(sourceModuleByte) ?: continue
        val packed = response[5].toInt() and 0xFF
        // Low 5 bits = offset, high 3 bits = length-1.
        result.add(
            ActiveLogger(
                loggerID = id,
                module = module,
                register = response[3].toInt() and 0xFF,
                channel = response[4].toInt() and 0xFF,
                chunkOffset = packed and 0x1F,
                chunkLength = ((packed shr 5) and 0x7) + 1,
            ),
        )
    }
    return result
}

/**
 * Query the board for all currently-installed data processors. Returns one
 * entry per processor ID in the order the board reports them. Used to
 * reconstruct the processor graph behind an anonymous (replayed) signal.
 *
 * Response layout (firmware does NOT echo the processor_id):
 * `[0x09, 0x82, parent_module, parent_register, parent_proc_id_or_0xFF, packed, proc_type, config...]`
 * The queried `id` IS the processor ID.
 */
suspend fun MetaWearDevice.queryActiveProcessors(): List<ActiveProcessor> {
    val result = mutableListOf<ActiveProcessor>()
    for (id in 0 until 32) {
        val response = try {
            sendRead(
                command = Packet.read(Module.DATA_PROCESSOR, 0x02, id),
                awaitModule = Module.DATA_PROCESSOR,
                awaitRegister = 0x02,
                timeout = PROBE_TIMEOUT,
            )
        } catch (e: MetaWearException.Timeout) {
            break
        }
        // Need at least: header(2) + parent_mod + parent_reg + parent_proc_id + packed + proc_type
        if (response.size < 7) break
        val parentModByte = response[2].toInt() and 0xFF
        if (parentModByte == 0xFF) break
        val parentMod = Module.from(parentModByte) ?: continue
        val packed = response[5].toInt() and 0xFF
        result.add(
            ActiveProcessor(
                processorID = id,
                parentModule = parentMod,
                parentRegister = response[3].toInt() and 0xFF,
                parentProcessorID = response[4].toInt() and 0xFF,
                chunkOffset = packed and 0x1F,
                chunkLength = (packed shr 5) + 1,
                processorType = response[6].toInt() and 0xFF,
                configBytes = if (response.size > 7) {
                    response.drop(7).map { it.toInt() and 0xFF }
                } else {
                    emptyList()
                },
            ),
        )
    }
    return result
}

/**
 * Rebuild the logger registry for a sensor after reconnect (e.g. after app
 * restart). Queries the board for active loggers and matches them by
 * module + register. Safe to call even if the registry already has an entry —
 * it will be refreshed.
 *
 * Recovering several sensors? Enumerate once and use the `active:` overload —
 * each call of this convenience re-runs the slot enumeration (which always
 * ends with one timed-out probe).
 */
suspend fun <S> MetaWearDevice.recoverLoggers(loggable: Loggable<S>) {
    recoverLoggers(loggable, queryActiveLoggers())
}

/**
 * Variant of [recoverLoggers] that matches against a pre-fetched enumeration
 * from [queryActiveLoggers], so callers recovering multiple sensors pay for
 * the slot scan once.
 */
fun <S> MetaWearDevice.recoverLoggers(loggable: Loggable<S>, active: List<ActiveLogger>) {
    // Normalized register comparison (and 0x3F) so triggers created with
    // read/silent bits still match the loggable's bare data register.
    val matched = active
        .filter {
            it.module == loggable.module &&
                (it.register and 0x3F) == (loggable.dataRegister and 0x3F)
        }
        .sortedBy { it.loggerID }
    if (matched.isEmpty()) {
        throw MetaWearException.OperationFailed(
            "No active logger found for '${loggable.loggerKey}' on " +
                "${loggable.module.name.lowercase()}/${loggable.dataRegister}",
        )
    }
    loggerRegistry[loggable.loggerKey] = matched.zip(loggable.logDataChunks) { activeLogger, chunk ->
        LoggerChunk(id = activeLogger.loggerID, byteCount = chunk.length)
    }
}

// ---- Download internals ----

/** Thrown internally to unwind the progress collection once the readout completes. */
private class DownloadFinished : Exception()

private suspend fun MetaWearDevice.runDownload(
    rawFlow: Flow<ByteArray>,
    progressFlow: Flow<ByteArray>,
    pageFlow: Flow<ByteArray>,
    totalEntries: Long,
    channel: Channel<Download<List<RawLogEntry>>>,
) {
    val accumulatorLock = Any()
    val accumulator = mutableListOf<RawLogEntry>()
    fun snapshot(): List<RawLogEntry> = synchronized(accumulatorLock) { accumulator.toList() }

    // Activity tracking for the inactivity watchdog. A monotonically-
    // increasing bump counter (instead of the Swift actor's clock reads) keeps
    // the watchdog correct under both real dispatchers and runTest virtual time.
    var activityCounter = 0L
    fun bump() = synchronized(accumulatorLock) { activityCounter++ }

    // Yield an initial 0% snapshot so callers see the total entry count
    // immediately — useful for UIs that want to render "0 of N entries"
    // before the first firmware progress notification arrives. (Long
    // downloads can wait several seconds for that first page-complete notice,
    // which would otherwise leave the progress bar at 0% with no context.)
    channel.trySend(
        Download(data = emptyList(), percentComplete = 0.0, totalEntries = totalEntries, entriesDownloaded = 0),
    )

    // Drain raw-entry notifications in the background
    val entryJob = scope.launch {
        rawFlow.collect { packet ->
            bump()
            val parsed = runCatching { RawLogEntry.parseAll(packet) }.getOrDefault(emptyList())
            synchronized(accumulatorLock) { accumulator.addAll(parsed) }
        }
    }

    // Confirm each page-completed notification so the board sends the next page
    val pageJob = scope.launch {
        pageFlow.collect {
            bump()
            runCatching { writeRaw(Packet.command(Module.LOGGING, LOG_READOUT_PAGE_CONFIRM)) }
        }
    }

    // Inactivity watchdog. When the board goes silent on all three channels
    // for DOWNLOAD_INACTIVITY_TIMEOUT, unsubscribe the logging registers —
    // that finishes the flows cleanly, the progress loop below falls through,
    // and the fall-through path surfaces MetaWearException.Timeout to the
    // consumer.
    val watchdogJob = scope.launch {
        var lastSeen = synchronized(accumulatorLock) { activityCounter }
        var idle = Duration.ZERO
        while (true) {
            delay(5.seconds)
            val now = synchronized(accumulatorLock) { activityCounter }
            idle = if (now == lastSeen) idle + 5.seconds else Duration.ZERO
            lastSeen = now
            if (idle > DOWNLOAD_INACTIVITY_TIMEOUT) {
                unsubscribeRaw(Module.LOGGING, LOG_READOUT_NOTIFY)
                unsubscribeRaw(Module.LOGGING, LOG_READOUT_PROGRESS)
                unsubscribeRaw(Module.LOGGING, LOG_READOUT_PAGE_COMPLETED)
                return@launch
            }
        }
    }

    suspend fun finish(error: Throwable?) {
        entryJob.cancel()
        pageJob.cancel()
        watchdogJob.cancel()
        withContext(NonCancellable) {
            cleanupLogDownloadSetup()
            _state.value = DeviceState.Idle
        }
        channel.close(error)
    }

    try {
        progressFlow.collect { packet ->
            bump()
            // Firmware sends only [0x0B, 0x08, remaining(LE32)] — 6 bytes
            // total. Older drafts of the SDK assumed an 8-byte payload that
            // included `total`, but the C++ SDK actually caches `total` from
            // the earlier read of register 0x05 (see
            // `logging_response_readout_progress` in
            // metawear/core/cpp/logging.cpp). We pass `totalEntries` in via
            // the call site for the same reason.
            if (packet.size < 6) return@collect
            val remaining = PacketParser.parseUInt32LE(packet, 2)
            val downloaded = totalEntries - minOf(remaining, totalEntries)
            val percent = if (totalEntries > 0) downloaded.toDouble() / totalEntries else 1.0
            val snap = snapshot()
            channel.trySend(
                Download(
                    data = snap, percentComplete = percent,
                    totalEntries = totalEntries, entriesDownloaded = downloaded,
                ),
            )
            if (remaining == 0L) {
                // The firmware can deliver the trailing `0x07` raw-entry
                // packets in the same radio window as the final `0x08`
                // progress notice. The entry job runs concurrently and may
                // not have drained its buffered packets by the time we
                // observe `remaining == 0` here. Poll the accumulator until
                // it reaches the entry count we cached from the earlier read
                // of register 0x05, or until progress has been stable for a
                // short window (in case the firmware sent fewer entries than
                // the cached total — better an incomplete download than a
                // 2-second tail on every successful run). The Swift original
                // uses a 2 s wall-clock deadline with a 300 ms stability
                // window; here the same budget is expressed as 20 ms poll
                // iterations so it also behaves under runTest virtual time.
                val target = totalEntries.toInt()
                var lastCount = snap.size
                var stableIterations = 0
                var iterations = 0
                while (snapshot().size < target && iterations < 100) {
                    delay(20.milliseconds)
                    iterations++
                    val now = snapshot().size
                    if (now != lastCount) {
                        lastCount = now
                        stableIterations = 0
                    } else {
                        stableIterations++
                        if (stableIterations >= 15) break
                    }
                }
                val finalSnapshot = snapshot()
                if (finalSnapshot.size > snap.size) {
                    channel.trySend(
                        Download(
                            data = finalSnapshot, percentComplete = 1.0,
                            totalEntries = totalEntries, entriesDownloaded = totalEntries,
                        ),
                    )
                }
                finish(error = null)
                throw DownloadFinished()
            }
        }
        // The progress flow finished without `remaining == 0` — the
        // inactivity watchdog unsubscribed the logging channels (or something
        // else tore the subscription down). Either way the readout never
        // completed; surface a timeout.
        finish(MetaWearException.Timeout)
    } catch (e: DownloadFinished) {
        // Success path — everything already finalized before the throw.
    } catch (e: CancellationException) {
        finish(e)
        throw e
    } catch (e: Throwable) {
        finish(e)
    }
}

private suspend fun MetaWearDevice.cleanupLogDownloadSetup() {
    runCatching { writeRaw(Packet.command(Module.LOGGING, LOG_READOUT_NOTIFY, 0x00)) }
    runCatching { writeRaw(Packet.command(Module.LOGGING, LOG_READOUT_PAGE_COMPLETED, 0x00)) }
    runCatching { writeRaw(Packet.command(Module.LOGGING, LOG_READOUT_PROGRESS, 0x00)) }
    unsubscribeRaw(Module.LOGGING, LOG_READOUT_NOTIFY)
    unsubscribeRaw(Module.LOGGING, LOG_READOUT_PROGRESS)
    unsubscribeRaw(Module.LOGGING, LOG_READOUT_PAGE_COMPLETED)
}

// ---- Log reassembly ----

/**
 * Decode an already-downloaded set of raw log entries into typed samples for
 * one loggable, using the chunk layout registered for it.
 *
 * Use this when you have *multiple* active loggers and want to download them
 * all in one pass: call `downloadLogs()` (no args) once to drain the board's
 * circular log, accumulate the raw entries, then call this once per loggable
 * to extract its samples. Calling `downloadLogs(loggable)` per loggable
 * instead would re-trigger the readout each time and the second call would
 * see `LOG_LENGTH == 0` (the first readout drained the log).
 *
 * @throws MetaWearException.InvalidState if no logger is registered for
 *   `loggable.loggerKey` — call `startLogging(loggable)` (live in the same
 *   session) or `recoverLoggers(loggable)` (across app restart) first.
 */
fun <S> MetaWearDevice.decodeEntries(
    entries: List<RawLogEntry>,
    loggable: Loggable<S>,
): List<LoggedSample<S>> {
    val chunks = loggerRegistry[loggable.loggerKey]
        ?: throw MetaWearException.InvalidState(
            "No logger registered for '${loggable.loggerKey}'. Call startLogging or recoverLoggers first.",
        )
    return decodeEntries(entries, chunks) { loggable.parseLogSample(it) }
}

/**
 * Decode an already-downloaded set of raw log entries into typed samples for
 * one polled-readable logger. Same multi-logger rationale as the [Loggable]
 * overload — issue a single raw `downloadLogs()` to drain the board, then
 * call this per polled logger to extract its samples.
 */
fun <S> MetaWearDevice.decodeEntries(
    entries: List<RawLogEntry>,
    logger: PolledLogger<S>,
): List<LoggedSample<S>> {
    val chunks = loggerRegistry[logger.loggerKey]
        ?: throw MetaWearException.InvalidState(
            "No logger registered for '${logger.loggerKey}'. Call startLogging or recoverLoggers first.",
        )
    val readable = logger.readable
    return decodeEntries(entries, chunks) { readable.parseLogSample(it) }
}

/**
 * Chunk-reassembly core shared by every `decodeEntries`/`downloadLogs` overload.
 *
 * Chunks are paired by **per-logger-ID arrival order**, mirroring the C++ SDK
 * (`process_log_data` queues entries per logger ID and pops one from each
 * queue to form a sample). An earlier draft grouped entries by identical
 * `(resetUID, tick)` instead — that breaks above ~683 Hz, where one tick
 * (≈1.465 ms) spans more than one sample and distinct samples collapse into
 * the same group and get dropped.
 *
 * The sample timestamp is taken from the first chunk's entry. A trailing
 * sample whose later chunks were cut off by the end of the download is
 * dropped, matching the old behaviour for incomplete groups.
 */
internal fun <S> MetaWearDevice.decodeEntries(
    entries: List<RawLogEntry>,
    chunks: List<LoggerChunk>,
    decode: (ByteArray) -> S,
): List<LoggedSample<S>> {
    val chunkIDs = chunks.map { it.id }
    val queues = mutableMapOf<Int, MutableList<RawLogEntry>>()
    for (entry in entries) {
        if (entry.id in chunkIDs) queues.getOrPut(entry.id) { mutableListOf() }.add(entry)
    }
    val sampleCount = chunkIDs.minOfOrNull { queues[it]?.size ?: 0 } ?: 0
    if (sampleCount <= 0) return emptyList()
    val firstQueue = queues[chunkIDs[0]] ?: return emptyList()

    val ref = logReferenceDate
    val result = ArrayList<LoggedSample<S>>(sampleCount)
    for (i in 0 until sampleCount) {
        var assembled = ByteArray(0)
        for (chunk in chunks) {
            val entry = queues.getValue(chunk.id)[i]
            assembled += PacketParser.le32(entry.rawData).copyOfRange(0, chunk.byteCount)
        }
        val sample = decode(assembled)
        val msElapsed = firstQueue[i].tick.toDouble() * PacketParser.MS_PER_TICK
        val date = if (ref != null) {
            ref + msElapsed.milliseconds
        } else {
            // No time reference — use ms-since-reset as a relative offset from epoch
            Instant.fromEpochMilliseconds(0) + msElapsed.milliseconds
        }
        result.add(LoggedSample(date = date, tickMs = msElapsed, value = sample))
    }
    return result.sortedBy { it.tickMs }
}
