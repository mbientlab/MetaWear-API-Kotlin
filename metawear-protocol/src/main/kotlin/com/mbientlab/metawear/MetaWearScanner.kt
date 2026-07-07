package com.mbientlab.metawear

import com.mbientlab.metawear.transport.BleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Scans for MetaWear peripherals and vends [MetaWearDevice] instances.
 * Port of the Swift `MetaWearScanner` (`@Observable @MainActor` class), with
 * observable dictionaries translated to [StateFlow] maps for Compose/ViewModel
 * consumption.
 *
 * One scanner per app. The scan itself runs on [scanTransport]; each
 * discovered peripheral gets its own device from [deviceFactory] (production
 * wires a per-peripheral Nordic transport there; tests return mock-backed
 * devices).
 */
class MetaWearScanner(
    private val scanTransport: BleTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val deviceFactory: (identifier: String) -> MetaWearDevice,
) {
    // ---- Public state ----

    private val _discoveredDevices = MutableStateFlow<Map<String, MetaWearDevice>>(emptyMap())

    /** Devices whose advertised name starts with "MetaWear", keyed by identifier. */
    val discoveredDevices: StateFlow<Map<String, MetaWearDevice>> = _discoveredDevices.asStateFlow()

    private val _advertisedNames = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Most-recently-seen advertised local name for each peripheral observed —
     * updated on every advertisement, **before** the MetaWear-prefix filter, so
     * a device renamed via settings (no longer advertising as "MetaWear…")
     * remains observable by identifier.
     */
    val advertisedNames: StateFlow<Map<String, String>> = _advertisedNames.asStateFlow()

    private val _advertisementRssi = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Most-recently-seen RSSI (dBm) per peripheral, refreshed on every advertisement. */
    val advertisementRssi: StateFlow<Map<String, Int>> = _advertisementRssi.asStateFlow()

    private val _isScanning = MutableStateFlow(false)

    /** True while a scan collection is active. */
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // ---- Private ----

    private var scanJob: Job? = null

    /**
     * Devices vended via [deviceForKnownIdentifier] for peripherals not yet
     * seen this session (e.g. a remembered device the user reconnects to before
     * it re-advertises). Kept separate so [discoveredDevices] stays a faithful
     * "currently visible on air" set.
     */
    private val knownDevices = mutableMapOf<String, MetaWearDevice>()

    // ---- Scanning ----

    /**
     * Start observing BLE advertisements. Scans without a service filter
     * because MetaWear boards do not consistently include the custom service
     * UUID in advertisements; devices are matched on the "MetaWear" name prefix.
     */
    fun startScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        scanJob = scope.launch {
            scanTransport.scan(null).collect { result ->
                val id = result.identifier
                val name = result.name ?: ""
                // Advertisements repeat several times per second per board;
                // guard writes on actual change so StateFlow subscribers aren't
                // invalidated at advertising rate. Names are recorded before
                // the prefix filter (rename observability).
                if (_advertisedNames.value[id] != name) {
                    _advertisedNames.update { it + (id to name) }
                }
                if (_advertisementRssi.value[id] != result.rssi) {
                    _advertisementRssi.update { it + (id to result.rssi) }
                }
                // Accept only MetaWear peripherals, once each.
                if (!name.startsWith("MetaWear")) return@collect
                if (_discoveredDevices.value.containsKey(id)) return@collect
                _discoveredDevices.update { it + (id to deviceFactory(id)) }
            }
        }
    }

    /** Stop the active scan and leave already-discovered devices cached. */
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    /**
     * Forget the cached advertised name for an identifier so the next scan must
     * observe a fresh advertisement — used to verify a rename reached the air.
     */
    fun clearAdvertisedName(identifier: String) {
        _advertisedNames.update { it - identifier }
    }

    /**
     * Return a [MetaWearDevice] for a peripheral known by identifier (typically
     * remembered from a previous session) without requiring re-discovery on
     * air. Repeated calls return the same instance; a live [discoveredDevices]
     * entry takes precedence.
     */
    fun deviceForKnownIdentifier(identifier: String): MetaWearDevice {
        _discoveredDevices.value[identifier]?.let { return it }
        knownDevices[identifier]?.let { return it }
        return deviceFactory(identifier).also { knownDevices[identifier] = it }
    }
}
