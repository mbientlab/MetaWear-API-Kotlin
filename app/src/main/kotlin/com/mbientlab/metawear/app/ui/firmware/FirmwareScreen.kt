package com.mbientlab.metawear.app.ui.firmware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.ErrorBanner
import com.mbientlab.metawear.app.ui.components.GlassCard
import com.mbientlab.metawear.app.ui.components.LabeledValue
import com.mbientlab.metawear.app.ui.theme.GlassGood
import com.mbientlab.metawear.app.ui.theme.GlassTextDim
import com.mbientlab.metawear.app.vm.FirmwareUpdateViewModel

@Composable
fun FirmwareScreen() {
    val vm = appViewModel(::FirmwareUpdateViewModel)
    val phase by vm.phase.collectAsState()
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Firmware", style = MaterialTheme.typography.headlineSmall) }

        item {
            GlassCard {
                LabeledValue("Installed version", vm.currentVersion ?: "—")
            }
        }

        item {
            when (val p = phase) {
                FirmwareUpdateViewModel.Phase.Unknown -> {
                    Button(onClick = { vm.checkForUpdate() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Check for Update")
                    }
                }

                FirmwareUpdateViewModel.Phase.Checking -> {
                    GlassCard {
                        Text("Checking the MbientLab catalog…", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                FirmwareUpdateViewModel.Phase.UpToDate -> {
                    GlassCard {
                        Text("Firmware is up to date", color = GlassGood, style = MaterialTheme.typography.titleSmall)
                    }
                }

                is FirmwareUpdateViewModel.Phase.UpdateAvailable -> {
                    GlassCard {
                        Text("Update available", style = MaterialTheme.typography.titleSmall)
                        LabeledValue("New version", p.build.firmwareRev)
                        LabeledValue("File", p.build.filename)
                        Button(
                            onClick = { vm.startUpdate(context) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Install Update") }
                        Text(
                            "Keep the board within range and charged. The board reboots into its " +
                                "bootloader during the flash.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTextDim,
                        )
                    }
                }

                is FirmwareUpdateViewModel.Phase.Updating -> {
                    GlassCard {
                        Text(
                            p.progress.state.name.lowercase().replace('_', ' ')
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        LinearProgressIndicator(
                            progress = { (p.progress.percentComplete / 100.0).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Part ${p.progress.currentPart}/${p.progress.totalParts}" +
                                if (p.progress.bytesPerSecond > 0) {
                                    " · ${(p.progress.bytesPerSecond / 1024).toInt()} KB/s"
                                } else {
                                    ""
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTextDim,
                        )
                    }
                }

                FirmwareUpdateViewModel.Phase.Completed -> {
                    GlassCard {
                        Text("Update complete", color = GlassGood, style = MaterialTheme.typography.titleSmall)
                        LabeledValue("Installed version", vm.currentVersion ?: "—")
                    }
                }

                is FirmwareUpdateViewModel.Phase.Failed -> {
                    ErrorBanner(p.message) { vm.reset() }
                }
            }
        }
    }
}
