package com.mbientlab.metawear.model

import java.io.File
import java.util.Locale
import kotlin.reflect.KClass
import kotlinx.datetime.Instant

// Port of MWDataTable.swift — the CSV export table.
//
// CSV byte-stability matters: tools that consume our exports parse the columns
// numerically, and a locale-dependent decimal separator (`1,000000` instead of
// `1.000000`) would silently corrupt downstream analysis. The Swift original
// pins its number formatting to the POSIX locale with no thousands grouping;
// here `Locale.US` with `String.format` gives the same byte-for-byte output
// regardless of the host's region settings.

/** Six-decimal CSV format for `Float`: matches the legacy `String(format: "%.6f", _)` output. */
private fun csv6(value: Float): String = String.format(Locale.US, "%.6f", value)

/** Four-decimal CSV format for `Float`: matches the legacy `String(format: "%.4f", _)` output. */
private fun csv4(value: Float): String = String.format(Locale.US, "%.4f", value)

/** Three-decimal CSV format for `Double`: matches the legacy `String(format: "%.3f", _)` output. */
private fun csv3(value: Double): String = String.format(Locale.US, "%.3f", value)

/**
 * ISO 8601 timestamp with second precision and a `Z` designator — the output
 * shape of Swift's `ISO8601DateFormatter` (e.g. `1970-01-01T00:16:40Z`).
 * Sub-second components are truncated, matching the Swift formatter.
 */
private fun iso8601(instant: Instant): String =
    Instant.fromEpochSeconds(instant.epochSeconds).toString()

/**
 * CSV column mapping for the sample types the SDK produces. Port of the
 * `MWDataConvertible` protocol (Swift).
 *
 * Swift expresses this as retroactive protocol conformances (including on
 * `Float` and `Bool`, which Kotlin cannot extend with new supertypes), so the
 * static `columnHeaders` / instance `columnValues` requirements become
 * when-on-type dispatch here.
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
}

/**
 * A named table of string rows suitable for CSV export. Port of `MWDataTable`
 * (Swift).
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
         * The explicit [sampleType] parameter carries what Swift gets from the
         * static `S.columnHeaders` requirement — it lets an empty sample list
         * still produce the correct header row. Prefer the reified overload.
         */
        fun <S : Any> fromLogged(
            samples: List<LoggedSample<S>>,
            name: String,
            sampleType: KClass<out S>,
        ): DataTable {
            val columns = listOf("epoch", "elapsed_ms") + DataConvertible.columnHeaders(sampleType)
            val rows = samples.map { s ->
                listOf(iso8601(s.date), csv3(s.tickMs)) + DataConvertible.columnValues(s.value)
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
            val columns = listOf("epoch") + DataConvertible.columnHeaders(sampleType)
            val rows = samples.map { s ->
                listOf(iso8601(s.time)) + DataConvertible.columnValues(s.value)
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
