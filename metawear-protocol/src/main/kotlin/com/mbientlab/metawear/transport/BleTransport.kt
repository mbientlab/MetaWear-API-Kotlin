package com.mbientlab.metawear.transport

import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * One BLE advertisement normalized for the SDK scanner.
 */
data class ScanResult(
    /**
     * Stable identifier for the advertising peripheral. On Android this is the
     * device MAC address ("AA:BB:CC:DD:EE:FF"); the seam uses an opaque string
     * so other transports can supply whatever stable identifier they have.
     */
    val identifier: String,
    /** Advertised local name, if present in the packet. */
    val name: String?,
    /** Received signal strength in dBm for this advertisement. */
    val rssi: Int,
    /** Raw manufacturer-specific data bytes, if present (iBeacon payloads etc.). */
    val manufacturerData: ByteArray? = null,
    /**
     * Advertised service UUID strings, as they appeared in the packet.
     * MetaWear boards advertise their custom service UUID here regardless of
     * how they've been renamed — the scanner's admission rule relies on it.
     */
    val serviceUUIDs: List<String> = emptyList(),
) {
    // ByteArray forces manual equals/hashCode for a data class.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanResult) return false
        return identifier == other.identifier &&
            name == other.name &&
            rssi == other.rssi &&
            manufacturerData.contentEquals(other.manufacturerData) &&
            serviceUUIDs == other.serviceUUIDs
    }

    override fun hashCode(): Int {
        var result = identifier.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + rssi
        result = 31 * result + (manufacturerData?.contentHashCode() ?: 0)
        result = 31 * result + serviceUUIDs.hashCode()
        return result
    }
}

/** GATT write mode. */
enum class WriteType {
    /** Acknowledged write — the peripheral confirms receipt. */
    WITH_RESPONSE,

    /** Unacknowledged write — faster, used for the MetaWear command stream. */
    WITHOUT_RESPONSE,
}

/**
 * Platform-agnostic BLE interface.
 *
 * The Nordic-backed Android implementation lives in `:metawear-core`; swap in
 * [MockBleTransport] for hardware-free unit tests. Implementations must
 * serialize GATT operations internally (Android allows only one outstanding
 * GATT operation per connection).
 */
interface BleTransport {

    /**
     * Scan for peripherals advertising any of [services] (all peripherals when
     * `null`). Cancel the collecting coroutine to stop scanning.
     */
    fun scan(services: List<UUID>?): Flow<ScanResult>

    /** Connect to a peripheral by [identifier]. Throws if connection fails or times out. */
    suspend fun connect(identifier: String)

    /** Disconnect the current peripheral. */
    suspend fun disconnect()

    /** Write [data] to a characteristic. */
    suspend fun write(data: ByteArray, characteristic: UUID, type: WriteType)

    /** Perform a one-shot read from a characteristic. */
    suspend fun read(characteristic: UUID): ByteArray

    /**
     * Subscribe to notifications from a characteristic.
     * The flow terminates with an exception on BLE disconnect.
     */
    fun notifications(characteristic: UUID): Flow<ByteArray>

    /**
     * Read the current RSSI of the active connection, in dBm. Requires an
     * established connection; values closer to zero mean a stronger signal.
     */
    suspend fun readRSSI(): Int
}
