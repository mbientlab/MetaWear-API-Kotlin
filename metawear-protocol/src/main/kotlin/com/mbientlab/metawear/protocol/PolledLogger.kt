package com.mbientlab.metawear.protocol

// Port of MWPolledLogger.swift — the polled-loggable protocol plus the
// polled-logger machinery that pairs a readable with an on-board timer period.

/**
 * A read-only sensor ([Readable]) whose responses can be captured to the
 * board's flash log by pairing a timer + event with a logger subscription.
 * Port of `MWPolledLoggable` (Swift).
 *
 * Conforming types declare how their data payload (the bytes *after* the
 * two-byte BLE `[module, register]` header) should be split into 4-byte
 * flash entries — same shape as [Loggable], but driven by an on-board
 * timer rather than the sensor streaming on its own.
 */
interface PolledLoggable<out S> : Readable<S> {
    /**
     * How to split the readable's data payload into 4-byte flash entries.
     * Each element is `(byteOffset, byteCount)` within the bytes after the
     * 2-byte BLE header. One logger ID is allocated per chunk.
     */
    val logDataChunks: List<LogChunk>

    /**
     * Source index byte for the logger trigger. Readables with a per-channel
     * data id (e.g. the multichannel thermometer) return the channel here so
     * the firmware matches the logger to that channel's responses; id-less
     * readables use the default `0xFF`.
     */
    val loggerTriggerIndex: Int get() = 0xFF

    /**
     * Register byte used in the logger TRIGGER: the readable register with
     * the read (0x80) and silent (0x40) bits set — matching the C++ SDK,
     * which builds logger triggers from the readable signal's full header
     * register (e.g. temperature = `0xC1`, not the bare `0x01`). The
     * timer-driven event must issue the matching SILENT read.
     */
    val loggerTriggerRegister: Int get() = dataRegister or 0x80 or 0x40

    /**
     * Decode one complete sample from the bytes reassembled in chunk order.
     * Default: prepend a synthetic `[module, register]` header so the existing
     * [parseSample] (which expects a full BLE packet) works on the logger-side
     * reassembled payload too.
     */
    fun parseLogSample(data: ByteArray): S =
        parseSample(byteArrayOf(module.value.toByte(), dataRegister.toByte()) + data)
}

/**
 * Pairs a [PolledLoggable] readable with the on-board timer period at which
 * the firmware should drive the read. Pass to `MetaWearDevice.startLogging` to
 * set up the timer → event → logger chain in one call. Port of
 * `MWPolledLogger` (Swift).
 */
class PolledLogger<out S>(
    val readable: PolledLoggable<S>,
    /** How often the board fires the read, in milliseconds. */
    val periodMs: Long,
) {
    /**
     * Synthetic key used to store this polled-logger's chunk registry on
     * `MetaWearDevice`. Distinct from any same-module streamed logger so
     * the two don't collide if both are active.
     */
    val loggerKey: String
        get() = "polled-%02X-%02X".format(readable.module.value, readable.dataRegister)
}

/**
 * On-board resource IDs allocated by `startLogging(polledLogger)`. Callers
 * must persist these (or otherwise remember them) so the polled logger can
 * be stopped/recovered across app restarts — the host doesn't poll, the
 * board does, and timer + event survive disconnects. Port of
 * `MWPolledLoggerHandles` (Swift).
 */
data class PolledLoggerHandles(
    val timerID: Int,
    val eventID: Int,
    val loggerIDs: List<Int>,
)
