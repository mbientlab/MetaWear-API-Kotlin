package com.mbientlab.metawear.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.app.AppContainer
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.data.ConfiguredSensor
import com.mbientlab.metawear.app.data.openStream
import com.mbientlab.metawear.app.data.saveLiveBuffer
import com.mbientlab.metawear.app.data.stopStream
import kotlinx.coroutines.Dispatchers
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
 * Live-streaming session: one [Channel] per selected sensor, BLE consume
 * coroutines feeding plain ring buffers, and a ~33 ms throttle loop that
 * snapshots into observable state. Port of `StreamSessionViewModel.swift`.
 */
class StreamSessionViewModel(private val container: AppContainer) : ViewModel() {

    private companion object {
        /** Throttle-loop cadence (~30 fps UI refresh). */
        const val TICK_MS = 33L
    }

    private val device: MetaWearDevice? = container.activeDevice()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _startedAt = MutableStateFlow<Instant?>(null)
    val startedAt: StateFlow<Instant?> = _startedAt.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val sensors = mutableListOf<Pair<ConfiguredSensor, Channel>>()
    private val streamJobs = mutableListOf<Job>()
    private var tickerJob: Job? = null
    private var hasArchived = false

    /** Configure channels and spawn one BLE stream per selection. */
    fun start(selections: List<SensorSelection>) {
        val device = device ?: return
        if (_isStreaming.value || selections.isEmpty()) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                sensors.clear()
                hasArchived = false
                val channelList = selections.map { selection ->
                    val channel = Channel(selection)
                    sensors.add(ConfiguredSensor.make(selection, device.modules) to channel)
                    channel
                }
                _channels.value = channelList
                _startedAt.value = Clock.System.now()
                _isPaused.value = false
                spawnStreams(device)
                _isStreaming.value = true
                startTicker()
            } catch (e: Exception) {
                _lastError.value = e.message ?: "Failed to start streaming"
                teardownStreams(device)
                _isStreaming.value = false
            } finally {
                _isBusy.value = false
            }
        }
    }

    /**
     * Consume tasks: each collects its typed BLE stream into the channel's
     * plain ring buffers off the main thread. Never touches Compose state —
     * the ticker publishes at a fixed cadence regardless of sample rate.
     */
    private suspend fun spawnStreams(device: MetaWearDevice) {
        for ((sensor, channel) in sensors) {
            val flow = sensor.openStream(device)
            streamJobs += viewModelScope.launch(Dispatchers.Default) {
                flow.collect { sample ->
                    if (!_isPaused.value) channel.ingest(sample)
                }
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (!_isPaused.value) {
                    _channels.value.forEach { it.publish() }
                }
            }
        }
    }

    /**
     * Freeze the charts instantly (synchronous flag flip) and tear the BLE
     * streams down in the background; buffers and counts are preserved.
     */
    fun pause() {
        val device = device ?: return
        if (!_isStreaming.value || _isPaused.value || _isBusy.value) return
        _isPaused.value = true
        viewModelScope.launch {
            _isBusy.value = true
            try {
                teardownStreams(device)
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Re-spawn the BLE streams; buffers continue where they left off. */
    fun resume() {
        val device = device ?: return
        if (!_isStreaming.value || !_isPaused.value || _isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                spawnStreams(device)
                _isPaused.value = false
            } catch (e: Exception) {
                _lastError.value = e.message ?: "Failed to resume"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun togglePause() = if (_isPaused.value) resume() else pause()

    /** Stop the session, archive each channel's capture buffer to history. */
    fun stop() {
        val device = device ?: return
        if (!_isStreaming.value) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                teardownStreams(device)
                tickerJob?.cancel()
                tickerJob = null
                _channels.value.forEach { it.publish() }   // final snapshot
                _isStreaming.value = false
                _isPaused.value = false
                archiveToHistory(device)
            } finally {
                _isBusy.value = false
            }
        }
    }

    private suspend fun teardownStreams(device: MetaWearDevice) {
        streamJobs.forEach { it.cancel() }
        streamJobs.clear()
        for ((sensor, _) in sensors) {
            runCatching { sensor.stopStream(device) }
                .onFailure { _lastError.value = it.message }
        }
    }

    /**
     * Save each channel's full-resolution ring as one persisted session.
     * Idempotent: repeated stop taps save once.
     */
    private suspend fun archiveToHistory(device: MetaWearDevice) {
        if (hasArchived) return
        hasArchived = true
        val info = device.deviceInfo ?: return
        val startedAt = _startedAt.value ?: return
        for ((sensor, channel) in sensors) {
            runCatching {
                sensor.saveLiveBuffer(
                    store = container.persistence,
                    deviceId = device.identifier,
                    deviceInfo = info,
                    samples = channel.captureBuffer(),
                    startedAt = startedAt,
                )
            }.onFailure { _lastError.value = "Archive failed: ${it.message}" }
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    override fun onCleared() {
        // ViewModel scope dies with this screen; make a best effort to stop
        // board-side sampling so the sensors don't drain the battery.
        val device = device ?: return
        if (_isStreaming.value) {
            container.appScope.launch {
                for ((sensor, _) in sensors) {
                    runCatching { sensor.stopStream(device) }
                }
            }
        }
    }
}
