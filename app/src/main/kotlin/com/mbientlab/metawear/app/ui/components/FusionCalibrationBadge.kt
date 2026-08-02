package com.mbientlab.metawear.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.CalibrationReadiness
import com.mbientlab.metawear.app.ui.theme.GlassAccent
import com.mbientlab.metawear.app.ui.theme.GlassError
import com.mbientlab.metawear.app.ui.theme.GlassGood
import com.mbientlab.metawear.app.ui.theme.GlassTextDim
import com.mbientlab.metawear.app.ui.theme.GlassWarn
import com.mbientlab.metawear.sensor.SensorFusionCalibration

/**
 * Live fusion-calibration badge: three A/G/M accuracy chips plus per-sensor
 * coaching. The bar is MEDIUM — a demoted-indoors magnetometer must not read
 * as a problem.
 */
@Composable
fun FusionCalibrationBadge(calibration: SensorFusionCalibration) {
    val state = CalibrationReadiness.state(calibration)

    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                CalibrationReadiness.title(state),
                style = MaterialTheme.typography.titleSmall,
                color = when (state) {
                    CalibrationReadiness.State.FULLY_CALIBRATED -> GlassGood
                    CalibrationReadiness.State.READY -> GlassAccent
                    CalibrationReadiness.State.CALIBRATING -> GlassWarn
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LevelChip("A", calibration.accelerometer)
                LevelChip("G", calibration.gyroscope)
                LevelChip("M", calibration.magnetometer)
            }
        }

        when (state) {
            CalibrationReadiness.State.FULLY_CALIBRATED -> Unit
            CalibrationReadiness.State.READY -> Text(
                CalibrationReadiness.READY_FOOTNOTE,
                style = MaterialTheme.typography.bodySmall,
                color = GlassTextDim,
            )
            CalibrationReadiness.State.CALIBRATING -> {
                CalibrationReadiness.coaching(calibration).forEach { coaching ->
                    Text(
                        "${coaching.sensor}: ${coaching.advice}",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTextDim,
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelChip(label: String, level: Int) {
    val color = when {
        level <= 0 -> GlassError
        level == 1 -> GlassWarn
        level == 2 -> GlassAccent
        else -> GlassGood
    }
    Text(
        "$label $level",
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFF06222E),
        modifier = Modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
