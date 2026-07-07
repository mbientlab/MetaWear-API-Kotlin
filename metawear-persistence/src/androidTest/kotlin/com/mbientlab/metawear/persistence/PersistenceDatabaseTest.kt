package com.mbientlab.metawear.persistence

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.model.DeviceInformation
import com.mbientlab.metawear.model.LoggedSample
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round-trips against the real Room database (in-memory) — the on-device
 * counterpart of the JVM store tests, verifying what the JVM fake can only
 * mirror: the generated DAO's SQL (sort orders, sample counts, scoped
 * deletes), the sessionId foreign-key cascade, and the Instant/epoch-millis
 * type converter.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceDatabaseTest {

    private lateinit var database: PersistenceDatabase
    private lateinit var store: PersistenceStore

    @Before
    fun createDatabase() {
        database = PersistenceDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        store = PersistenceStore(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    // ---- Test fixtures ----

    private fun makeDeviceInfo(serial: String = "AA:BB:CC:DD:EE:FF") = DeviceInformation(
        manufacturer = "MbientLab Inc.",
        modelNumber = "4",
        serialNumber = serial,
        firmwareRevision = "1.7.0",
        hardwareRevision = "0.4",
    )

    private fun randomDeviceID(): String = UUID.randomUUID().toString()

    private fun cartesianSamples(count: Int): List<LoggedSample<CartesianFloat>> =
        (0 until count).map { i ->
            LoggedSample(
                date = Instant.fromEpochSeconds(i.toLong()),
                tickMs = i * 10.0,
                value = CartesianFloat(x = i * 0.1f, y = i * 0.2f, z = i * 0.3f),
            )
        }

    private fun saveCartesian(
        deviceID: String = randomDeviceID(),
        samples: List<LoggedSample<CartesianFloat>> = cartesianSamples(3),
    ): SessionSnapshot = runBlocking {
        store.saveSession(
            deviceID = deviceID,
            deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = samples,
            persistable = CartesianFloatPersistable,
        )
    }

    // ---- Save / fetch ----

    @Test
    fun saveSession_returnsSnapshot_withSampleCount() {
        val deviceID = randomDeviceID()
        val snapshot = saveCartesian(deviceID = deviceID, samples = cartesianSamples(5))
        assertEquals(deviceID, snapshot.deviceID)
        assertEquals("cartesian", snapshot.sensorKind)
        assertEquals(5, snapshot.sampleCount)
    }

    @Test
    fun fetchSessions_countsSamples_viaSql() = runBlocking {
        val deviceID = randomDeviceID()
        saveCartesian(deviceID = deviceID, samples = cartesianSamples(4))
        val sessions = store.fetchSessions(deviceID = deviceID)
        assertEquals(1, sessions.size)
        assertEquals(4, sessions[0].sampleCount)
    }

    @Test
    fun cartesianSamples_roundTrip() = runBlocking {
        val original = cartesianSamples(3)
        val snap = saveCartesian(samples = original)

        val restored = store.fetchSamples(sessionID = snap.id, persistable = CartesianFloatPersistable)
        assertEquals(3, restored.size)
        for ((o, r) in original.zip(restored)) {
            assertEquals(o.value.x, r.value.x, 1e-5f)
            assertEquals(o.value.y, r.value.y, 1e-5f)
            assertEquals(o.value.z, r.value.z, 1e-5f)
            assertEquals(o.tickMs, r.tickMs, 1e-9)
            assertEquals(o.date, r.date)
        }
    }

    @Test
    fun fetchSessions_sortedNewestFirst_viaSql() = runBlocking {
        val deviceID = randomDeviceID()
        val older = listOf(
            LoggedSample(
                date = Instant.fromEpochSeconds(1000), tickMs = 0.0,
                value = CartesianFloat(x = 0f, y = 0f, z = 0f),
            ),
        )
        val newer = listOf(
            LoggedSample(
                date = Instant.fromEpochSeconds(2000), tickMs = 0.0,
                value = CartesianFloat(x = 1f, y = 1f, z = 1f),
            ),
        )
        saveCartesian(deviceID = deviceID, samples = older)
        saveCartesian(deviceID = deviceID, samples = newer)

        val sessions = store.fetchSessions(deviceID = deviceID)
        assertEquals(2, sessions.size)
        assertTrue(sessions[0].startDate > sessions[1].startDate)
    }

    @Test
    fun fetchSessions_scopedToDevice_viaSql() = runBlocking {
        val deviceA = randomDeviceID()
        val deviceB = randomDeviceID()
        saveCartesian(deviceID = deviceA)
        saveCartesian(deviceID = deviceB)

        val resultA = store.fetchSessions(deviceID = deviceA)
        assertEquals(1, resultA.size)
        assertEquals(deviceA, resultA[0].deviceID)
    }

    @Test
    fun fetchSamples_sortedByTickMs_viaSql() = runBlocking {
        val scrambled = listOf(
            LoggedSample(
                date = Instant.fromEpochSeconds(2), tickMs = 20.0,
                value = CartesianFloat(x = 2f, y = 0f, z = 0f),
            ),
            LoggedSample(
                date = Instant.fromEpochSeconds(0), tickMs = 0.0,
                value = CartesianFloat(x = 0f, y = 0f, z = 0f),
            ),
            LoggedSample(
                date = Instant.fromEpochSeconds(1), tickMs = 10.0,
                value = CartesianFloat(x = 1f, y = 0f, z = 0f),
            ),
        )
        val snap = saveCartesian(samples = scrambled)

        val restored = store.fetchSamples(sessionID = snap.id, persistable = CartesianFloatPersistable)
        assertTrue(restored[0].tickMs < restored[1].tickMs)
        assertTrue(restored[1].tickMs < restored[2].tickMs)
    }

    // ---- Converter ----

    @Test
    fun instantConverter_preservesEpochMillis() = runBlocking {
        val date = Instant.fromEpochMilliseconds(1_700_000_000_123)
        val snap = saveCartesian(
            samples = listOf(
                LoggedSample(date = date, tickMs = 0.0, value = CartesianFloat(x = 0f, y = 0f, z = 0f)),
            ),
        )
        val restored = store.fetchSamples(sessionID = snap.id, persistable = CartesianFloatPersistable)
        assertEquals(date, restored[0].date)
    }

    // ---- Errors ----

    @Test
    fun fetchSamples_kindMismatch_throws() = runBlocking {
        val snap = saveCartesian()
        val error = runCatching {
            store.fetchSamples(sessionID = snap.id, persistable = QuaternionPersistable)
        }.exceptionOrNull()
        assertTrue(error is PersistenceException.KindMismatch)
    }

    @Test
    fun fetchSamples_unknownID_throws() = runBlocking {
        val snap = saveCartesian()
        store.deleteSession(id = snap.id)
        val error = runCatching {
            store.fetchSamples(sessionID = snap.id, persistable = CartesianFloatPersistable)
        }.exceptionOrNull()
        assertTrue(error is PersistenceException.SessionNotFound)
    }

    // ---- Delete / cascade ----

    @Test
    fun deleteSession_cascadesToSamples() = runBlocking {
        val snap = saveCartesian(samples = cartesianSamples(3))
        val dao = database.persistenceDao()
        assertEquals(3, dao.samplesForSession(snap.id).size)

        store.deleteSession(id = snap.id)

        assertTrue(store.fetchAllSessions().isEmpty())
        // The FK cascade — not the store — must have removed the child rows.
        assertTrue(dao.samplesForSession(snap.id).isEmpty())
    }

    @Test
    fun deleteAllSessions_onlyRemovesMatchingDevice() = runBlocking {
        val deviceA = randomDeviceID()
        val deviceB = randomDeviceID()
        saveCartesian(deviceID = deviceA)
        saveCartesian(deviceID = deviceB)

        store.deleteAllSessions(deviceID = deviceA)

        assertTrue(store.fetchSessions(deviceID = deviceA).isEmpty())
        assertEquals(1, store.fetchSessions(deviceID = deviceB).size)
    }

    @Test
    fun deleteAll_clearsEverything() = runBlocking {
        saveCartesian()
        saveCartesian()
        store.deleteAll()
        assertTrue(store.fetchAllSessions().isEmpty())
    }

    // ---- Export ----

    @Test
    fun exportTable_onRealDatabase() = runBlocking {
        val snap = saveCartesian(
            samples = listOf(
                LoggedSample(
                    date = Instant.fromEpochSeconds(1_700_000_000), tickMs = 12345.678,
                    value = CartesianFloat(x = 0f, y = 0f, z = 0f),
                ),
            ),
        )
        val table = store.exportTable(sessionID = snap.id, persistable = CartesianFloatPersistable)
        assertEquals(listOf("epoch", "elapsed_ms", "x", "y", "z"), table.columns)
        assertEquals("2023-11-14T22:13:20Z", table.rows[0][0])
        assertEquals("12345.678", table.rows[0][1])
    }
}
