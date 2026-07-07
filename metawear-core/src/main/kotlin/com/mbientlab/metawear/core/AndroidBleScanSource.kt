package com.mbientlab.metawear.core

import android.annotation.SuppressLint
import android.content.Context
import com.mbientlab.metawear.transport.BleTransport
import com.mbientlab.metawear.transport.ScanResult
import com.mbientlab.metawear.transport.WriteType
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanMode
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerSettings
import no.nordicsemi.android.kotlin.ble.scanner.BleScanner

/**
 * Scan-only [BleTransport] backing [com.mbientlab.metawear.MetaWearScanner] on
 * Android.
 *
 * Only [scan] is functional. Connection members throw
 * [UnsupportedOperationException]: per-peripheral connections are handled by
 * [NordicBleTransport] instances vended through the scanner's device factory
 * (see [AndroidMetaWear.scanner]).
 *
 * ### No service-UUID filter
 * The scan is deliberately unfiltered: MetaWear boards do **not** reliably
 * include the custom service UUID (`326A9000-…`) in their advertisement
 * packets, so an OS-level service filter would hide real boards.
 * [com.mbientlab.metawear.MetaWearScanner] matches on the advertised
 * "MetaWear" name prefix instead. The [scan] `services` argument is accepted
 * to satisfy the [BleTransport] interface but intentionally not forwarded.
 *
 * ### Permissions
 * The caller must hold `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (API 31+) or
 * `BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION` (API ≤ 30) *before* collecting
 * the flow — this library never requests permissions. If Bluetooth is off,
 * the flow fails with the Nordic scanner's `ScanningFailedException`.
 */
@SuppressLint("MissingPermission") // runtime permission grant is the caller's contract, see KDoc
class AndroidBleScanSource(context: Context) : BleTransport {

    private val appContext: Context = context.applicationContext

    override fun scan(services: List<UUID>?): Flow<ScanResult> =
        BleScanner(appContext)
            .scan(
                filters = emptyList(), // no service filter — see class KDoc
                settings = BleScannerSettings(
                    // Foreground, user-visible discovery: report every
                    // advertisement so RSSI and advertised names refresh
                    // continuously while the scan list is on screen.
                    scanMode = BleScanMode.SCAN_MODE_LOW_LATENCY,
                    // Bonded-but-silent devices have no advertisement (and no
                    // RSSI); MetaWearScanner only cares about what's on air.
                    includeStoredBondedDevices = false,
                ),
            )
            .mapNotNull { result ->
                // `data` is only null for synthetic bonded-device entries,
                // which are disabled above — but stay defensive.
                val data = result.data ?: return@mapNotNull null
                ScanResult(
                    identifier = result.device.address,
                    name = data.scanRecord?.deviceName?.takeIf { it.isNotEmpty() }
                        ?: result.device.name,
                    rssi = data.rssi,
                    manufacturerData = data.scanRecord?.manufacturerSpecificData
                        ?.let { manufacturerBlob(it) },
                )
            }

    /**
     * Rebuild the raw advertisement manufacturer-data blob:
     * `ScanResult.manufacturerData` consumers expect
     * `[companyId LE (2 bytes)] + payload` as one buffer, but Android splits
     * it into a SparseArray keyed by company id — re-prepend the id so
     * payloads (e.g. iBeacon frames) parse as they appear on air. MetaWear
     * advertisements carry at most one entry.
     */
    private fun manufacturerBlob(
        data: android.util.SparseArray<no.nordicsemi.android.kotlin.ble.core.data.util.DataByteArray>,
    ): ByteArray? {
        if (data.size() == 0) return null
        val companyId = data.keyAt(0)
        val payload = data.valueAt(0).value
        return byteArrayOf(
            (companyId and 0xFF).toByte(),
            ((companyId shr 8) and 0xFF).toByte(),
        ) + payload
    }

    // ---- Connection members: not supported on the scan source ----

    override suspend fun connect(identifier: String): Nothing = unsupported()
    override suspend fun disconnect(): Nothing = unsupported()
    override suspend fun write(data: ByteArray, characteristic: UUID, type: WriteType): Nothing =
        unsupported()
    override suspend fun read(characteristic: UUID): Nothing = unsupported()
    override fun notifications(characteristic: UUID): Nothing = unsupported()
    override suspend fun readRSSI(): Nothing = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "AndroidBleScanSource only scans; connections go through NordicBleTransport " +
            "(see AndroidMetaWear.scanner)",
    )
}
