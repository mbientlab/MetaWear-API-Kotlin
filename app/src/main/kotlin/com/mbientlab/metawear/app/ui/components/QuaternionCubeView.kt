package com.mbientlab.metawear.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mbientlab.metawear.app.core.QuaternionCube
import com.mbientlab.metawear.app.ui.theme.Palette

/**
 * Dependency-free 3D orientation cube: rotates a wireframe cube by the live
 * fusion quaternion and draws it on a Canvas with a simple orthographic
 * projection. Depth is cued by edge alpha (nearer edges brighter and
 * thicker).
 */
@Composable
fun QuaternionCubeView(
    w: Float,
    x: Float,
    y: Float,
    z: Float,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
) {
    val frameColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        // Rotated cube spans [-√3, √3]; leave a margin.
        val scale = minOf(size.width, size.height) / (2f * 2.0f)

        val vertices = QuaternionCube.rotatedVertices(w, x, y, z)
        // Screen-space orthographic projection: +x right, +y up.
        val projected = vertices.map { Offset(cx + it.x * scale, cy - it.y * scale) }

        for ((a, b) in QuaternionCube.EDGES) {
            // Depth cue: rotated z in [-√3, √3] → alpha 0.25..1, width 1..3 dp.
            val depth = ((vertices[a].z + vertices[b].z) / 2f / 1.75f).coerceIn(-1f, 1f)
            val t = (depth + 1f) / 2f
            drawLine(
                color = Palette.accent.copy(alpha = 0.25f + 0.75f * t),
                start = projected[a],
                end = projected[b],
                strokeWidth = (1f + 2f * t).dp.toPx(),
            )
        }
        // Vertex dots for the front-most corners.
        vertices.forEachIndexed { index, v ->
            if (v.z > 0f) {
                drawCircle(Palette.accent, radius = 3.dp.toPx(), center = projected[index])
            }
        }
        drawRect(frameColor, style = Stroke(1.dp.toPx()))
    }
}
