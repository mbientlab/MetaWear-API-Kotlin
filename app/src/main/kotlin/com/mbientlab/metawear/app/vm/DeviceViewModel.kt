package com.mbientlab.metawear.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.app.AppContainer
import com.mbientlab.metawear.app.data.ForeignLogDecision
import com.mbientlab.metawear.app.data.LogDownloader
import com.mbientlab.metawear.app.data.OrphanLogState
import com.mbientlab.metawear.app.data.RememberedDevice
import com.mbientlab.metawear.app.data.foreignLogDecision
import com.mbientlab.metawear.app.demo.DemoBleTransport
import com.mbientlab.metawear.clearLog
import com.mbientlab.metawear.model.BatteryState
import com.mbientlab.metawear.queryActiveLoggers
import com.mbientlab.metawear.sensor.LogLength
import com.mbientlab.metawear.sensor.LoggingEnabled
import com.mbientlab.metawear.model.DeviceInformation
import com.mbientlab.metawear.model.ModuleInfo
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.sensor.LedPattern
import com.mbientlab.metawear.sensor.Settings
import com.mbientlab.metawear.sensor.setLed
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Connection lifecycle + identity for the active device: connects on first
 * appearance, reads battery/MAC once settled, polls battery every 60 s, and
 * upserts the remembered-device bookmark after a successful connect.
 */
class DeviceViewModel(private val container: AppContainer) : ViewModel() {

    val device: MetaWearDevice? = container.activeDevice()

    val state: StateFlow<DeviceState> =
        device?.state ?: MutableStateFlow(DeviceState.Disconnected)

    private val _deviceInfo = MutableStateFlow<DeviceInformation?>(null)
    val deviceInfo: StateFlow<DeviceInformation?> = _deviceInfo.asStateFlow()

    private val _battery = MutableStateFlow<BatteryState?>(null)
    val battery: StateFlow<BatteryState?> = _battery.asStateFlow()

    private val _macAddress = MutableStateFlow<String?>(null)
    val macAddress: StateFlow<String?> = _macAddress.asStateFlow()

    private val _modules = MutableStateFlow<Map<Module, ModuleInfo>>(emptyMap())
    val modules: StateFlow<Map<Module, ModuleInfo>> = _modules.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    /** A foreign log discovered on connect (someone else's session) — offer download/discard. */
    private val _foreignLog = MutableStateFlow<OrphanLogState?>(null)
    val foreignLog: StateFlow<OrphanLogState?> = _foreignLog.asStateFlow()

    private val _foreignStatus = MutableStateFlow<String?>(null)
    val foreignStatus: StateFlow<String?> = _foreignStatus.asStateFlow()

    /** Display name: advertised name, remembered name, or the identifier. */
    val displayName: String
        get() {
            val id = device?.identifier ?: return "MetaWear"
            if (id == DemoBleTransport.DEVICE_IDENTIFIER) return DemoBleTransport.DEVICE_NAME
            return container.scanner.advertisedNames.value[id]?.ifEmpty { null }
                ?: container.remembered.devices.value.firstOrNull { it.mac == id }?.name
                ?: id
        }

    private var batteryPollJob: Job? = null

    private companion object {
        val BATTERY_POLL_PERIOD = 60.seconds
    }

    init {
        connectIfNeeded()
    }

    fun connectIfNeeded() {
        val device = device ?: return
        if (device.state.value != DeviceState.Disconnected) {
            // Already connected in a previous visit: refresh identity reads.
            onConnected(device)
            return
        }
        viewModelScope.launch {
            _isConnecting.value = true
            try {
                device.connect()
                onConnected(device)
            } catch (e: Exception) {
                _lastError.value = e.message ?: "Connection failed"
            } finally {
                _isConnecting.value = false
            }
        }
    }

    private fun onConnected(device: MetaWearDevice) {
        _deviceInfo.value = device.deviceInfo
        _modules.value = device.modules
        rememberDevice(device)
        viewModelScope.launch { refreshBattery() }
        viewModelScope.launch {
            _macAddress.value = runCatching { device.read(Settings.ReadMacAddress()).value }.getOrNull()
        }
        viewModelScope.launch { evaluateForeignLog(device) }
        startBatteryPolling(device)
    }

    /**
     * Decide what to do about any log on the board's flash: this app's own
     * pending session is left alone; decodable foreign data (or a session in
     * progress) is surfaced for download/discard; undecodable leftover
     * entries are cleared silently.
     */
    private suspend fun evaluateForeignLog(device: MetaWearDevice) {
        val entryCount = runCatching { device.read(LogLength()).value }.getOrNull() ?: return
        val loggingEnabled = runCatching { device.read(LoggingEnabled()).value }.getOrNull() ?: false
        // null = enumeration failed → err on the side of surfacing.
        val hasActiveLoggers = runCatching { device.queryActiveLoggers().isNotEmpty() }.getOrNull()
        val hasLocalPending = container.logSessions.pendingFor(device.identifier).isNotEmpty()

        when (val decision = foreignLogDecision(entryCount, hasActiveLoggers, loggingEnabled, hasLocalPending)) {
            is ForeignLogDecision.Surface ->
                _foreignLog.value = OrphanLogState(entryCount, device.identifier, decision.isActivelyLogging)
            ForeignLogDecision.SilentClear -> {
                runCatching { device.clearLog() }
                _foreignLog.value = null
            }
            ForeignLogDecision.LeaveAlone -> _foreignLog.value = null
        }
    }

    /** Recover the surfaced foreign log into session history. */
    fun downloadForeignLog() {
        val device = device ?: return
        val state = _foreignLog.value ?: return
        viewModelScope.launch {
            _foreignStatus.value = "Recovering the board's log…"
            val result = LogDownloader.downloadForeign(
                device, container.persistence, state,
                deviceName = container.displayNameFor(device.identifier),
            )
            if (result.failed) {
                _foreignStatus.value = result.message
            } else {
                _foreignLog.value = null
                _foreignStatus.value = result.warning
                    ?: "Recovered ${result.snapshots.size} session(s) into history"
            }
        }
    }

    /** Discard the surfaced foreign log (erases the board's entries). */
    fun discardForeignLog() {
        val device = device ?: return
        viewModelScope.launch {
            runCatching { device.clearLog() }
                .onSuccess {
                    _foreignLog.value = null
                    _foreignStatus.value = "Foreign log discarded"
                }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun clearForeignStatus() {
        _foreignStatus.value = null
    }

    private fun startBatteryPolling(device: MetaWearDevice) {
        batteryPollJob?.cancel()
        batteryPollJob = viewModelScope.launch {
            while (isActive) {
                delay(BATTERY_POLL_PERIOD)
                if (device.state.value == DeviceState.Idle) refreshBattery()
            }
        }
    }

    suspend fun refreshBattery() {
        val device = device ?: return
        _battery.value = runCatching { device.read(Settings.ReadBatteryState()).value }.getOrNull()
            ?: _battery.value
    }

    private fun rememberDevice(device: MetaWearDevice) {
        // The demo device is never persisted.
        if (device.identifier == DemoBleTransport.DEVICE_IDENTIFIER) return
        val info = device.deviceInfo
        container.remembered.upsert(
            RememberedDevice(
                mac = device.identifier,
                name = displayName,
                lastConnectedEpochMs = System.currentTimeMillis(),
                serialNumber = info?.serialNumber,
                firmwareRevision = info?.firmwareRevision,
                modelNumber = info?.modelNumber,
            ),
        )
    }

    fun disconnect() {
        val device = device ?: return
        viewModelScope.launch {
            runCatching { device.disconnect() }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun reconnect() = connectIfNeeded()

    /** Flash the green LED so the user can spot the board on a desk. */
    fun identify() {
        val device = device ?: return
        viewModelScope.launch {
            runCatching { device.setLed(green = LedPattern.flash) }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun clearError() {
        _lastError.value = null
    }
}
