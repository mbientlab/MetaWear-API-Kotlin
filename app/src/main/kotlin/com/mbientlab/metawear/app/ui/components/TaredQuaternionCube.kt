package com.mbientlab.metawear.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mbientlab.metawear.app.core.AnyChartSample
import com.mbientlab.metawear.app.core.QuaternionFrame

/**
 * The live 3D orientation cube with tare: renders rotation **since a
 * reference pose** (auto-set from the first valid sample; re-set by the Zero
 * button) through the IMU mounting correction — never the raw quaternion,
 * whose absolute frame isn't stable session-to-session.
 */
@Composable
fun TaredQuaternionCube(
    latest: AnyChartSample?,
    modifier: Modifier = Modifier,
) {
    var reference by remember { mutableStateOf<QuaternionFrame.Quat?>(null) }

    val raw = latest?.let { QuaternionFrame.quatOrNull(it.f0, it.f1, it.f2, it.f3) }
    if (reference == null && raw != null) {
        // First valid sample becomes the zero pose.
        SideEffect { reference = raw }
    }

    val rendered = raw?.let { QuaternionFrame.rendered(reference ?: it, it) }
        ?: QuaternionFrame.IDENTITY

    Box(modifier = modifier.fillMaxWidth()) {
        QuaternionCubeView(w = rendered.w, x = rendered.x, y = rendered.y, z = rendered.z)
        TextButton(
            onClick = { raw?.let { reference = it } },
            modifier = Modifier.align(Alignment.BottomEnd),
        ) { Text("Zero") }
    }
}
