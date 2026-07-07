package com.mbientlab.metawear.core

import android.content.Context
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.MetaWearScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Android entry point for the MetaWear SDK — wires the platform BLE pieces
 * ([AndroidBleScanSource] + [NordicBleTransport]) into the platform-agnostic
 * [MetaWearScanner] / [MetaWearDevice] from `:metawear-protocol`.
 *
 * ```kotlin
 * val scanner = AndroidMetaWear.scanner(applicationContext)
 * scanner.startScan()
 * scanner.discoveredDevices.collect { devices -> /* MAC → MetaWearDevice */ }
 * ```
 */
object AndroidMetaWear {

    /**
     * Create the app-wide [MetaWearScanner]. Create **one** per process and
     * share it; every discovered board is vended as a [MetaWearDevice] backed
     * by its own [NordicBleTransport].
     *
     * ### Runtime permissions — the caller's responsibility
     * This library declares the Bluetooth permissions in its manifest but
     * never requests them. Before calling [MetaWearScanner.startScan] or
     * [MetaWearDevice.connect], the app must have been **granted**:
     * - API 31+: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`
     * - API 26–30: `BLUETOOTH` / `BLUETOOTH_ADMIN` and `ACCESS_FINE_LOCATION`
     *
     * Scanning with the permissions missing fails the scan flow; connecting
     * without `BLUETOOTH_CONNECT` throws a `SecurityException` from the stack.
     *
     * @param context any [Context]; only [Context.getApplicationContext] is retained.
     * @param scope host for scan collection and each device's protocol router.
     *   Defaults to a process-lifetime supervisor scope; pass your own (e.g. a
     *   ViewModel scope) to tie SDK work to a narrower lifecycle.
     */
    fun scanner(
        context: Context,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ): MetaWearScanner {
        val appContext = context.applicationContext
        return MetaWearScanner(
            scanTransport = AndroidBleScanSource(appContext),
            scope = scope,
            deviceFactory = { mac ->
                MetaWearDevice(mac, NordicBleTransport(appContext, mac), scope)
            },
        )
    }
}
