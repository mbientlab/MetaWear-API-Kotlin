package com.mbientlab.metawear.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.app.AppContainer
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.data.ConfiguredSensor
import com.mbientlab.metawear.app.data.LogSessionRecord
import com.mbientlab.metawear.app.data.startLoggingOn
import com.mbientlab.metawear.app.data.stopLoggingOn
import com.mbientlab.metawear.flushLogPage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * On-device logging lifecycle: start loggers for the selected sensors, tick
 * an elapsed clock while recording, stop and mark records ready for download.
 * Port of `LogSessionViewModel.swift`.
 */
class LogSessionViewModel(private val container: AppContainer) : ViewModel() {

    sealed class Phase {
        data object Idle : Phase()
        data class Running(val startedAt: Instant) : Phase()
        data object Stopped : Phase()
    }

    private val device: MetaWearDevice? = container.activeDevice()

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** All records for this device (any status) — drives the download list. */
    val records: StateFlow<List<LogSessionRecord>> = container.logSessions.records

    private var elapsedJob: Job? = null

    /** Sensors actively logging, keyed by record id (needed to stop them). */
    private val activeSensors = mutableMapOf<String, ConfiguredSensor>()

    init {
        // Rehydrate from records still running on this device (e.g. after
        // navigating away and back while logging).
        val device = device
        if (device != null) {
            val running = container.logSessions.records.value.filter {
                it.deviceId == device.identifier && it.status == LogSessionRecord.Status.RUNNING
            }
            if (running.isNotEmpty()) {
                running.forEach { record ->
                    activeSensors[record.id] = ConfiguredSensor.make(record.selection, device.modules)
                }
                val startedAt = running.minOf { it.startDate }
                _phase.value = Phase.Running(startedAt)
                startElapsedTicker(startedAt)
            }
        }
    }

    fun start(selections: List<SensorSelection>) {
        val device = device ?: return
        if (_phase.value is Phase.Running || selections.isEmpty()) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val startedAt = Clock.System.now()
                for (selection in selections) {
                    val sensor = ConfiguredSensor.make(selection, device.modules)
                    // Polled (environmental) sensors return the board-side
                    // timer/event/logger handles; keep them on the record so
                    // stop() can dismantle the chain even after this VM is
                    // recreated by navigation.
                    val handles = sensor.startLoggingOn(device)
                    val record = LogSessionRecord(
                        deviceId = device.identifier,
                        selection = selection,
                        startDate = startedAt,
                        polledHandles = handles,
                    )
                    activeSensors[record.id] = sensor
                    container.logSessions.add(record)
                }
                _phase.value = Phase.Running(startedAt)
                startElapsedTicker(startedAt)
            } catch (e: Exception) {
                _lastError.value = e.message ?: "Failed to start logging"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun stop() {
        val device = device ?: return
        val running = _phase.value as? Phase.Running ?: return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val recordsById = container.logSessions.records.value.associateBy { it.id }
                for ((recordId, sensor) in activeSensors) {
                    runCatching { sensor.stopLoggingOn(device, recordsById[recordId]?.polledHandles) }
                        .onFailure { _lastError.value = it.message }
                    container.logSessions.updateStatus(recordId, LogSessionRecord.Status.STOPPED)
                }
                // MMS boards buffer the active flash page in RAM; force-commit
                // so short sessions aren't stranded (no-op on MMRL).
                runCatching { device.flushLogPage() }
                activeSensors.clear()
                elapsedJob?.cancel()
                _elapsedSeconds.value = (Clock.System.now() - running.startedAt).inWholeSeconds.toInt()
                _phase.value = Phase.Stopped
            } finally {
                _isBusy.value = false
            }
        }
    }

    private fun startElapsedTicker(startedAt: Instant) {
        elapsedJob?.cancel()
        elapsedJob = viewModelScope.launch {
            while (isActive) {
                _elapsedSeconds.value = (Clock.System.now() - startedAt).inWholeSeconds.toInt()
                delay(1_000)
            }
        }
    }

    fun clearError() {
        _lastError.value = null
    }
}
