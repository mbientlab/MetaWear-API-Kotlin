package com.mbientlab.metawear

import com.mbientlab.metawear.transport.BleTransport
import com.mbientlab.metawear.transport.Uuids
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
 * Discovery state is exposed as [StateFlow] maps for Compose/ViewModel
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

    companion object {
        /**
         * Whether an advertisement belongs to a MetaWear board running
         * application firmware, by either of two signals:
         *  1. The default local-name prefix ("MetaWear").
         *  2. The MetaWear service UUID — boards advertise it regardless of
         *     how they've been RENAMED, so a board called "bob" still counts.
         *
         * MetaBoot-mode boards match neither (name "MetaBoot", Nordic DFU
         * service) and stay excluded on purpose — the normal connect flow
         * can't talk to a bootloader.
         */
        fun isMetaWearAdvertisement(name: String, serviceUUIDs: List<String>): Boolean {
            if (name.startsWith("MetaWear")) return true
            val service = Uuids.service.toString()
            return serviceUUIDs.any { it.equals(service, ignoreCase = true) }
        }
    }

    // ---- Public state ----

    private val _discoveredDevices = MutableStateFlow<Map<String, MetaWearDevice>>(emptyMap())

    /**
     * Devices admitted by [isMetaWearAdvertisement] (default name prefix or
     * the advertised MetaWear service UUID), keyed by identifier.
     */
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
     * UUID in advertisements; admission goes through [isMetaWearAdvertisement]
     * (name prefix or, when the packet does carry it, the service UUID — which
     * is what keeps RENAMED boards discoverable).
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
                // the admission filter (rename observability).
                if (_advertisedNames.value[id] != name) {
                    _advertisedNames.update { it + (id to name) }
                }
                if (_advertisementRssi.value[id] != result.rssi) {
                    _advertisementRssi.update { it + (id to result.rssi) }
                }
                // Accept only MetaWear peripherals, once each.
                if (!isMetaWearAdvertisement(name, result.serviceUUIDs)) return@collect
                if (_discoveredDevices.value.containsKey(id)) return@collect
                // Promote the known-peripheral instance instead of minting a
                // twin. Two MetaWearDevice instances for one identifier means
                // two transports racing over one peripheral — the loser's
                // connection state goes dark. With several remembered boards
                // reconnecting while a scan runs, that race would be routine.
                val device = knownDevices.remove(id) ?: deviceFactory(id)
                _discoveredDevices.update { it + (id to device) }
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
     * Override the cached advertised name for an identifier.
     *
     * For rename flows: after a settings device-name write the cache is
     * KNOWN-stale — a connected board doesn't advertise, so no observation
     * will correct it until after disconnect. Callers inject the expected
     * name so UI keyed off this cache updates immediately; the next real
     * advertisement reconciles it with the truth.
     */
    fun noteAdvertisedName(identifier: String, name: String) {
        _advertisedNames.update { it + (identifier to name) }
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
     * air. Repeated calls return the same instance, and if the scanner
     * subsequently discovers the same identifier on air that SAME instance is
     * promoted into [discoveredDevices] — callers never observe two
     * [MetaWearDevice] instances for one identifier.
     */
    fun deviceForKnownIdentifier(identifier: String): MetaWearDevice {
        _discoveredDevices.value[identifier]?.let { return it }
        knownDevices[identifier]?.let { return it }
        return deviceFactory(identifier).also { knownDevices[identifier] = it }
    }
}
