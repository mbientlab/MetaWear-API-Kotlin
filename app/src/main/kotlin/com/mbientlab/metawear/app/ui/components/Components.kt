package com.mbientlab.metawear.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.app.ui.theme.GlassAccent
import com.mbientlab.metawear.app.ui.theme.GlassError
import com.mbientlab.metawear.app.ui.theme.GlassGood
import com.mbientlab.metawear.app.ui.theme.GlassSurfaceHigh
import com.mbientlab.metawear.app.ui.theme.GlassTextDim
import com.mbientlab.metawear.app.ui.theme.GlassWarn

/** Translucent rounded card — the app's "glass" surface treatment. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = GlassTextDim,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Label on the left, value on the right — the settings-list staple. */
@Composable
fun LabeledValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = GlassTextDim, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Colored capsule reporting the device connection state. */
@Composable
fun StateBadge(state: DeviceState) {
    val (label, color) = when (state) {
        DeviceState.Disconnected -> "Disconnected" to GlassTextDim
        DeviceState.Connecting -> "Connecting…" to GlassWarn
        DeviceState.Idle -> "Connected" to GlassGood
        DeviceState.Streaming -> "Streaming" to GlassAccent
        DeviceState.Logging -> "Logging" to GlassAccent
        is DeviceState.Downloading -> "Downloading ${(state.progress * 100).toInt()}%" to GlassAccent
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .background(GlassSurfaceHigh, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Signal-strength pill: rough RSSI to quality words + color. */
@Composable
fun RssiPill(rssi: Int?) {
    val (label, color) = when {
        rssi == null -> "—" to GlassTextDim
        rssi >= -60 -> "$rssi dBm" to GlassGood
        rssi >= -80 -> "$rssi dBm" to GlassWarn
        else -> "$rssi dBm" to GlassError
    }
    Text(label, style = MaterialTheme.typography.labelMedium, color = color)
}

/** Inline error banner with dismissal. */
@Composable
fun ErrorBanner(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                color = GlassError,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
