package com.mbientlab.metawear.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.app.AppContainer
import com.mbientlab.metawear.sensor.Haptic
import com.mbientlab.metawear.sensor.Led
import com.mbientlab.metawear.sensor.LedPattern
import com.mbientlab.metawear.sensor.setLed
import com.mbientlab.metawear.sensor.stopLed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * LED + haptic controls. (Battery lives on
 * the device screen instead.)
 */
class ControlsViewModel(container: AppContainer) : ViewModel() {

    /** Named LED pattern presets from the SDK. */
    enum class PatternPreset(val title: String, val pattern: LedPattern) {
        SOLID("Solid", LedPattern.solid),
        BLINK("Blink", LedPattern.blink),
        BREATHE("Breathe", LedPattern.breathe),
        FLASH("Flash", LedPattern.flash),
    }

    private val device: MetaWearDevice? = container.activeDevice()

    private val _ledColor = MutableStateFlow(Led.Color.GREEN)
    val ledColor: StateFlow<Led.Color> = _ledColor.asStateFlow()

    private val _ledPattern = MutableStateFlow(PatternPreset.BLINK)
    val ledPattern: StateFlow<PatternPreset> = _ledPattern.asStateFlow()

    /** Vibration strength in percent (0–100). */
    private val _motorDuty = MutableStateFlow(100)
    val motorDuty: StateFlow<Int> = _motorDuty.asStateFlow()

    /** Pulse width in milliseconds. */
    private val _motorPulseMs = MutableStateFlow(500)
    val motorPulseMs: StateFlow<Int> = _motorPulseMs.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun setLedColor(color: Led.Color) {
        _ledColor.value = color
    }

    fun setLedPattern(preset: PatternPreset) {
        _ledPattern.value = preset
    }

    fun setMotorDuty(duty: Int) {
        _motorDuty.value = duty.coerceIn(0, 100)
    }

    fun setMotorPulseMs(ms: Int) {
        _motorPulseMs.value = ms.coerceIn(1, 65_535)
    }

    fun playLed() {
        val device = device ?: return
        val pattern = _ledPattern.value.pattern
        viewModelScope.launch {
            runCatching {
                when (_ledColor.value) {
                    Led.Color.GREEN -> device.setLed(green = pattern)
                    Led.Color.RED -> device.setLed(red = pattern)
                    Led.Color.BLUE -> device.setLed(blue = pattern)
                }
            }.onFailure { _lastError.value = it.message }
        }
    }

    fun stopLedPlayback() {
        val device = device ?: return
        viewModelScope.launch {
            runCatching { device.stopLed(clearPattern = true) }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun pulseMotor() {
        val device = device ?: return
        viewModelScope.launch {
            runCatching {
                device.send(Haptic.motor(dutyCycle = _motorDuty.value, pulseWidth = _motorPulseMs.value))
            }.onFailure { _lastError.value = it.message }
        }
    }

    fun pulseBuzzer() {
        val device = device ?: return
        viewModelScope.launch {
            runCatching { device.send(Haptic.buzzer(pulseWidth = _motorPulseMs.value)) }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun clearError() {
        _lastError.value = null
    }
}
