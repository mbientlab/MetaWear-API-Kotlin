package com.mbientlab.metawear.model

import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.reflect.KClass
import kotlinx.datetime.Instant

// The CSV export table.
//
// CSV byte-stability matters: tools that consume our exports parse the columns
// numerically, and a locale-dependent decimal separator (`1,000000` instead of
// `1.000000`) would silently corrupt downstream analysis. `Locale.US` with
// `String.format` pins the number formatting (period decimal separator, no
// thousands grouping) so output is byte-for-byte identical regardless of the
// host's region settings.

/** Six-decimal CSV format for `Float` (e.g. `1.000000`). */
private fun csv6(value: Float): String = String.format(Locale.US, "%.6f", value)

/** Four-decimal CSV format for `Float` (e.g. `1.0000`). */
private fun csv4(value: Float): String = String.format(Locale.US, "%.4f", value)

/** Three-decimal CSV format for `Double` (e.g. `1.000`). */
private fun csv3(value: Double): String = String.format(Locale.US, "%.3f", value)

/**
 * ISO 8601 UTC timestamp with whole-second precision and a `Z` designator
 * (e.g. `1970-01-01T00:16:40Z`); sub-second components are truncated.
 */
private fun iso8601(instant: Instant): String =
    Instant.fromEpochSeconds(instant.epochSeconds).toString()

/**
 * Euler angles computed from this quaternion (Tait-Bryan, degrees): heading
 * normalized to 0..360°, pitch −90..+90°, roll −180..+180°.
 *
 * Axis convention MATCHED TO THE FIRMWARE'S OWN EULER OUTPUT on hardware
 * (fw 1.7.x NDoF), via paired CSV captures of both channels at the same
 * static poses — all three angles agreed to under 1° at two distinct tilts.
 * In terms of the classic ZYX derivation: heading is the classic yaw about
 * `z` (unchanged), the firmware's "pitch" is the NEGATED classic roll about
 * `x`, and its "roll" is the NEGATED classic pitch about `y`. (Note the
 * firmware's pitch is therefore the ±180°-range angle and its roll the
 * ±90°-bounded one — opposite of what the type's doc ranges imply; the radio
 * wins.)
 *
 * `yaw` is set equal to `heading`: the firmware's separate yaw channel is
 * gyroscope-INTEGRATED (unbounded, drifts) and cannot be reconstructed from a
 * single orientation quaternion. No derived-yaw CSV column is emitted for the
 * same reason — it would just duplicate heading.
 */
val Quaternion.derivedEulerAngles: EulerAngles
    get() {
        val qw = w.toDouble()
        val qx = x.toDouble()
        val qy = y.toDouble()
        val qz = z.toDouble()
        val pitch = -atan2(2 * (qw * qx + qy * qz), 1 - 2 * (qx * qx + qy * qy))
        val roll = -asin((2 * (qw * qy - qz * qx)).coerceIn(-1.0, 1.0))
        val yaw = atan2(2 * (qw * qz + qx * qy), 1 - 2 * (qy * qy + qz * qz))
        var heading = yaw * 180.0 / PI
        if (heading < 0) heading += 360.0
        // `+ 0.0` collapses IEEE −0.0 to +0.0 so identity rows print "0.0000".
        return EulerAngles(
            heading = heading.toFloat(),
            pitch = (pitch * 180.0 / PI + 0.0).toFloat(),
            roll = (roll * 180.0 / PI + 0.0).toFloat(),
            yaw = heading.toFloat(),
        )
    }

/**
 * CSV column mapping for the sample types the SDK produces.
 *
 * Built-in types (`Float`, `Boolean`) can't be given new supertypes, so the
 * mapping is centralized when-on-type dispatch rather than an interface each
 * sample type implements.
 */
object DataConvertible {

    /**
     * Column header names for the sensor-specific fields of [sampleType]
     * (excludes timestamp columns).
     *
     * @throws MetaWearException.OperationFailed for types without a CSV mapping.
     */
    fun columnHeaders(sampleType: KClass<*>): List<String> = when (sampleType) {
        CartesianFloat::class -> listOf("x", "y", "z")
        Quaternion::class -> listOf("w", "x", "y", "z")
        EulerAngles::class -> listOf("heading", "pitch", "roll", "yaw")
        CorrectedCartesianFloat::class -> listOf("x", "y", "z", "accuracy")
        Float::class -> listOf("value")
        Boolean::class -> listOf("value")
        else -> throw MetaWearException.OperationFailed(
            "No CSV column mapping for ${sampleType.simpleName}",
        )
    }

    /**
     * String representation of each sensor-specific field of [sample], in the
     * same order as [columnHeaders].
     *
     * @throws MetaWearException.OperationFailed for types without a CSV mapping.
     */
    fun columnValues(sample: Any): List<String> = when (sample) {
        is CartesianFloat -> listOf(csv6(sample.x), csv6(sample.y), csv6(sample.z))
        is Quaternion -> listOf(csv6(sample.w), csv6(sample.x), csv6(sample.y), csv6(sample.z))
        is EulerAngles -> listOf(
            csv4(sample.heading), csv4(sample.pitch), csv4(sample.roll), csv4(sample.yaw),
        )
        is CorrectedCartesianFloat -> listOf(
            csv6(sample.x), csv6(sample.y), csv6(sample.z), "${sample.accuracy}",
        )
        is Float -> listOf(csv6(sample))
        is Boolean -> listOf(if (sample) "1" else "0")
        else -> throw MetaWearException.OperationFailed(
            "No CSV column mapping for ${sample::class.simpleName}",
        )
    }

    /**
     * Headers for host-computed convenience columns appended AFTER the raw
     * fields (e.g. Euler angles derived from a quaternion). Deriving at
     * export keeps the stored samples raw while sparing spreadsheet users
     * the quaternion math. Empty for types with nothing to derive.
     */
    fun derivedColumnHeaders(sampleType: KClass<*>): List<String> = when (sampleType) {
        Quaternion::class -> listOf("heading", "pitch", "roll")
        else -> emptyList()
    }

    /** Values parallel to [derivedColumnHeaders]. */
    fun derivedColumnValues(sample: Any): List<String> = when (sample) {
        is Quaternion -> {
            val e = sample.derivedEulerAngles
            listOf(csv4(e.heading), csv4(e.pitch), csv4(e.roll))
        }
        else -> emptyList()
    }
}

/**
 * A named table of string rows suitable for CSV export.
 */
data class DataTable(
    /** Logical name for the table (typically the sensor key, e.g. `"acceleration"`). */
    val name: String,
    /** Column header strings, in order. */
    val columns: List<String>,
    /** Data rows, each as a list of strings parallel to [columns]. */
    val rows: List<List<String>>,
) {

    // ---- CSV export ----

    /** The table rendered as a CSV string (header row + one row per sample). */
    val csvString: String
        get() {
            val lines = mutableListOf(columns.joinToString(","))
            for (row in rows) {
                lines.add(
                    row.joinToString(",") { field ->
                        // Quote fields that contain commas or quotes
                        if (field.contains(",") || field.contains("\"")) {
                            "\"" + field.replace("\"", "\"\"") + "\""
                        } else {
                            field
                        }
                    },
                )
            }
            return lines.joinToString("\n")
        }

    /** Write the CSV to a file. */
    fun writeCsv(file: File) {
        file.writeText(csvString, Charsets.UTF_8)
    }

    // ---- Factory methods ----

    companion object {

        /**
         * Build a table from typed logged samples.
         * Columns: epoch (ISO 8601), elapsed_ms, then sensor-specific columns.
         *
         * The explicit [sampleType] parameter lets an empty sample list still
         * produce the correct header row. Prefer the reified overload.
         */
        fun <S : Any> fromLogged(
            samples: List<LoggedSample<S>>,
            name: String,
            sampleType: KClass<out S>,
        ): DataTable {
            val columns = listOf("epoch", "elapsed_ms") +
                DataConvertible.columnHeaders(sampleType) +
                DataConvertible.derivedColumnHeaders(sampleType)
            val rows = samples.map { s ->
                listOf(iso8601(s.date), csv3(s.tickMs)) +
                    DataConvertible.columnValues(s.value) +
                    DataConvertible.derivedColumnValues(s.value)
            }
            return DataTable(name = name, columns = columns, rows = rows)
        }

        /** Reified-type convenience for [fromLogged]. */
        inline fun <reified S : Any> fromLogged(
            samples: List<LoggedSample<S>>,
            name: String,
        ): DataTable = fromLogged(samples, name, S::class)

        /**
         * Build a table from a streamed sample list.
         * Columns: epoch (ISO 8601), then sensor-specific columns.
         */
        fun <S : Any> fromStreamed(
            samples: List<Timestamped<S>>,
            name: String,
            sampleType: KClass<out S>,
        ): DataTable {
            val columns = listOf("epoch") +
                DataConvertible.columnHeaders(sampleType) +
                DataConvertible.derivedColumnHeaders(sampleType)
            val rows = samples.map { s ->
                listOf(iso8601(s.time)) +
                    DataConvertible.columnValues(s.value) +
                    DataConvertible.derivedColumnValues(s.value)
            }
            return DataTable(name = name, columns = columns, rows = rows)
        }

        /** Reified-type convenience for [fromStreamed]. */
        inline fun <reified S : Any> fromStreamed(
            samples: List<Timestamped<S>>,
            name: String,
        ): DataTable = fromStreamed(samples, name, S::class)
    }
}
