package com.mbientlab.metawear.model

import com.mbientlab.metawear.protocol.Module

// Anonymous-signal value type + scheme identifier builder. The pure
// reconstruction logic lives in AnonymousSignalBuilder.kt; the device-facing
// entry point (`createAnonymousDataSignals`) lives in
// DeviceAnonymousSignals.kt.

/**
 * Represents a logger signal recovered from a board whose on-device logger
 * configuration is intact but whose host-side `Loggable` registry is not.
 *
 * Reconstructed by walking `queryActiveLoggers()` → `queryActiveProcessors()`,
 * following any data-processor chain back to its root sensor signal, and
 * producing a canonical identifier string plus a typed decode closure.
 *
 * Mirrors `mbl_mw_metawearboard_create_anonymous_datasignals` from the C++
 * MetaWear SDK (test_anonymous_signal.py).
 */
class AnonymousSignal(
    /**
     * Canonical identifier, e.g. `"acceleration"`, `"angular-velocity[1]"`,
     * `"acceleration:rms?id=0:accumulate?id=1:time?id=2"`.
     */
    val identifier: String,

    /** The root sensor signal this chain ultimately reads from. */
    val rootModule: Module,

    /**
     * Logger chunks this signal reads from the log stream, in concatenation
     * order. Each entry carries the board-assigned logger ID and the byte
     * count the chunk contributes to the assembled payload — both needed by
     * `MetaWearDevice.decodeEntries` to reassemble fragmented entries.
     */
    val chunks: List<Chunk>,

    /**
     * Decode a single raw log entry's payload into typed samples.
     * Normal signals yield a 1-element list; the Fuser yields 2 elements
     * (one for each fused input).
     */
    val decode: (ByteArray) -> List<Output>,
) {
    /**
     * One logger subscription that contributes a slice of the full payload.
     * A 6-byte XYZ signal that exceeds the firmware's 4-byte-per-entry limit,
     * for example, surfaces as two chunks (4 bytes + 2 bytes).
     */
    data class Chunk(val id: Int, val byteCount: Int)

    /**
     * Convenience: just the logger IDs, in the same order as [chunks].
     * Provided for callers that only care about identity (e.g. matching
     * against `queryActiveLoggers()` output).
     */
    val loggerIDs: List<Int> get() = chunks.map { it.id }

    /** A typed sample produced by [decode]. */
    sealed class Output {
        data class Cartesian(val value: CartesianFloat) : Output()
        data class Scalar(val value: Float) : Output()
        data class Quaternion(val value: com.mbientlab.metawear.model.Quaternion) : Output()
        data class Euler(val value: EulerAngles) : Output()
        data class CorrectedCartesian(val value: CorrectedCartesianFloat) : Output()
    }

    // Value-based equality on the identifying fields only — identifier, root
    // module, and chunks. The decode closure isn't comparable.
    override fun equals(other: Any?): Boolean = other is AnonymousSignal &&
        other.identifier == identifier &&
        other.rootModule == rootModule &&
        other.chunks == chunks

    override fun hashCode(): Int {
        var result = identifier.hashCode()
        result = 31 * result + rootModule.hashCode()
        result = 31 * result + chunks.hashCode()
        return result
    }

    override fun toString(): String =
        "AnonymousSignal(identifier=$identifier, rootModule=$rootModule, chunks=$chunks)"
}

/**
 * Scheme identifier builder: internal pure functions that map
 * (root signal + processor chain) → string. Kept separate from
 * `MetaWearDevice` so they can be exhaustively tested without any I/O.
 */
internal object AnonymousSignalScheme {

    /** One processor link in a chain handed to [compose]. */
    data class ProcessorLink(val type: Int, val id: Int, val config: List<Int>)

    /**
     * Identifier for a root sensor signal. [chunkLength] distinguishes packed
     * XYZ (length > 2) from single-axis (length == 2).
     */
    fun rootIdentifier(
        module: Module,
        register: Int,
        channel: Int,
        chunkOffset: Int,
        chunkLength: Int,
    ): String? = when (module) {

        Module.ACCELEROMETER -> {
            // Register 0x04 is the data-interrupt register (with READ bit cleared).
            when {
                register != 0x04 -> null
                chunkLength > 2 -> "acceleration"
                else -> "acceleration[${chunkOffset / 2}]"
            }
        }

        Module.GYRO -> when {
            register != 0x05 -> null
            chunkLength > 2 -> "angular-velocity"
            else -> "angular-velocity[${chunkOffset / 2}]"
        }

        Module.MAGNETOMETER -> when {
            register != 0x05 -> null
            chunkLength > 2 -> "magnetic-field"
            else -> "magnetic-field[${chunkOffset / 2}]"
        }

        Module.TEMPERATURE -> {
            // Register 0xC1 (READ|VALUE); channel byte carries the thermistor index.
            if (register == 0xC1) "temperature[$channel]" else null
        }

        Module.SENSOR_FUSION -> when (register) {
            // Sensor-fusion data registers use the data-register number directly.
            0x04 -> "corrected-acceleration"
            0x05 -> "corrected-angular-velocity"
            0x06 -> "corrected-magnetic-field"
            0x07 -> "quaternion"
            0x08 -> "euler-angles"
            0x09 -> "gravity"
            0x0A -> "linear-acceleration"
            else -> null
        }

        else -> null
    }

    /** Identifier segment for a single processor link, e.g. `"rms?id=0"`. */
    fun processorSegment(type: Int, id: Int, config: List<Int>): String? {
        val name = processorName(type, config) ?: return null
        return "$name?id=$id"
    }

    /**
     * Base name for a processor type. Some processor IDs carry a config-dependent
     * disambiguation (ACCUMULATOR vs. COUNTER share id 0x02; LOW-PASS vs. HIGH-PASS
     * share id 0x03; RMS vs. RSS share id 0x07).
     */
    fun processorName(type: Int, config: List<Int>): String? = when (type) {
        0x01 -> "passthrough"
        0x02 -> {
            // AccumulatorConfig packs mode into bits 4-6 of config[0]:
            // 0 = accumulate, 1 = count. Low bits describe output/input size.
            val mode = ((config.firstOrNull() ?: 0) shr 4) and 0x07
            if (mode == 0x01) "count" else "accumulate"
        }
        0x03 -> {
            // Average filter: bit 0 of mode indicates high-pass.
            val mode = if (config.size > 1) config[1] else 0
            if ((mode and 0x01) != 0) "high-pass" else "low-pass"
        }
        0x06 -> "comparison"
        0x07 -> {
            // RMS vs. RSS: distinguished by the mode byte at config[1].
            // 0 = RMS, 1 = RSS.
            val mode = if (config.size > 1) config[1] else 0x00
            if (mode == 0x01) "rss" else "rms"
        }
        0x08 -> "time"
        0x09 -> "math"
        0x0A -> "delay"
        0x0B -> "pulse"
        0x0C -> "differential"
        0x0D -> "threshold"
        0x0F -> "buffer"
        0x10 -> "packer"
        0x11 -> "account"
        0x1B -> "fuser"
        else -> null
    }

    /**
     * The processor type IDs that emit a "state" (readable) signal rather than
     * (or in addition to) data. These show up as `"buffer-state"` in the Python
     * reference when the logger captures the buffer's state register instead of
     * its data output.
     */
    fun stateNameOverride(forType: Int): String? = when (forType) {
        0x0F -> "buffer-state"
        else -> null
    }

    /**
     * True when `(module, register)` indicates a logger is capturing a data
     * processor's STATE register (as opposed to its normal NOTIFY output).
     * The C++ SDK encodes this as register `0xC4` on the DATA_PROCESSOR module.
     */
    fun isProcessorStateCapture(module: Module, register: Int): Boolean =
        module == Module.DATA_PROCESSOR && register == 0xC4

    /**
     * True when `(module, register)` indicates the logger's source is a data
     * processor's normal NOTIFY output (register 0x03).
     */
    fun isProcessorDataCapture(module: Module, register: Int): Boolean =
        module == Module.DATA_PROCESSOR && register == 0x03

    /**
     * Compose the full identifier from a root + an ordered chain of processors.
     * When [captureStateOfTerminalBuffer] is true, the last segment is rewritten
     * from its normal name (e.g. `"buffer"`) to the state variant (`"buffer-state"`).
     */
    fun compose(
        root: String,
        chain: List<ProcessorLink>,
        captureStateOfTerminalBuffer: Boolean = false,
    ): String? {
        val parts = mutableListOf(root)
        for ((idx, link) in chain.withIndex()) {
            val isTerminal = idx == chain.size - 1
            val stateName = if (isTerminal && captureStateOfTerminalBuffer) {
                stateNameOverride(forType = link.type)
            } else {
                null
            }
            val name = stateName
                ?: processorName(link.type, link.config)
                ?: return null
            parts.add("$name?id=${link.id}")
        }
        return parts.joinToString(":")
    }
}
