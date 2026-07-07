package com.mbientlab.metawear.model

import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ported from MWDataTableTests.swift — the `MWDataConvertible`,
 * `MWDataTable Factory`, and `MWDataTable CSV` suites.
 *
 * Where the Swift tests use `Date()` (current wall clock) for samples whose
 * timestamp is irrelevant to the assertion, fixed instants are used instead —
 * assertions never depend on `Clock.System`.
 */
class DataTableTest {

    // ---- DataConvertible: CartesianFloat ----

    @Test
    fun `cartesianFloat headers`() {
        assertEquals(listOf("x", "y", "z"), DataConvertible.columnHeaders(CartesianFloat::class))
    }

    @Test
    fun `cartesianFloat values positive and negative`() {
        val v = CartesianFloat(x = 1.0f, y = -2.5f, z = 0.0f)
        val vals = DataConvertible.columnValues(v)
        assertEquals(3, vals.size)
        assertEquals("1.000000", vals[0])
        assertEquals("-2.500000", vals[1])
        assertEquals("0.000000", vals[2])
    }

    // ---- DataConvertible: Quaternion ----

    @Test
    fun `quaternion headers`() {
        assertEquals(listOf("w", "x", "y", "z"), DataConvertible.columnHeaders(Quaternion::class))
    }

    @Test
    fun `quaternion unit values`() {
        val q = Quaternion(w = 1.0f, x = 0.0f, y = 0.0f, z = 0.0f)
        val vals = DataConvertible.columnValues(q)
        assertEquals(4, vals.size)
        assertEquals("1.000000", vals[0])
        assertEquals("0.000000", vals[1])
    }

    // ---- DataConvertible: EulerAngles ----

    @Test
    fun `eulerAngles headers`() {
        assertEquals(
            listOf("heading", "pitch", "roll", "yaw"),
            DataConvertible.columnHeaders(EulerAngles::class),
        )
    }

    @Test
    fun `eulerAngles values`() {
        val e = EulerAngles(heading = 90.0f, pitch = -45.0f, roll = 0.0f, yaw = 180.0f)
        val vals = DataConvertible.columnValues(e)
        assertEquals(4, vals.size)
        assertEquals("90.0000", vals[0])
        assertEquals("-45.0000", vals[1])
    }

    // ---- DataConvertible: CorrectedCartesianFloat ----

    @Test
    fun `correctedCartesian headers`() {
        assertEquals(
            listOf("x", "y", "z", "accuracy"),
            DataConvertible.columnHeaders(CorrectedCartesianFloat::class),
        )
    }

    @Test
    fun `correctedCartesian includes accuracy`() {
        val v = CorrectedCartesianFloat(x = 1.0f, y = 0.0f, z = 0.0f, accuracy = 3)
        val vals = DataConvertible.columnValues(v)
        assertEquals(4, vals.size)
        assertEquals("3", vals[3])
    }

    // ---- DataConvertible: Float ----

    @Test
    fun `float header`() {
        assertEquals(listOf("value"), DataConvertible.columnHeaders(Float::class))
    }

    @Test
    fun `float value`() {
        val f = 3.14f
        assertEquals(1, DataConvertible.columnValues(f).size)
        assertEquals("3.140000", DataConvertible.columnValues(f)[0])
    }

    // ---- DataConvertible: Boolean ----

    @Test
    fun `bool header`() {
        assertEquals(listOf("value"), DataConvertible.columnHeaders(Boolean::class))
    }

    @Test
    fun `bool true is one`() {
        assertEquals(listOf("1"), DataConvertible.columnValues(true))
    }

    @Test
    fun `bool false is zero`() {
        assertEquals(listOf("0"), DataConvertible.columnValues(false))
    }

    // ---- Factory: streamed ----

    @Test
    fun `streamed columns include epoch then sensor headers`() {
        val samples = listOf(
            Timestamped(
                time = Instant.fromEpochMilliseconds(0),
                value = CartesianFloat(x = 1f, y = 2f, z = 3f),
            ),
        )
        val table = DataTable.fromStreamed(samples, name = "accel")
        assertEquals("accel", table.name)
        assertEquals(listOf("epoch", "x", "y", "z"), table.columns)
    }

    @Test
    fun `streamed row count matches samples`() {
        val samples = (0 until 5).map { i ->
            Timestamped(
                time = Instant.fromEpochMilliseconds(i * 1000L),
                value = CartesianFloat(x = i.toFloat(), y = 0f, z = 0f),
            )
        }
        val table = DataTable.fromStreamed(samples, name = "t")
        assertEquals(5, table.rows.size)
    }

    @Test
    fun `streamed row column count matches headers`() {
        val samples = listOf(
            Timestamped(
                time = Instant.fromEpochMilliseconds(1_600_000_000_000),
                value = CartesianFloat(x = 0f, y = 0f, z = 1f),
            ),
        )
        val table = DataTable.fromStreamed(samples, name = "t")
        assertEquals(table.columns.size, table.rows[0].size)
    }

    @Test
    fun `streamed empty has no rows`() {
        val table = DataTable.fromStreamed(emptyList<Timestamped<CartesianFloat>>(), name = "t")
        assertTrue(table.rows.isEmpty())
        assertEquals(listOf("epoch", "x", "y", "z"), table.columns)
    }

    // ---- Factory: logged ----

    @Test
    fun `logged columns include epoch elapsed then sensor headers`() {
        val s = LoggedSample(
            date = Instant.fromEpochMilliseconds(0),
            tickMs = 0.0,
            value = CartesianFloat(x = 0f, y = 0f, z = 1f),
        )
        val table = DataTable.fromLogged(listOf(s), name = "log")
        assertEquals(listOf("epoch", "elapsed_ms", "x", "y", "z"), table.columns)
    }

    @Test
    fun `logged elapsedMs formatted to three decimals`() {
        val s = LoggedSample(
            date = Instant.fromEpochMilliseconds(0),
            tickMs = 1234.5,
            value = CartesianFloat(x = 0f, y = 0f, z = 0f),
        )
        val table = DataTable.fromLogged(listOf(s), name = "t")
        assertEquals("1234.500", table.rows[0][1])
    }

    @Test
    fun `logged row column count matches headers`() {
        val s = LoggedSample(
            date = Instant.fromEpochMilliseconds(1_600_000_000_000),
            tickMs = 0.0,
            value = Quaternion(w = 1f, x = 0f, y = 0f, z = 0f),
        )
        val table = DataTable.fromLogged(listOf(s), name = "t")
        assertEquals(table.columns.size, table.rows[0].size)
    }

    // ---- CSV ----

    @Test
    fun `csvString first line is header`() {
        val table = DataTable(name = "t", columns = listOf("a", "b"), rows = listOf(listOf("1", "2")))
        val lines = table.csvString.split("\n")
        assertEquals("a,b", lines[0])
    }

    @Test
    fun `csvString data rows`() {
        val table = DataTable(
            name = "t",
            columns = listOf("a", "b"),
            rows = listOf(listOf("1", "2"), listOf("3", "4")),
        )
        val lines = table.csvString.split("\n")
        assertEquals(3, lines.size)
        assertEquals("1,2", lines[1])
        assertEquals("3,4", lines[2])
    }

    @Test
    fun `csvString empty rows only header`() {
        val table = DataTable(name = "t", columns = listOf("a", "b"), rows = emptyList())
        val lines = table.csvString.split("\n")
        assertEquals(1, lines.size)
        assertEquals("a,b", lines[0])
    }

    @Test
    fun `csvString quotes field with comma`() {
        val table = DataTable(name = "t", columns = listOf("v"), rows = listOf(listOf("hello,world")))
        assertTrue(table.csvString.contains("\"hello,world\""))
    }

    @Test
    fun `csvString quotes field with quote`() {
        val table = DataTable(name = "t", columns = listOf("v"), rows = listOf(listOf("say \"hi\"")))
        // Should be escaped: "say ""hi"""
        assertTrue(table.csvString.contains("\"say \"\"hi\"\"\""))
    }

    @Test
    fun `csvString plain field not quoted`() {
        val table = DataTable(name = "t", columns = listOf("v"), rows = listOf(listOf("1.234")))
        assertFalse(table.csvString.contains("\""))
    }

    @Test
    fun `csv roundtrip column count`() {
        val samples = listOf(
            Timestamped(
                time = Instant.fromEpochMilliseconds(1_000_000), // 1000 s since epoch
                value = CartesianFloat(x = 1.0f, y = 2.0f, z = 3.0f),
            ),
        )
        val table = DataTable.fromStreamed(samples, name = "accel")
        val lines = table.csvString.split("\n")
        val headerCols = lines[0].split(",").size
        val dataCols = lines[1].split(",").size
        assertEquals(headerCols, dataCols)
        assertEquals(4, headerCols) // epoch + x + y + z
    }
}
