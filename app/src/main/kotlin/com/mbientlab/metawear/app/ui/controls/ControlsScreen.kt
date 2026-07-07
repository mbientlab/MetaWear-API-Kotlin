package com.mbientlab.metawear.app.ui.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.ErrorBanner
import com.mbientlab.metawear.app.ui.components.GlassCard
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.ui.theme.GlassTextDim
import com.mbientlab.metawear.app.vm.ControlsViewModel
import com.mbientlab.metawear.sensor.Led

@Composable
fun ControlsScreen() {
    val vm = appViewModel(::ControlsViewModel)
    val ledColor by vm.ledColor.collectAsState()
    val ledPattern by vm.ledPattern.collectAsState()
    val motorDuty by vm.motorDuty.collectAsState()
    val motorPulseMs by vm.motorPulseMs.collectAsState()
    val lastError by vm.lastError.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Controls", style = MaterialTheme.typography.headlineSmall) }
        item { ErrorBanner(lastError) { vm.clearError() } }

        item { SectionHeader("LED") }
        item {
            GlassCard {
                Text("Color", style = MaterialTheme.typography.labelMedium, color = GlassTextDim)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Led.Color.entries.forEach { color ->
                        FilterChip(
                            selected = ledColor == color,
                            onClick = { vm.setLedColor(color) },
                            label = { Text(color.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                Text("Pattern", style = MaterialTheme.typography.labelMedium, color = GlassTextDim)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ControlsViewModel.PatternPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = ledPattern == preset,
                            onClick = { vm.setLedPattern(preset) },
                            label = { Text(preset.title) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { vm.playLed() }, modifier = Modifier.weight(1f)) { Text("Play") }
                    OutlinedButton(onClick = { vm.stopLedPlayback() }, modifier = Modifier.weight(1f)) {
                        Text("Stop")
                    }
                }
            }
        }

        item { SectionHeader("Haptic") }
        item {
            GlassCard {
                Text("Strength $motorDuty%", style = MaterialTheme.typography.labelMedium, color = GlassTextDim)
                Slider(
                    value = motorDuty.toFloat(),
                    onValueChange = { vm.setMotorDuty(it.toInt()) },
                    valueRange = 0f..100f,
                )
                Text("Pulse $motorPulseMs ms", style = MaterialTheme.typography.labelMedium, color = GlassTextDim)
                Slider(
                    value = motorPulseMs.toFloat(),
                    onValueChange = { vm.setMotorPulseMs(it.toInt()) },
                    valueRange = 50f..2000f,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { vm.pulseMotor() }, modifier = Modifier.weight(1f)) { Text("Buzz Motor") }
                    OutlinedButton(onClick = { vm.pulseBuzzer() }, modifier = Modifier.weight(1f)) {
                        Text("Buzzer")
                    }
                }
                Text(
                    "MMR/MMRL boards carry a motor; MMC/MMS route this to the buzzer output.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassTextDim,
                )
            }
        }
    }
}
