package com.mbientlab.metawear.persistence

import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.model.CorrectedCartesianFloat
import com.mbientlab.metawear.model.EulerAngles
import com.mbientlab.metawear.model.LoggedSample
import com.mbientlab.metawear.model.Quaternion
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Ported from MWPersistenceStoreTests.swift. The Swift tests run the store
// against an in-memory SwiftData container; here the store's logic runs
// against FakePersistenceDao on the JVM, and the generated Room DAO is
// exercised on-device by src/androidTest.

/** Port of the "MWPersistenceStore — save and fetch" suite. */
class PersistenceStoreSaveFetchTest {

    @Test
    fun `saveSession returns snapshot`() = runTest {
        val store = makeStore()
        val id = randomDeviceID()
        val samples = cartesianSamples(count = 5)
        val snapshot = store.saveSession(
            deviceID = id,
            deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = samples,
            persistable = CartesianFloatPersistable,
        )
        assertEquals(id, snapshot.deviceID)
        assertEquals(CartesianFloatPersistable.persistenceKind, snapshot.sensorKind)
        assertEquals(5, snapshot.sampleCount)
    }

    @Test
    fun `saveSession with empty samples throws`() = runTest {
        val store = makeStore()
        val empty = emptyList<LoggedSample<CartesianFloat>>()
        val error = runCatching {
            store.saveSession(
                deviceID = randomDeviceID(),
                deviceInfo = makeDeviceInfo(),
                sensorKind = CartesianFloatPersistable.persistenceKind,
                samples = empty,
                persistable = CartesianFloatPersistable,
            )
        }.exceptionOrNull()
        assertTrue(error is PersistenceException.EmptySampleSet)
    }

    @Test
    fun `fetchSessions returns only matching device`() = runTest {
        val store = makeStore()
        val deviceA = randomDeviceID()
        val deviceB = randomDeviceID()

        store.saveSession(
            deviceID = deviceA, deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = cartesianSamples(count = 3), persistable = CartesianFloatPersistable,
        )
        store.saveSession(
            deviceID = deviceB, deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = cartesianSamples(count = 3), persistable = CartesianFloatPersistable,
        )

        val resultA = store.fetchSessions(deviceID = deviceA)
        val resultB = store.fetchSessions(deviceID = deviceB)
        assertEquals(1, resultA.size)
        assertEquals(1, resultB.size)
        assertEquals(deviceA, resultA[0].deviceID)
        assertEquals(deviceB, resultB[0].deviceID)
    }

    @Test
    fun `fetchSessions sorted newest first`() = runTest {
        val store = makeStore()
        val deviceID = randomDeviceID()
        val info = makeDeviceInfo()

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

        store.saveSession(
            deviceID = deviceID, deviceInfo = info,
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = older, persistable = CartesianFloatPersistable,
        )
        store.saveSession(
            deviceID = deviceID, deviceInfo = info,
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = newer, persistable = CartesianFloatPersistable,
        )

        val sessions = store.fetchSessions(deviceID = deviceID)
        assertEquals(2, sessions.size)
        assertTrue(sessions[0].startDate > sessions[1].startDate)
    }

    @Test
    fun `fetchAllSessions on empty store returns empty`() = runTest {
        val store = makeStore()
        val all = store.fetchAllSessions()
        assertTrue(all.isEmpty())
    }

    @Test
    fun `fetchAllSessions returns across devices`() = runTest {
        val store = makeStore()
        val info = makeDeviceInfo()
        store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = info,
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = cartesianSamples(count = 1), persistable = CartesianFloatPersistable,
        )
        store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = info,
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = cartesianSamples(count = 1), persistable = CartesianFloatPersistable,
        )
        val all = store.fetchAllSessions()
        assertEquals(2, all.size)
    }

    @Test
    fun `saveSession stores device info`() = runTest {
        val store = makeStore()
        val info = makeDeviceInfo(serial = "11:22:33:44:55:66")
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = info,
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = cartesianSamples(count = 1), persistable = CartesianFloatPersistable,
        )
        assertEquals("11:22:33:44:55:66", snap.deviceSerial)
        assertEquals("4", snap.deviceModel)
        assertEquals("1.7.0", snap.deviceFirmware)
    }
}

/** Port of the "MWPersistenceStore — sample round-trips" suite. */
class PersistenceStoreRoundTripTest {

    @Test
    fun `cartesian roundTrip`() = runTest {
        val store = makeStore()
        val original = cartesianSamples(count = 3)
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = original, persistable = CartesianFloatPersistable,
        )

        val restored = store.fetchSamples(sessionID = snap.id, persistable = CartesianFloatPersistable)
        assertEquals(3, restored.size)
        for ((o, r) in original.zip(restored)) {
            assertEquals(o.value.x, r.value.x, 1e-5f)
            assertEquals(o.value.y, r.value.y, 1e-5f)
            assertEquals(o.value.z, r.value.z, 1e-5f)
            assertEquals(o.tickMs, r.tickMs, 1e-9)
        }
    }

    @Test
    fun `quaternion roundTrip`() = runTest {
        val store = makeStore()
        val original = listOf(
            LoggedSample(
                date = someInstant, tickMs = 123.456,
                value = Quaternion(w = 0.707f, x = 0.0f, y = 0.707f, z = 0.0f),
            ),
        )
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = QuaternionPersistable.persistenceKind,
            samples = original, persistable = QuaternionPersistable,
        )

        val restored = store.fetchSamples(sessionID = snap.id, persistable = QuaternionPersistable)
        assertEquals(1, restored.size)
        assertEquals(0.707f, restored[0].value.w, 1e-5f)
        assertEquals(0.707f, restored[0].value.y, 1e-5f)
        assertEquals(123.456, restored[0].tickMs, 1e-9)
    }

    @Test
    fun `eulerAngles roundTrip`() = runTest {
        val store = makeStore()
        val original = listOf(
            LoggedSample(
                date = someInstant, tickMs = 0.0,
                value = EulerAngles(heading = 45f, pitch = -10f, roll = 5f, yaw = 90f),
            ),
        )
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = EulerAnglesPersistable.persistenceKind,
            samples = original, persistable = EulerAnglesPersistable,
        )

        val restored = store.fetchSamples(sessionID = snap.id, persistable = EulerAnglesPersistable)
        assertEquals(45f, restored[0].value.heading, 1e-5f)
        assertEquals(-10f, restored[0].value.pitch, 1e-5f)
        assertEquals(5f, restored[0].value.roll, 1e-5f)
        assertEquals(90f, restored[0].value.yaw, 1e-5f)
    }

    @Test
    fun `correctedCartesian roundTrip preserves accuracy`() = runTest {
        val store = makeStore()
        val original = listOf(
            LoggedSample(
                date = someInstant, tickMs = 0.0,
                value = CorrectedCartesianFloat(x = 1f, y = 2f, z = 3f, accuracy = 3),
            ),
        )
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = CorrectedCartesianFloatPersistable.persistenceKind,
            samples = original, persistable = CorrectedCartesianFloatPersistable,
        )

        val restored =
            store.fetchSamples(sessionID = snap.id, persistable = CorrectedCartesianFloatPersistable)
        assertEquals(3, restored[0].value.accuracy)
    }

    @Test
    fun `float roundTrip`() = runTest {
        val store = makeStore()
        val original = listOf(LoggedSample(date = someInstant, tickMs = 0.0, value = 23.5f))
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = FloatPersistable.persistenceKind,
            samples = original, persistable = FloatPersistable,
        )

        val restored = store.fetchSamples(sessionID = snap.id, persistable = FloatPersistable)
        assertEquals(23.5f, restored[0].value, 1e-5f)
    }

    @Test
    fun `bool roundTrip`() = runTest {
        val store = makeStore()
        val original = listOf(
            LoggedSample(date = Instant.fromEpochSeconds(0), tickMs = 0.0, value = true),
            LoggedSample(date = Instant.fromEpochSeconds(1), tickMs = 10.0, value = false),
        )
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = BoolPersistable.persistenceKind,
            samples = original, persistable = BoolPersistable,
        )

        val restored = store.fetchSamples(sessionID = snap.id, persistable = BoolPersistable)
        assertEquals(2, restored.size)
        assertTrue(restored[0].value)
        assertFalse(restored[1].value)
    }

    @Test
    fun `fetchSamples sorted by tickMs`() = runTest {
        val store = makeStore()
        // Insert samples in reverse tick order — fetch must return them sorted ascending
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
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = scrambled, persistable = CartesianFloatPersistable,
        )

        val restored = store.fetchSamples(sessionID = snap.id, persistable = CartesianFloatPersistable)
        assertTrue(restored[0].tickMs < restored[1].tickMs)
        assertTrue(restored[1].tickMs < restored[2].tickMs)
    }

    @Test
    fun `fetchSamples kind mismatch throws`() = runTest {
        val store = makeStore()
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = cartesianSamples(count = 1), persistable = CartesianFloatPersistable,
        )

        val error = runCatching {
            store.fetchSamples(sessionID = snap.id, persistable = QuaternionPersistable)
        }.exceptionOrNull()
        assertTrue(error is PersistenceException.KindMismatch)
        assertEquals("cartesian", (error as PersistenceException.KindMismatch).stored)
        assertEquals("quaternion", error.requested)
    }

    @Test
    fun `fetchSamples unknown id throws`() = runTest {
        val store = makeStore()
        // Save and delete so we have a well-formed ID but the record is gone
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = cartesianSamples(count = 1), persistable = CartesianFloatPersistable,
        )
        store.deleteSession(id = snap.id)

        val error = runCatching {
            store.fetchSamples(sessionID = snap.id, persistable = CartesianFloatPersistable)
        }.exceptionOrNull()
        assertTrue(error is PersistenceException.SessionNotFound)
    }
}

/** Port of the "MWPersistenceStore — delete" suite. */
class PersistenceStoreDeleteTest {

    @Test
    fun `deleteSession removes it`() = runTest {
        val store = makeStore()
        val snap = store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = cartesianSamples(count = 2), persistable = CartesianFloatPersistable,
        )
        store.deleteSession(id = snap.id)
        val all = store.fetchAllSessions()
        assertTrue(all.isEmpty())
    }

    @Test
    fun `deleteAllSessions only removes matching device`() = runTest {
        val store = makeStore()
        val deviceA = randomDeviceID()
        val deviceB = randomDeviceID()
        val info = makeDeviceInfo()

        store.saveSession(
            deviceID = deviceA, deviceInfo = info,
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = cartesianSamples(count = 1), persistable = CartesianFloatPersistable,
        )
        store.saveSession(
            deviceID = deviceB, deviceInfo = info,
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = cartesianSamples(count = 1), persistable = CartesianFloatPersistable,
        )

        store.deleteAllSessions(deviceID = deviceA)

        val remainingA = store.fetchSessions(deviceID = deviceA)
        val remainingB = store.fetchSessions(deviceID = deviceB)
        assertTrue(remainingA.isEmpty())
        assertEquals(1, remainingB.size)
    }

    @Test
    fun `deleteAll clears everything`() = runTest {
        val store = makeStore()
        val info = makeDeviceInfo()
        store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = info,
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = cartesianSamples(count = 1), persistable = CartesianFloatPersistable,
        )
        store.saveSession(
            deviceID = randomDeviceID(), deviceInfo = info,
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = cartesianSamples(count = 1), persistable = CartesianFloatPersistable,
        )
        store.deleteAll()
        val all = store.fetchAllSessions()
        assertTrue(all.isEmpty())
    }
}
