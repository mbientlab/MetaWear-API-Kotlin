package com.mbientlab.metawear.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.CalibrationReadiness
import com.mbientlab.metawear.app.ui.theme.Palette
import com.mbientlab.metawear.sensor.SensorFusionCalibration

/**
 * Live fusion-calibration card: readiness headline, three A/G/M accuracy
 * chips, and per-sensor coaching for whichever sensor is still below the
 * bar. The bar is MEDIUM — a demoted-indoors magnetometer must not read as
 * a problem.
 */
@Composable
fun FusionCalibrationBadge(calibration: SensorFusionCalibration) {
    val state = CalibrationReadiness.state(calibration)
    val (icon, tint) = when (state) {
        CalibrationReadiness.State.CALIBRATING -> Icons.Filled.CenterFocusStrong to Palette.warning
        CalibrationReadiness.State.READY -> Icons.Filled.CheckCircle to Palette.info
        CalibrationReadiness.State.FULLY_CALIBRATED -> Icons.Filled.Verified to Palette.success
    }

    BrandCard(verticalSpacing = 6.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint)
            Text(
                CalibrationReadiness.title(state),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            LevelChip("A", calibration.accelerometer)
            LevelChip("G", calibration.gyroscope)
            LevelChip("M", calibration.magnetometer)
        }

        when (state) {
            CalibrationReadiness.State.FULLY_CALIBRATED -> Unit
            CalibrationReadiness.State.READY -> Text(
                CalibrationReadiness.READY_FOOTNOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CalibrationReadiness.State.CALIBRATING -> {
                CalibrationReadiness.coaching(calibration).forEach { coaching ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            coaching.sensor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(14.dp),
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            coaching.advice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** One accuracy chip: white letter on a level-colored capsule. */
@Composable
private fun LevelChip(label: String, level: Int) {
    val color = when {
        level <= 0 -> Palette.danger
        level == 1 -> Palette.warning
        level == 2 -> Palette.info
        else -> Palette.success
    }
    Surface(shape = CircleShape, color = color, contentColor = Color.White) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .size(width = 26.dp, height = 22.dp)
                .padding(top = 2.dp),
        )
    }
}
