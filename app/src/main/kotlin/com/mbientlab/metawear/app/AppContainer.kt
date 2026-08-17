package com.mbientlab.metawear.app

import android.bluetooth.BluetoothManager
import android.content.Context
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.MetaWearScanner
import com.mbientlab.metawear.app.data.LogSessionRegistry
import com.mbientlab.metawear.app.data.RememberedDeviceStore
import com.mbientlab.metawear.app.vm.GroupCaptureCoordinator
import com.mbientlab.metawear.core.AndroidMetaWear
import com.mbientlab.metawear.persistence.PersistenceDatabase
import com.mbientlab.metawear.persistence.PersistenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Long-lived app services shared across ViewModels.
 * One instance per process, owned by [MetaWearApplication]; ViewModels reach
 * it through the factory in `AppViewModel.kt`.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Process-lifetime host for SDK routers and scan collection. */
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

    /**
     * Log-session records pending download — prefs-backed so pending sessions
     * (including board-allocated polled-logger handles) survive process death.
     */
    val logSessions: LogSessionRegistry by lazy {
        LogSessionRegistry(appContext.getSharedPreferences("metawear_app", Context.MODE_PRIVATE))
    }

    /** Identifier of the device the detail screens operate on. */
    val activeDeviceId = MutableStateFlow<String?>(null)

    /** Fleet-wide group logging coordinator (survives navigation). */
    val groupCapture: GroupCaptureCoordinator by lazy {
        GroupCaptureCoordinator(persistence, logSessions)
    }

    /** Resolve an identifier to its scanner-vended device. */
    fun device(identifier: String): MetaWearDevice = scanner.deviceForKnownIdentifier(identifier)

    /**
     * Best display name for a board right now: live advertised name, then the
     * remembered bookmark, then nothing. Used to stamp sessions at capture
     * time — names are unrecoverable later (boards go off air; the advertised
     * cache is per-host).
     */
    fun displayNameFor(identifier: String): String? =
        scanner.advertisedNames.value[identifier]?.ifEmpty { null }
            ?: remembered.devices.value.firstOrNull { it.mac == identifier }?.name

    /** The device the detail screens operate on. Null before any selection. */
    fun activeDevice(): MetaWearDevice? = activeDeviceId.value?.let { device(it) }

    /** True when the platform has a Bluetooth adapter and it is enabled. */
    fun isBluetoothAvailable(): Boolean {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }
}
