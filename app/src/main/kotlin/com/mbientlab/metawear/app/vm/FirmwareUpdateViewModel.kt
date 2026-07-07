package com.mbientlab.metawear.app.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.app.AppContainer
import com.mbientlab.metawear.firmware.DFUProgress
import com.mbientlab.metawear.firmware.FirmwareBuild
import com.mbientlab.metawear.firmware.FirmwareServer
import com.mbientlab.metawear.firmware.checkForFirmwareUpdate
import com.mbientlab.metawear.firmware.updateFirmwareToLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Firmware check + DFU flow. Port of `FirmwareUpdateViewModel.swift`:
 * check the MbientLab catalog against the connected board, then stream
 * Nordic-DFU progress through the update. After a completed flash the board
 * reboots into app mode and this VM reconnects to refresh device info.
 */
class FirmwareUpdateViewModel(container: AppContainer) : ViewModel() {

    sealed class Phase {
        data object Unknown : Phase()
        data object Checking : Phase()
        data object UpToDate : Phase()
        data class UpdateAvailable(val build: FirmwareBuild) : Phase()
        data class Updating(val progress: DFUProgress) : Phase()
        data object Completed : Phase()
        data class Failed(val message: String) : Phase()
    }

    private val device: MetaWearDevice? = container.activeDevice()
    private val server = FirmwareServer()

    private val _phase = MutableStateFlow<Phase>(Phase.Unknown)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    val currentVersion: String? get() = device?.deviceInfo?.firmwareRevision

    val isBusy: Boolean
        get() = _phase.value is Phase.Checking || _phase.value is Phase.Updating

    fun checkForUpdate() {
        val device = device ?: return
        if (isBusy) return
        viewModelScope.launch {
            _phase.value = Phase.Checking
            _phase.value = try {
                val build = device.checkForFirmwareUpdate(server)
                if (build == null) Phase.UpToDate else Phase.UpdateAvailable(build)
            } catch (e: Exception) {
                Phase.Failed(e.message ?: "Update check failed")
            }
        }
    }

    fun startUpdate(context: Context) {
        val device = device ?: return
        if (_phase.value !is Phase.UpdateAvailable) return
        if (device.state.value != DeviceState.Idle) {
            _phase.value = Phase.Failed("Device must be idle to update (stop streams/logs first)")
            return
        }
        viewModelScope.launch {
            try {
                device.updateFirmwareToLatest(context.applicationContext, server).collect { progress ->
                    _phase.value = Phase.Updating(progress)
                }
                // The flow completed: board is back in app mode with stale
                // local caches — reconnect to refresh deviceInfo/modules.
                runCatching { device.connect() }
                _phase.value = Phase.Completed
            } catch (e: Exception) {
                _phase.value = Phase.Failed(e.message ?: "Firmware update failed")
            }
        }
    }

    fun reset() {
        _phase.value = Phase.Unknown
    }
}
