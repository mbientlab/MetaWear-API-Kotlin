package com.mbientlab.metawear.app.export

import com.mbientlab.metawear.app.core.AnyChartSample
import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.model.Quaternion
import com.mbientlab.metawear.model.derivedEulerAngles
import java.util.Locale

/**
 * CSV export for a live-stream capture buffer: header `time,{labels…}`,
 * ISO-8601 timestamps with fractional seconds, floats at up to 6 fractional
 * digits (US locale), and the `accuracy` column (corrected fusion outputs)
 * printed as an integer.
 *
 * Quaternion buffers additionally get derived `heading,pitch,roll` columns
 * (4 fractional digits) computed with the firmware's Euler convention — the
 * same derivation the persisted-session export uses — so spreadsheet users
 * get human-readable angles without doing quaternion math.
 */
object LiveBufferCsvExporter {

    fun export(samples: List<AnyChartSample>, selection: SensorSelection): String =
        export(
            samples,
            selection.key.axisStyle.labels,
            deriveEuler = selection.key == SensorKey.FUSION_QUATERNION,
        )

    fun export(samples: List<AnyChartSample>, labels: List<String>, deriveEuler: Boolean = false): String {
        val header = listOf("time") + labels + if (deriveEuler) listOf("heading", "pitch", "roll") else emptyList()
        val lines = ArrayList<String>(samples.size + 1)
        lines.add(header.joinToString(","))
        for (sample in samples) {
            val values = labels.mapIndexed { index, label ->
                val v = sample.channel(index)
                if (label == "accuracy") v.toInt().toString() else formatFloat(v)
            }
            val derived = if (deriveEuler) {
                val e = Quaternion(sample.f0, sample.f1, sample.f2, sample.f3).derivedEulerAngles
                listOf(formatAngle(e.heading), formatAngle(e.pitch), formatAngle(e.roll))
            } else {
                emptyList()
            }
            lines.add((listOf(sample.time.toString()) + values + derived).joinToString(","))
        }
        return lines.joinToString("\n") + "\n"
    }

    /** Up to 6 fractional digits, no trailing-zero stripping — the format downstream parsers expect. */
    fun formatFloat(value: Float): String = String.format(Locale.US, "%.6f", value)

    /** Angles print at 4 fractional digits, matching the session-export table. */
    fun formatAngle(value: Float): String = String.format(Locale.US, "%.4f", value)
}
