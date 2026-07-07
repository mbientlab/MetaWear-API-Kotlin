package com.mbientlab.metawear.app.data

import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.protocol.PolledLoggerHandles
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

/**
 * One sensor's on-device logging session, from start to downloaded. Lean port
 * of `LogSessionRecord.swift`: records live in memory for the app's lifetime
 * (the SDK's logger registry is in-memory too, so a pending download does not
 * survive process death — reconnect + the SDK's `recoverLoggers` covers that
 * path and is out of scope here).
 */
data class LogSessionRecord(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val selection: SensorSelection,
    val startDate: Instant,
    val status: Status = Status.RUNNING,
    /**
     * Board-allocated timer/event/logger IDs for polled (environmental)
     * sessions — required to dismantle the on-board chain on stop. `null`
     * for streamed sensors.
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

/** In-memory registry of log-session records, shared across ViewModels. */
class LogSessionRegistry {

    private val _records = MutableStateFlow<List<LogSessionRecord>>(emptyList())
    val records: StateFlow<List<LogSessionRecord>> = _records.asStateFlow()

    fun add(record: LogSessionRecord) {
        _records.value = _records.value + record
    }

    fun updateStatus(id: String, status: LogSessionRecord.Status) {
        _records.value = _records.value.map { if (it.id == id) it.copy(status = status) else it }
    }

    fun pendingFor(deviceId: String): List<LogSessionRecord> =
        _records.value.filter {
            it.deviceId == deviceId &&
                (it.status == LogSessionRecord.Status.RUNNING || it.status == LogSessionRecord.Status.STOPPED)
        }

    fun clearDownloaded(deviceId: String) {
        _records.value = _records.value.filterNot {
            it.deviceId == deviceId && it.status == LogSessionRecord.Status.DOWNLOADED
        }
    }
}
