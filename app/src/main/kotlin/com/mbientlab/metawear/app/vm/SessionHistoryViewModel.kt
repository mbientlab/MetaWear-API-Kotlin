package com.mbientlab.metawear.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbientlab.metawear.app.AppContainer
import com.mbientlab.metawear.app.core.AnyChartSample
import com.mbientlab.metawear.app.core.SessionAxisStyle
import com.mbientlab.metawear.app.core.SessionHistoryGrouping
import com.mbientlab.metawear.app.data.Persistables
import com.mbientlab.metawear.persistence.QuaternionPersistable
import com.mbientlab.metawear.persistence.SessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Persisted-session history across all boards: sections grouped by board
 * identity, per-session CSV export, sample loading for chart previews and
 * quaternion replay, and delete.
 */
class SessionHistoryViewModel(private val container: AppContainer) : ViewModel() {

    private val _sessions = MutableStateFlow<List<SessionSnapshot>>(emptyList())
    val sessions: StateFlow<List<SessionSnapshot>> = _sessions.asStateFlow()

    /** History grouped by board (serial-keyed; titles prefer name, then MAC). */
    val sections: StateFlow<List<SessionHistoryGrouping.BoardSection>> = combine(
        _sessions,
        container.remembered.devices,
    ) { snapshots, remembered ->
        val macBySerial = buildMap {
            for (device in remembered) {
                val serial = device.serialNumber ?: continue
                putIfAbsent(serial, device.mac)
            }
        }
        SessionHistoryGrouping.sections(snapshots, macBySerial)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { container.persistence.fetchAllSessions() }
                .onSuccess { _sessions.value = it }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun deleteSession(id: String) {
        // Optimistic: drop the row immediately; a failed delete resurrects it
        // on the reload.
        _sessions.value = _sessions.value.filterNot { it.id == id }
        viewModelScope.launch {
            runCatching { container.persistence.deleteSession(id) }
                .onFailure { _lastError.value = it.message }
            refresh()
        }
    }

    /** Build the CSV text for one session, or null on failure. */
    suspend fun exportCsv(snapshot: SessionSnapshot): String? {
        val persistable = Persistables.forKind(snapshot.sensorKind) ?: run {
            _lastError.value = "Unknown sensor kind '${snapshot.sensorKind}'"
            return null
        }
        return runCatching { container.persistence.exportTable(snapshot.id, persistable).csvString }
            .onFailure { _lastError.value = it.message }
            .getOrNull()
    }

    /**
     * Load a session's samples as chart points (most recent 600), through the
     * generic flat-value packing so every stored kind renders.
     */
    suspend fun loadPreview(snapshot: SessionSnapshot): List<AnyChartSample>? {
        val persistable = Persistables.forKind(snapshot.sensorKind) ?: return null
        val channels = SessionAxisStyle.channelCountFor(snapshot.sensorKind)
        return runCatching {
            container.persistence.fetchSamples(snapshot.id, persistable).takeLast(600).map { sample ->
                val v = persistable.persistenceValues(sample.value)
                AnyChartSample.of(
                    sample.date, v.f0, v.f1, v.f2,
                    if (v.accuracy != 0) v.accuracy.toFloat() else v.f3,
                    channelCount = channels,
                )
            }
        }
            .onFailure { _lastError.value = it.message }
            .getOrNull()
    }

    /** Replay payload for a quaternion session: chart samples + tick offsets (ms). */
    suspend fun loadReplay(snapshot: SessionSnapshot): Pair<List<AnyChartSample>, List<Double>>? {
        if (snapshot.sensorKind != QuaternionPersistable.persistenceKind) return null
        return runCatching {
            val samples = container.persistence.fetchSamples(snapshot.id, QuaternionPersistable)
            val chart = samples.map { s ->
                AnyChartSample.of(s.date, s.value.w, s.value.x, s.value.y, s.value.z, channelCount = 4)
            }
            chart to samples.map { it.tickMs }
        }
            .onFailure { _lastError.value = it.message }
            .getOrNull()
    }

    fun clearError() {
        _lastError.value = null
    }
}
