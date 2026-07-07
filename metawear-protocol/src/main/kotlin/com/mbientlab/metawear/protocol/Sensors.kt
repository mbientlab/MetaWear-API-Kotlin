package com.mbientlab.metawear.protocol

// Core sensor interfaces. Port of MWActions.swift (MWSensor / MWStreamable /
// MWLoggable / MWReadable / MWCommand / MWCommandSequence / MWPollable).
//
// Swift associated types become Kotlin generic type parameters. The sample type
// is in `out` position (only ever produced, never consumed), so it is variance-
// annotated `out S`.

/** A sensor that produces typed samples from raw BLE notification bytes. */
interface Sensor {
    val module: Module
    val dataRegister: Int
    val packedDataRegister: Int? get() = null
}

/** A sensor that can stream data continuously (~100 Hz max over BLE). */
interface Streamable<out S> : Sensor {
    /** Commands sent once to configure the sensor before streaming. */
    val configureCommands: List<ByteArray>

    /** Enable the data interrupt / output. */
    val enableCommand: ByteArray

    /** Start the sensor hardware. */
    val startCommand: ByteArray

    /** Stop the sensor hardware. */
    val stopCommand: ByteArray

    /** Disable the data interrupt / output. */
    val disableCommand: ByteArray

    // Multi-command forms. Override when a sensor needs more than one BLE write
    // for a phase (e.g. sensor fusion enabling the underlying chips). Default:
    // wrap the single command, dropping empties.
    val enableCommands: List<ByteArray> get() = if (enableCommand.isEmpty()) emptyList() else listOf(enableCommand)
    val startCommands: List<ByteArray> get() = if (startCommand.isEmpty()) emptyList() else listOf(startCommand)
    val stopCommands: List<ByteArray> get() = if (stopCommand.isEmpty()) emptyList() else listOf(stopCommand)
    val disableCommands: List<ByteArray> get() = if (disableCommand.isEmpty()) emptyList() else listOf(disableCommand)

    /** Commands issued before [configureCommands] to wake a cold-booted sensor. */
    val warmupCommands: List<ByteArray> get() = emptyList()

    /** Nanoseconds to sleep after [warmupCommands] and before [configureCommands]. */
    val warmupDelayNanos: Long get() = 0L

    /** Parse a single XYZ / scalar notification packet into a typed sample. */
    fun parseSample(packet: ByteArray): S

    /** Parse a packed notification (3 samples in one BLE packet). */
    fun parsePackedSamples(packet: ByteArray): List<S> = emptyList()
}

/** How to split a sensor's data payload into 4-byte flash log entries. */
data class LogChunk(val offset: Int, val length: Int)

/** A streaming sensor whose data can also be logged to on-device flash. */
interface Loggable<out S> : Streamable<S> {
    /** Unique string identifying this signal type (e.g. "acceleration"). */
    val loggerKey: String

    /**
     * Chunk layout for the on-device logger. Each `(offset, length)` is a slice
     * of the signal's data payload (the bytes *after* the 2-byte module/register
     * header). Default: a 6-byte XYZ int16 sensor split as first 4 bytes then 2.
     */
    val logDataChunks: List<LogChunk> get() = listOf(LogChunk(0, 4), LogChunk(4, 2))

    /**
     * Decode one sample from the bytes reassembled from all log chunks. Default
     * prepends a synthetic `[module, dataRegister]` header so [parseSample] works.
     */
    fun parseLogSample(data: ByteArray): S =
        parseSample(byteArrayOf(module.value.toByte(), dataRegister.toByte()) + data)
}

/** A sensor that is read once on demand rather than streamed. */
interface Readable<out S> : Sensor {
    /** Command to trigger a read. */
    val readCommand: ByteArray

    /** Parse the response packet. */
    fun parseSample(packet: ByteArray): S
}

/** A fire-and-forget board command. */
interface Command {
    val commandData: ByteArray
}

/** A fire-and-forget board action that requires more than one BLE write. */
interface CommandSequence {
    val commands: List<ByteArray>
}

/** A readable sensor meaningful to poll on an interval (marker for `device.poll`). */
interface Pollable<out S> : Readable<S>
