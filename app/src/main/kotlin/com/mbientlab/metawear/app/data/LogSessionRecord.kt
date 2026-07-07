package com.mbientlab.metawear.app.data

import android.content.SharedPreferences
import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.protocol.PolledLoggerHandles
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

/**
 * One sensor's on-device logging session, from start to downloaded.
 * Pending sessions — including the
 * board-allocated polled-logger handles — persist through
 * [LogSessionRegistry]'s prefs backing and are restored on relaunch. The
 * SDK's in-memory chunk registry is rebuilt from the board's trigger table
 * via `recoverLoggers` at download time (see `ConfiguredSensor.decodeAndSave`).
 */
data class LogSessionRecord(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val selection: SensorSelection,
    val startDate: Instant,
    val status: Status = Status.RUNNING,
    /**
     * Board-allocated timer/event/logger IDs for polled (environmental)
     * sessions — required to dismantle the on-board chain on stop, even
     * across app restarts. `null` for streamed sensors.
     */
    val polledHandles: PolledLoggerHandles? = null,
) {
    enum class Status {
        /** Logger active on the board. */
        RUNNING,

        /** Stopped but data still on the board (awaiting download). */
        STOPPED,

        /** Data successfully persisted locally. */
        DOWNLOADED,

        /** Failed to start or decode. */
        FAILED,
    }
}

/**
 * Line-per-record, tab-separated persistence codec for [LogSessionRecord],
 * covering the sensor config and polled-logger handles. Pure Kotlin so the
 * round trip is unit-tested on the JVM.
 */
object LogSessionRecordCodec {

    private const val FIELD_SEP = "\t"
    private const val LINE_SEP = "\n"
    private const val EMPTY = ""

    fun encode(records: List<LogSessionRecord>): String =
        records.joinToString(LINE_SEP) { r ->
            listOf(
                r.id,
                r.deviceId,
                r.selection.key.name,
                r.selection.hz.toString(),
                r.selection.range?.toString() ?: EMPTY,
                r.selection.pollIntervalMs?.toString() ?: EMPTY,
                r.startDate.toEpochMilliseconds().toString(),
                r.status.name,
                r.polledHandles?.let { h ->
                    "${h.timerID},${h.eventID},${h.loggerIDs.joinToString("+")}"
                } ?: EMPTY,
            ).joinToString(FIELD_SEP)
        }

    fun decode(encoded: String): List<LogSessionRecord> =
        encoded.split(LINE_SEP).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val f = line.split(FIELD_SEP)
            if (f.size < 8) return@mapNotNull null
            // Unknown sensor keys (e.g. from a newer app version) are skipped.
            val key = SensorKey.entries.firstOrNull { it.name == f[2] } ?: return@mapNotNull null
            val status = LogSessionRecord.Status.entries.firstOrNull { it.name == f[7] }
                ?: LogSessionRecord.Status.RUNNING
            LogSessionRecord(
                id = f[0],
                deviceId = f[1],
                selection = SensorSelection(
                    key = key,
                    hz = f[3].toDoubleOrNull() ?: key.defaultHz,
                    range = f[4].toFloatOrNull(),
                    pollIntervalMs = f[5].toLongOrNull(),
                ),
                startDate = Instant.fromEpochMilliseconds(f[6].toLongOrNull() ?: 0L),
                status = status,
                polledHandles = f.getOrNull(8)?.takeIf { it.isNotEmpty() }?.let(::decodeHandles),
            )
        }

    private fun decodeHandles(encoded: String): PolledLoggerHandles? {
        val parts = encoded.split(",")
        if (parts.size < 3) return null
        return PolledLoggerHandles(
            timerID = parts[0].toIntOrNull() ?: return null,
            eventID = parts[1].toIntOrNull() ?: return null,
            loggerIDs = parts[2].split("+").mapNotNull { it.toIntOrNull() },
        )
    }
}

/**
 * Registry of log-session records, shared across ViewModels. When backed by
 * [prefs] (production), every mutation persists so a pending log session
 * survives process death. Pass `null` for a purely in-memory registry
 * (tests).
 */
class LogSessionRegistry(private val prefs: SharedPreferences? = null) {

    private companion object {
        const val KEY = "log_session_records"
    }

    private val _records = MutableStateFlow(
        // DOWNLOADED/FAILED records are per-run debris — prune at load so the
        // stored list only ever accumulates truly pending work.
        LogSessionRecordCodec.decode(prefs?.getString(KEY, "") ?: "").filter {
            it.status == LogSessionRecord.Status.RUNNING || it.status == LogSessionRecord.Status.STOPPED
        },
    )
    val records: StateFlow<List<LogSessionRecord>> = _records.asStateFlow()

    private fun commit(records: List<LogSessionRecord>) {
        _records.value = records
        prefs?.edit()?.putString(KEY, LogSessionRecordCodec.encode(records))?.apply()
    }

    fun add(record: LogSessionRecord) {
        commit(_records.value + record)
    }

    fun updateStatus(id: String, status: LogSessionRecord.Status) {
        commit(_records.value.map { if (it.id == id) it.copy(status = status) else it })
    }

    fun pendingFor(deviceId: String): List<LogSessionRecord> =
        _records.value.filter {
            it.deviceId == deviceId &&
                (it.status == LogSessionRecord.Status.RUNNING || it.status == LogSessionRecord.Status.STOPPED)
        }

    fun clearDownloaded(deviceId: String) {
        commit(
            _records.value.filterNot {
                it.deviceId == deviceId && it.status == LogSessionRecord.Status.DOWNLOADED
            },
        )
    }
}
