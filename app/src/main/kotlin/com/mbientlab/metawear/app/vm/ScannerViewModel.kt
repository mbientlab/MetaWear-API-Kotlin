package com.mbientlab.metawear.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbientlab.metawear.app.AppContainer
import com.mbientlab.metawear.app.core.DeviceFreshness
import com.mbientlab.metawear.app.demo.DemoBleTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Scan-screen state: nearby devices merged from the SDK scanner's StateFlows,
 * remembered devices from the prefs store, plus the demo device when demo
 * mode is on.
 */
class ScannerViewModel(private val container: AppContainer) : ViewModel() {

    /** One row in the nearby list. */
    data class NearbyDevice(
        val identifier: String,
        val name: String,
        val rssi: Int?,
        val isDemo: Boolean = false,
    )

    private val scanner = container.scanner

    val isScanning: StateFlow<Boolean> = scanner.isScanning
    val demoModeEnabled: StateFlow<Boolean> = container.demoModeEnabled
    val remembered = container.remembered.devices

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Last time each identifier was heard from. The Kotlin scanner exposes
     * name/RSSI maps rather than raw advertisement events, so liveness is
     * approximated from map churn — good enough to age out silent boards via
     * [DeviceFreshness].
     */
    private val lastSeen = MutableStateFlow<Map<String, Instant>>(emptyMap())

    init {
        viewModelScope.launch {
            var previousRssi = emptyMap<String, Int>()
            combine(scanner.advertisedNames, scanner.advertisementRssi) { names, rssi ->
                names.keys to rssi
            }.collect { (ids, rssi) ->
                val now = Clock.System.now()
                val stamped = lastSeen.value.toMutableMap()
                for (id in ids + rssi.keys) {
                    // Stamp newly seen ids and ids whose RSSI value changed —
                    // the closest proxy for "advertisement heard" the scanner's
                    // deduplicated StateFlows offer.
                    if (id !in stamped || rssi[id] != previousRssi[id]) stamped[id] = now
                }
                previousRssi = rssi
                lastSeen.value = stamped
            }
        }
    }

    /** Nearby devices: discovered + fresh, demo device prepended when enabled. */
    val nearbyDevices: StateFlow<List<NearbyDevice>> = combine(
        scanner.discoveredDevices,
        scanner.advertisedNames,
        scanner.advertisementRssi,
        container.demoModeEnabled,
        lastSeen,
    ) { discovered, names, rssi, demo, seen ->
        val now = Clock.System.now()
        val rows = discovered.keys
            .filter { DeviceFreshness.isFresh(seen[it], now) || !isScanningNow() }
            .map { id ->
                NearbyDevice(
                    identifier = id,
                    name = names[id]?.ifEmpty { null } ?: "MetaWear",
                    rssi = rssi[id],
                )
            }
            .sortedBy { it.name }
        if (demo) {
            listOf(NearbyDevice(DemoBleTransport.DEVICE_IDENTIFIER, DemoBleTransport.DEVICE_NAME, rssi = -52, isDemo = true)) + rows
        } else {
            rows
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun isScanningNow(): Boolean = scanner.isScanning.value

    fun isBluetoothAvailable(): Boolean = container.isBluetoothAvailable()

    fun toggleScan() {
        if (scanner.isScanning.value) stopScan() else startScan()
    }

    fun startScan() {
        if (!container.isBluetoothAvailable()) {
            _lastError.value = "Bluetooth is unavailable — try demo mode"
            return
        }
        runCatching { scanner.startScan() }
            .onFailure { _lastError.value = it.message ?: "Scan failed" }
    }

    fun stopScan() {
        runCatching { scanner.stopScan() }
    }

    fun setDemoMode(enabled: Boolean) {
        container.demoModeEnabled.value = enabled
    }

    /** Mark [identifier] active and hand off to the device detail flow. */
    fun select(identifier: String) {
        container.activeDeviceId.value = identifier
    }

    fun forget(mac: String) {
        container.remembered.forget(mac)
    }

    fun clearError() {
        _lastError.value = null
    }

    override fun onCleared() {
        runCatching { scanner.stopScan() }
    }
}
