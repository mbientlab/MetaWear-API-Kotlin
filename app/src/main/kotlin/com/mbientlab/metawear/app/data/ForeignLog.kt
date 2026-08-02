package com.mbientlab.metawear.app.data

import com.mbientlab.metawear.RawLogEntry
import com.mbientlab.metawear.model.AnonymousSignal
import com.mbientlab.metawear.model.LoggedSample
import com.mbientlab.metawear.protocol.PacketParser
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.Instant

// A "foreign log" is data on a board that this app has no pending session
// record for — logged by another phone, another app, or a since-wiped install.
// The pure decision table below chooses what to do with it; the anonymous
// signal machinery recovers and labels whatever is decodable.

/** A foreign log discovered on a connected board. */
data class OrphanLogState(
    /** Entry count at evaluation time (0 while an MMS page is still in RAM). */
    val entryCount: Long,
    /** Board the log lives on (never drain a different one). */
    val deviceId: String,
    /** Whether the board reported itself actively recording. */
    val isActivelyLogging: Boolean,
)

/** What to do about a board's on-flash log. */
sealed class ForeignLogDecision {
    /** Recoverable data or a session in progress — surface it to the user. */
    data class Surface(val isActivelyLogging: Boolean) : ForeignLogDecision()

    /** Entries with NO loggers = undecodable garbage; clear without asking. */
    data object SilentClear : ForeignLogDecision()

    /** Nothing foreign (or it's this app's own pending session). */
    data object LeaveAlone : ForeignLogDecision()
}

/**
 * Pure decision table.
 *
 * @param hasActiveLoggers `null` when logger enumeration failed — err on the
 *   side of surfacing (a wrong "surface" costs a dialog; a wrong clear costs
 *   someone's data).
 */
fun foreignLogDecision(
    entryCount: Long,
    hasActiveLoggers: Boolean?,
    isLoggingEnabled: Boolean,
    hasLocalPendingRecord: Boolean,
): ForeignLogDecision {
    if (hasLocalPendingRecord) return ForeignLogDecision.LeaveAlone
    return when (hasActiveLoggers) {
        true -> if (entryCount > 0 || isLoggingEnabled) {
            ForeignLogDecision.Surface(isActivelyLogging = isLoggingEnabled)
        } else {
            ForeignLogDecision.LeaveAlone
        }
        false -> if (entryCount > 0) ForeignLogDecision.SilentClear else ForeignLogDecision.LeaveAlone
        null -> if (entryCount > 0 || isLoggingEnabled) {
            ForeignLogDecision.Surface(isActivelyLogging = isLoggingEnabled)
        } else {
            ForeignLogDecision.LeaveAlone
        }
    }
}

/**
 * Human-readable session label for a recovered anonymous-signal identifier
 * (`"acceleration"`, `"temperature[1]"`, `"acceleration:rms?id=0"`, …).
 * Processor chains keep the full identifier; plain signals read "Recovered".
 */
fun foreignLabel(identifier: String): String {
    val root = identifier.substringBefore(':')
    val base = root.substringBefore('[')
    val head = when (base) {
        "acceleration" -> "Accelerometer"
        "angular-velocity" -> "Gyroscope"
        "magnetic-field" -> "Magnetometer"
        "temperature" -> "Temperature"
        "quaternion" -> "Fusion · Quaternion"
        "euler-angles" -> "Fusion · Euler Angles"
        "gravity" -> "Fusion · Gravity"
        "linear-acceleration" -> "Fusion · Linear Acceleration"
        "corrected-acceleration" -> "Fusion · Corrected Acceleration"
        "corrected-angular-velocity" -> "Fusion · Corrected Angular Velocity"
        "corrected-magnetic-field" -> "Fusion · Corrected Magnetic Field"
        else -> return "Unknown · $identifier"
    }
    return if (identifier.contains(':')) "$head · $identifier" else "$head · Recovered"
}

/**
 * Chunk reassembly for anonymous signals: pairs raw entries per logger-ID
 * arrival order, concatenates each signal's chunk slices, and decodes typed
 * outputs. Timestamps come from the board tick and [logReferenceDate]
 * (relative-to-epoch when the reference is unknown) — the same convention as
 * registered-logger decoding, so recovered sessions align with native ones.
 */
object AnonymousSignalDecoder {

    fun decode(
        entries: List<RawLogEntry>,
        signal: AnonymousSignal,
        logReferenceDate: Instant?,
    ): List<LoggedSample<List<AnonymousSignal.Output>>> {
        val chunkIDs = signal.chunks.map { it.id }
        val queues = mutableMapOf<Int, MutableList<RawLogEntry>>()
        for (entry in entries) {
            if (entry.id in chunkIDs) queues.getOrPut(entry.id) { mutableListOf() }.add(entry)
        }
        val sampleCount = chunkIDs.minOfOrNull { queues[it]?.size ?: 0 } ?: 0
        if (sampleCount <= 0) return emptyList()
        val firstQueue = queues[chunkIDs[0]] ?: return emptyList()

        val result = ArrayList<LoggedSample<List<AnonymousSignal.Output>>>(sampleCount)
        for (i in 0 until sampleCount) {
            var assembled = ByteArray(0)
            for (chunk in signal.chunks) {
                val entry = queues.getValue(chunk.id)[i]
                assembled += PacketParser.le32(entry.rawData).copyOfRange(0, chunk.byteCount)
            }
            val outputs = signal.decode(assembled)
            val msElapsed = firstQueue[i].tick.toDouble() * PacketParser.MS_PER_TICK
            val date = (logReferenceDate ?: Instant.fromEpochMilliseconds(0)) + msElapsed.milliseconds
            result.add(LoggedSample(date = date, tickMs = msElapsed, value = outputs))
        }
        return result.sortedBy { it.tickMs }
    }
}
