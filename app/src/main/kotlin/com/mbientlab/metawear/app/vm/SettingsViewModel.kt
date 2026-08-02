package com.mbientlab.metawear.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.app.AppContainer
import com.mbientlab.metawear.sensor.Settings
import com.mbientlab.metawear.sensor.eraseAllMacros
import com.mbientlab.metawear.sensor.removeAllEvents
import com.mbientlab.metawear.sensor.removeAllTimers
import com.mbientlab.metawear.sensor.stopLed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Device settings: advertising name, advertising parameters, TX power,
 * board maintenance (LED reset, macro/event clears, restart), and the
 * factory-reset flow.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val device: MetaWearDevice? = container.activeDevice()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _didFactoryReset = MutableStateFlow(false)
    val didFactoryReset: StateFlow<Boolean> = _didFactoryReset.asStateFlow()

    fun isNameValid(name: String): Boolean = Settings.isNameValid(name)

    /** Write a new BLE advertising name (validated; ≤ 26 ASCII chars). */
    fun rename(name: String) {
        val device = device ?: return
        viewModelScope.launch {
            runCatching { device.send(Settings.SetDeviceName.validating(name)) }
                .onSuccess {
                    // The cache is known-stale after a rename (a connected
                    // board doesn't advertise) — inject the expected name so
                    // UI updates immediately; the next real advertisement
                    // reconciles it.
                    container.scanner.noteAdvertisedName(device.identifier, name)
                    _statusMessage.value = "Name set to \"$name\" — takes effect on next advertisement"
                }
                .onFailure { _lastError.value = it.message }
        }
    }

    /**
     * Write the advertising interval/timeout. Interval 20–10240 ms
     * (0.625 ms units on the wire); timeout 0 = advertise forever.
     */
    fun setAdvertising(intervalMs: Int, timeoutSec: Int) {
        val device = device ?: return
        viewModelScope.launch {
            runCatching {
                device.send(
                    Settings.SetAdvertisingInterval(
                        intervalMs = intervalMs.coerceIn(20, 10_240),
                        timeoutSec = timeoutSec.coerceIn(0, 180),
                    ),
                )
            }
                .onSuccess { _statusMessage.value = "Advertising set: $intervalMs ms, timeout $timeoutSec s" }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun setTxPower(power: Settings.TxPower) {
        val device = device ?: return
        viewModelScope.launch {
            runCatching { device.send(Settings.SetTxPower(power)) }
                .onSuccess { _statusMessage.value = "TX power set to ${power.raw} dBm" }
                .onFailure { _lastError.value = it.message }
        }
    }

    /**
     * Scrub all on-device state (log entries, loggers, events, processors,
     * macros) and reboot the board. The BLE link drops; the device returns to
     * Disconnected and can be reconnected after ~1 s.
     */
    fun factoryReset() {
        val device = device ?: return
        viewModelScope.launch {
            runCatching { device.factoryReset() }
                .onSuccess {
                    _didFactoryReset.value = true
                    _statusMessage.value = "Factory reset sent — the board is rebooting"
                }
                .onFailure { _lastError.value = it.message }
        }
    }

    // ---- Maintenance ----
    // Field-recovery actions for a misbehaving board, each scoped to one
    // subsystem so users don't reach for factory reset (which costs data).

    /** Stop LED playback and erase all configured patterns. */
    fun resetLed() {
        runMaintenance("LED reset") { it.stopLed(clearPattern = true) }
    }

    /** Erase every recorded macro (flash-resident command replays). */
    fun clearMacros() {
        runMaintenance("Macros erased") { it.eraseAllMacros() }
    }

    /** Remove every event binding and timer (from ALL apps, this one's included). */
    fun clearEvents() {
        runMaintenance("Events & timers cleared") {
            it.removeAllEvents()
            it.removeAllTimers()
        }
    }

    /**
     * Reboot the board WITHOUT erasing anything — log entries and macros
     * survive; wedged volatile state (timers, sensor enables, a stuck NAND
     * pass) clears. The link drops; the device screen offers Reconnect.
     */
    fun restart() {
        val device = device ?: return
        viewModelScope.launch {
            runCatching { device.restart() }
                .onSuccess {
                    _didFactoryReset.value = true   // same "board rebooting" navigation as factory reset
                    _statusMessage.value = "Restart sent — the board is rebooting"
                }
                .onFailure { _lastError.value = it.message }
        }
    }

    private fun runMaintenance(successMessage: String, action: suspend (MetaWearDevice) -> Unit) {
        val device = device ?: return
        viewModelScope.launch {
            runCatching { action(device) }
                .onSuccess { _statusMessage.value = successMessage }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun clearMessages() {
        _statusMessage.value = null
        _lastError.value = null
    }
}
