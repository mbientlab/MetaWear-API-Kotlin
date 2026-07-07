package com.mbientlab.metawear.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.RawLogEntry
import com.mbientlab.metawear.app.AppContainer
import com.mbientlab.metawear.app.data.ConfiguredSensor
import com.mbientlab.metawear.app.data.LogSessionRecord
import com.mbientlab.metawear.app.data.decodeAndSave
import com.mbientlab.metawear.clearLog
import com.mbientlab.metawear.downloadLogs
import com.mbientlab.metawear.persistence.SessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Flash-log download orchestration: one
 * raw drain of the board's circular log, then per-record typed decode + save
 * (downloading per sensor would re-trigger the readout and find the log
 * already empty), then a single `clearLog()` once everything decoded.
 */
class DownloadViewModel(private val container: AppContainer) : ViewModel() {

    sealed class Phase {
        data object Idle : Phase()
        data class Downloading(val progress: Double, val entries: Long, val total: Long?) : Phase()
        data class Ready(val snapshots: List<SessionSnapshot>, val warning: String?) : Phase()
        data class Failed(val message: String) : Phase()
    }

    private val device: MetaWearDevice? = container.activeDevice()

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    fun downloadAll(records: List<LogSessionRecord>) {
        val device = device ?: return
        if (_phase.value is Phase.Downloading || records.isEmpty()) return
        viewModelScope.launch {
            _phase.value = Phase.Downloading(0.0, 0, null)
            try {
                // 1. Single raw drain, tracking progress.
                var entries: List<RawLogEntry> = emptyList()
                device.downloadLogs().collect { progress ->
                    entries = progress.data
                    _phase.value = Phase.Downloading(
                        progress = progress.percentComplete,
                        entries = progress.entriesDownloaded ?: entries.size.toLong(),
                        total = progress.totalEntries,
                    )
                }

                // 2. Typed decode + persist per record.
                val snapshots = mutableListOf<SessionSnapshot>()
                var warning: String? = null
                val info = device.deviceInfo
                for (record in records) {
                    if (info == null) break
                    val sensor = ConfiguredSensor.make(record.selection, device.modules)
                    runCatching { sensor.decodeAndSave(device, container.persistence, entries, info) }
                        .onSuccess { snapshot ->
                            if (snapshot != null) {
                                snapshots.add(snapshot)
                                container.logSessions.updateStatus(record.id, LogSessionRecord.Status.DOWNLOADED)
                            } else {
                                warning = "No samples decoded for ${record.selection.key.title}"
                                container.logSessions.updateStatus(record.id, LogSessionRecord.Status.FAILED)
                            }
                        }
                        .onFailure {
                            warning = it.message
                            container.logSessions.updateStatus(record.id, LogSessionRecord.Status.FAILED)
                        }
                }

                // 3. Clear the board's flash once everything is decoded.
                runCatching { device.clearLog() }

                _phase.value = Phase.Ready(snapshots, warning)
            } catch (e: Exception) {
                _phase.value = Phase.Failed(e.message ?: "Download failed")
            }
        }
    }

    fun reset() {
        _phase.value = Phase.Idle
    }
}
