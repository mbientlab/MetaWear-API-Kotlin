package com.mbientlab.metawear.app

import android.bluetooth.BluetoothManager
import android.content.Context
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.MetaWearScanner
import com.mbientlab.metawear.app.data.LogSessionRegistry
import com.mbientlab.metawear.app.data.RememberedDeviceStore
import com.mbientlab.metawear.app.demo.DemoBleTransport
import com.mbientlab.metawear.core.AndroidMetaWear
import com.mbientlab.metawear.persistence.PersistenceDatabase
import com.mbientlab.metawear.persistence.PersistenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Long-lived app services — the Kotlin analogue of the Swift `AppStore`.
 * One instance per process, owned by [MetaWearApplication]; ViewModels reach
 * it through the factory in `AppViewModel.kt`.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Process-lifetime host for SDK routers, scan collection, demo emitters. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The app-wide scanner (one per process, per SDK guidance). */
    val scanner: MetaWearScanner by lazy { AndroidMetaWear.scanner(appContext, appScope) }

    /** Room-backed session store shared by logging, download, and history. */
    val persistence: PersistenceStore by lazy {
        PersistenceStore(PersistenceDatabase.create(appContext))
    }

    /** Boards the user connected to before (MAC-keyed, prefs-backed). */
    val remembered: RememberedDeviceStore by lazy {
        RememberedDeviceStore(appContext.getSharedPreferences("metawear_app", Context.MODE_PRIVATE))
    }

    /** In-memory log-session records pending download. */
    val logSessions = LogSessionRegistry()

    /** Identifier of the device the detail screens operate on. */
    val activeDeviceId = MutableStateFlow<String?>(null)

    /** Demo-mode toggle: surfaces the simulated device in the scan list. */
    val demoModeEnabled = MutableStateFlow(false)

    /**
     * Fully simulated MetaMotion S (see [DemoBleTransport]) — lazily created,
     * reused across connect/disconnect cycles, never persisted as remembered.
     */
    val demoDevice: MetaWearDevice by lazy {
        MetaWearDevice(DemoBleTransport.DEVICE_IDENTIFIER, DemoBleTransport(appScope), appScope)
    }

    /** Resolve an identifier to a device (demo or scanner-vended). */
    fun device(identifier: String): MetaWearDevice =
        if (identifier == DemoBleTransport.DEVICE_IDENTIFIER) demoDevice
        else scanner.deviceForKnownIdentifier(identifier)

    /** The device the detail screens operate on. Null before any selection. */
    fun activeDevice(): MetaWearDevice? = activeDeviceId.value?.let { device(it) }

    /** True when the platform has a Bluetooth adapter and it is enabled. */
    fun isBluetoothAvailable(): Boolean {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }
}
