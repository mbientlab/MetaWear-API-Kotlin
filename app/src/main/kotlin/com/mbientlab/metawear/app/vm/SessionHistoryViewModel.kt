package com.mbientlab.metawear.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbientlab.metawear.app.AppContainer
import com.mbientlab.metawear.app.data.Persistables
import com.mbientlab.metawear.persistence.SessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Persisted-session history for the active device: list, delete, CSV export.
 * The CSV comes straight from the persistence module's `exportTable`
 * (the `epoch,elapsed_ms,…` layout).
 */
class SessionHistoryViewModel(private val container: AppContainer) : ViewModel() {

    private val deviceId: String? = container.activeDeviceId.value

    private val _sessions = MutableStateFlow<List<SessionSnapshot>>(emptyList())
    val sessions: StateFlow<List<SessionSnapshot>> = _sessions.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val id = deviceId
                if (id == null) container.persistence.fetchAllSessions()
                else container.persistence.fetchSessions(id)
            }
                .onSuccess { _sessions.value = it }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun deleteSession(id: String) {
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

    fun clearError() {
        _lastError.value = null
    }
}
