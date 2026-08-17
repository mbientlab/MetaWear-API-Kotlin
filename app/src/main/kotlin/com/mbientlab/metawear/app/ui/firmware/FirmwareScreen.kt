package com.mbientlab.metawear.app.ui.firmware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.ui.appViewModel
import com.mbientlab.metawear.app.ui.components.ActionRow
import com.mbientlab.metawear.app.ui.components.AppScaffold
import com.mbientlab.metawear.app.ui.components.GroupCard
import com.mbientlab.metawear.app.ui.components.LabeledValue
import com.mbientlab.metawear.app.ui.components.Notice
import com.mbientlab.metawear.app.ui.components.SectionFooter
import com.mbientlab.metawear.app.ui.components.SectionHeader
import com.mbientlab.metawear.app.ui.components.cardListItemColors
import com.mbientlab.metawear.app.ui.theme.Palette
import com.mbientlab.metawear.app.ui.theme.forText
import com.mbientlab.metawear.app.vm.FirmwareUpdateViewModel
import com.mbientlab.metawear.firmware.DFUProgress

/** Stand-alone firmware page (the same section also lives inside Settings). */
@Composable
fun FirmwareScreen(onBack: () -> Unit) {
    val vm = appViewModel(::FirmwareUpdateViewModel)
    val phase by vm.phase.collectAsState()
    AppScaffold(
        title = "Firmware",
        onBack = onBack,
        notice = (phase as? FirmwareUpdateViewModel.Phase.Failed)?.let { Notice(it.message) { vm.reset() } },
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
            item { SectionHeader("Firmware") }
            item { FirmwareSection(vm) }
        }
    }
}

/**
 * Installed version, a catalog check, and the over-the-air update with a
 * live progress readout — every state of [FirmwareUpdateViewModel.Phase]
 * mapped onto rows of one card.
 */
@Composable
fun FirmwareSection(vm: FirmwareUpdateViewModel) {
    val phase by vm.phase.collectAsState()
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GroupCard {
            LabeledValue("Installed", vm.currentVersion ?: "—", monospace = true)
            HorizontalDivider()
            when (val p = phase) {
                FirmwareUpdateViewModel.Phase.Unknown ->
                    ActionRow("Check for Updates", Icons.Filled.Sync, onClick = { vm.checkForUpdate() })

                FirmwareUpdateViewModel.Phase.Checking -> ListItem(
                    headlineContent = { Text("Checking for updates…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) },
                    colors = cardListItemColors(),
                )

                FirmwareUpdateViewModel.Phase.UpToDate -> {
                    StatusRow("Up to date")
                    HorizontalDivider()
                    ActionRow("Check Again", Icons.Filled.Sync, onClick = { vm.checkForUpdate() })
                }

                is FirmwareUpdateViewModel.Phase.UpdateAvailable -> {
                    LabeledValue("Latest", p.build.firmwareRev, monospace = true)
                    HorizontalDivider()
                    ActionRow("Update Firmware", Icons.Filled.Download, onClick = { vm.startUpdate(context) })
                }

                is FirmwareUpdateViewModel.Phase.Updating -> ProgressRow(p.progress)

                FirmwareUpdateViewModel.Phase.Completed -> StatusRow("Updated")

                is FirmwareUpdateViewModel.Phase.Failed -> {
                    ListItem(
                        headlineContent = { Text(p.message) },
                        leadingContent = { Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.warning) },
                        colors = cardListItemColors(),
                    )
                    HorizontalDivider()
                    ActionRow("Try Again", Icons.Filled.Sync, onClick = { vm.checkForUpdate() })
                }
            }
        }
        when (phase) {
            is FirmwareUpdateViewModel.Phase.Updating -> SectionFooter(
                "Updating firmware. Keep the app open with the board nearby and powered — do not disconnect.",
            )
            is FirmwareUpdateViewModel.Phase.UpdateAvailable -> SectionFooter(
                "Downloads the latest firmware from MbientLab and installs it over Bluetooth. " +
                    "The board restarts when finished.",
            )
            else -> Unit
        }
    }
}

@Composable
private fun StatusRow(text: String) {
    ListItem(
        headlineContent = { Text("Status") },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.success, modifier = Modifier.size(18.dp))
                Text(text, color = Palette.success.forText())
            }
        },
        colors = cardListItemColors(),
    )
}

/** Active DFU phase plus a determinate bar while bytes are transferring. */
@Composable
private fun ProgressRow(progress: DFUProgress) {
    val label = when (progress.state) {
        DFUProgress.State.FETCHING_CATALOG -> "Checking catalog…"
        DFUProgress.State.DOWNLOADING_FIRMWARE -> "Downloading firmware…"
        DFUProgress.State.BOOTLOADER_HANDOFF -> "Preparing device…"
        DFUProgress.State.SCANNING -> "Locating device…"
        DFUProgress.State.CONNECTING -> "Connecting…"
        DFUProgress.State.STARTING -> "Starting transfer…"
        DFUProgress.State.VALIDATING -> "Validating…"
        DFUProgress.State.UPLOADING -> "Installing…"
        DFUProgress.State.DISCONNECTING -> "Finishing up…"
        DFUProgress.State.COMPLETED -> "Complete"
        DFUProgress.State.ABORTED -> "Aborted"
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (progress.totalParts > 1) {
                Text(
                    "Step ${progress.currentPart} of ${progress.totalParts}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (progress.state == DFUProgress.State.UPLOADING) {
                Text(
                    "${progress.percentComplete.toInt()}%",
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (progress.state == DFUProgress.State.UPLOADING) {
            LinearProgressIndicator(
                progress = { (progress.percentComplete / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
