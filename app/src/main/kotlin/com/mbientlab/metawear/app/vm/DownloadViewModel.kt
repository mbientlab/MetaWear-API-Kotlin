package com.mbientlab.metawear.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.app.AppContainer
import com.mbientlab.metawear.app.data.LogDownloader
import com.mbientlab.metawear.app.data.LogSessionRecord
import com.mbientlab.metawear.persistence.SessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Flash-log download orchestration for the solo logging screen. The heavy
 * lifting — single raw drain, per-record typed decode, attribution stamps,
 * board clear — lives in [LogDownloader], shared with the group coordinator.
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
            val result = LogDownloader.downloadAll(
                device = device,
                persistence = container.persistence,
                registry = container.logSessions,
                records = records,
                deviceName = container.displayNameFor(device.identifier),
                onProgress = { fraction, entries, total ->
                    _phase.value = Phase.Downloading(fraction, entries, total)
                },
            )
            _phase.value = if (result.failed) {
                Phase.Failed(result.message ?: "Download failed")
            } else {
                Phase.Ready(result.snapshots, result.warning)
            }
        }
    }

    fun reset() {
        _phase.value = Phase.Idle
    }
}
