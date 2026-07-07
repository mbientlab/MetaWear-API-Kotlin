package com.mbientlab.metawear.persistence

import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.model.LoggedSample
import com.mbientlab.metawear.model.Quaternion
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Port of the "MWPersistenceStore — CSV export" suite (MWSessionExportTests.swift). */
class SessionExportTest {

    @Test
    fun `exportTable cartesian column headers`() = runTest {
        val store = makeStore()
        val samples = (0 until 3).map { i ->
            LoggedSample(
                date = Instant.fromEpochSeconds(i.toLong()), tickMs = i * 10.0,
                value = CartesianFloat(x = i.toFloat(), y = 0f, z = 0f),
            )
        }
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = samples, persistable = CartesianFloatPersistable,
        )
        val table = store.exportTable(sessionID = snap.id, persistable = CartesianFloatPersistable)
        assertEquals(listOf("epoch", "elapsed_ms", "x", "y", "z"), table.columns)
    }

    @Test
    fun `exportTable quaternion column headers`() = runTest {
        val store = makeStore()
        val s = listOf(
            LoggedSample(
                date = someInstant, tickMs = 0.0,
                value = Quaternion(w = 1f, x = 0f, y = 0f, z = 0f),
            ),
        )
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = QuaternionPersistable.persistenceKind,
            samples = s, persistable = QuaternionPersistable,
        )
        val table = store.exportTable(sessionID = snap.id, persistable = QuaternionPersistable)
        assertEquals(listOf("epoch", "elapsed_ms", "w", "x", "y", "z"), table.columns)
    }

    @Test
    fun `exportTable row count matches sample count`() = runTest {
        val store = makeStore()
        val count = 7
        val samples = (0 until count).map { i ->
            LoggedSample(
                date = Instant.fromEpochSeconds(i.toLong()), tickMs = i.toDouble(),
                value = CartesianFloat(x = 0f, y = 0f, z = 0f),
            )
        }
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = samples, persistable = CartesianFloatPersistable,
        )
        val table = store.exportTable(sessionID = snap.id, persistable = CartesianFloatPersistable)
        assertEquals(count, table.rows.size)
    }

    @Test
    fun `exportTable csvString line count`() = runTest {
        val store = makeStore()
        val count = 5
        val samples = (0 until count).map { i ->
            LoggedSample(
                date = Instant.fromEpochSeconds(i.toLong()), tickMs = i.toDouble(),
                value = CartesianFloat(x = 0f, y = 0f, z = 0f),
            )
        }
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = samples, persistable = CartesianFloatPersistable,
        )
        val table = store.exportTable(sessionID = snap.id, persistable = CartesianFloatPersistable)
        val lines = table.csvString.split("\n")
        // 1 header + count data rows
        assertEquals(count + 1, lines.size)
    }

    @Test
    fun `exportTable epoch is ISO8601`() = runTest {
        val store = makeStore()
        val date = Instant.fromEpochSeconds(1_700_000_000)
        val s = listOf(
            LoggedSample(date = date, tickMs = 0.0, value = CartesianFloat(x = 0f, y = 0f, z = 0f)),
        )
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = s, persistable = CartesianFloatPersistable,
        )
        val table = store.exportTable(sessionID = snap.id, persistable = CartesianFloatPersistable)
        val epochField = table.rows[0][0]
        // Parseable ISO 8601 (the Swift test's ISO8601DateFormatter check) …
        val parsed = Instant.parse(epochField)
        // … and byte-exact for a known instant.
        assertEquals(date, parsed)
        assertEquals("2023-11-14T22:13:20Z", epochField)
    }

    @Test
    fun `exportTable elapsedMs is correct`() = runTest {
        val store = makeStore()
        val s = listOf(
            LoggedSample(
                date = someInstant, tickMs = 12345.678,
                value = CartesianFloat(x = 0f, y = 0f, z = 0f),
            ),
        )
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = s, persistable = CartesianFloatPersistable,
        )
        val table = store.exportTable(sessionID = snap.id, persistable = CartesianFloatPersistable)
        assertEquals("12345.678", table.rows[0][1])
    }
}
