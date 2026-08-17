package com.mbientlab.metawear.app.ui.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.AppScaffold
import com.mbientlab.metawear.app.ui.components.BrandCard
import com.mbientlab.metawear.app.ui.components.Notice
import com.mbientlab.metawear.app.ui.components.SectionFooter
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.ui.theme.Palette
import com.mbientlab.metawear.app.vm.ControlsViewModel
import com.mbientlab.metawear.sensor.Led

/** LED (color, pattern, play/stop) and haptic (duty, pulse, motor/buzzer) controls. */
@Composable
fun ControlsScreen(onBack: () -> Unit) {
    val vm = appViewModel(::ControlsViewModel)
    val ledColor by vm.ledColor.collectAsState()
    val ledPattern by vm.ledPattern.collectAsState()
    val motorDuty by vm.motorDuty.collectAsState()
    val motorPulseMs by vm.motorPulseMs.collectAsState()
    val lastError by vm.lastError.collectAsState()

    AppScaffold(
        title = "Controls",
        onBack = onBack,
        notice = lastError?.let { Notice(it) { vm.clearError() } },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionHeader("LED") }
            item {
                BrandCard(verticalSpacing = 10.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = Palette.accent)
                        Text("Pattern playback", style = MaterialTheme.typography.titleSmall)
                    }
                    ChoiceRow("Color") {
                        Led.Color.entries.forEach { color ->
                            FilterChip(
                                selected = ledColor == color,
                                onClick = { vm.setLedColor(color) },
                                label = { Text(color.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            )
                        }
                    }
                    ChoiceRow("Pattern") {
                        ControlsViewModel.PatternPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = ledPattern == preset,
                                onClick = { vm.setLedPattern(preset) },
                                label = { Text(preset.title) },
                            )
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { vm.playLed() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play")
                        }
                        OutlinedButton(onClick = { vm.stopLedPlayback() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stop")
                        }
                    }
                }
            }

            item { SectionHeader("Haptic") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrandCard(verticalSpacing = 4.dp) {
                        SliderRow(
                            label = "Duty cycle",
                            value = "$motorDuty%",
                        ) {
                            Slider(
                                value = motorDuty.toFloat(),
                                onValueChange = { vm.setMotorDuty(it.toInt()) },
                                valueRange = 0f..100f,
                                steps = 9,
                            )
                        }
                        SliderRow(
                            label = "Pulse width",
                            value = "$motorPulseMs ms",
                        ) {
                            Slider(
                                value = motorPulseMs.toFloat(),
                                onValueChange = { vm.setMotorPulseMs(it.toInt()) },
                                valueRange = 50f..2000f,
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = { vm.pulseMotor() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Vibration, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Motor")
                            }
                            OutlinedButton(onClick = { vm.pulseBuzzer() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Buzzer")
                            }
                        }
                    }
                    SectionFooter("MMR/MMRL boards carry a motor; MMC/MMS route this to the buzzer output.")
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    }
}

@Composable
private fun SliderRow(label: String, value: String, slider: @Composable () -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        slider()
    }
}
