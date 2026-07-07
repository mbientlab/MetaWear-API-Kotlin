package com.mbientlab.metawear.app.export

import com.mbientlab.metawear.app.core.AnyChartSample
import com.mbientlab.metawear.app.core.SensorSelection
import java.util.Locale

/**
 * CSV export for a live-stream capture buffer: header `time,{labels…}`,
 * ISO-8601 timestamps with fractional seconds, floats at up to 6 fractional
 * digits (US locale), and the `accuracy` column (corrected fusion outputs)
 * printed as an integer.
 */
object LiveBufferCsvExporter {

    fun export(samples: List<AnyChartSample>, selection: SensorSelection): String =
        export(samples, selection.key.axisStyle.labels)

    fun export(samples: List<AnyChartSample>, labels: List<String>): String {
        val lines = ArrayList<String>(samples.size + 1)
        lines.add((listOf("time") + labels).joinToString(","))
        for (sample in samples) {
            val values = labels.mapIndexed { index, label ->
                val v = sample.channel(index)
                if (label == "accuracy") v.toInt().toString() else formatFloat(v)
            }
            lines.add((listOf(sample.time.toString()) + values).joinToString(","))
        }
        return lines.joinToString("\n") + "\n"
    }

    /** Up to 6 fractional digits, no trailing-zero stripping — the format downstream parsers expect. */
    fun formatFloat(value: Float): String = String.format(Locale.US, "%.6f", value)
}
