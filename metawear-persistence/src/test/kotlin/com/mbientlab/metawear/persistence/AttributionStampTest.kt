package com.mbientlab.metawear.persistence

import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// Attribution stamps (group-logging prebake).
//
// deviceName / groupID are stamped at SAVE time because they cannot be
// reconstructed later: boards go off air and advertised-name caches are
// per-host (no retroactive name), and a group batch only exists at capture.
// Data written without these fields can never be backfilled — which is why
// the stamps land ahead of the group-logging feature itself.

class AttributionStampTest {

    @Test
    fun `saveSession stamps name count and group`() = runTest {
        val store = makeStore()
        val group = UUID.randomUUID().toString()
        val snap = store.saveSession(
            deviceID = randomDeviceID(),
            deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = cartesianSamples(7),
            persistable = CartesianFloatPersistable,
            label = "Accelerometer · ±2g · 100 Hz",
            deviceName = "bob",
            groupID = group,
        )
        assertEquals("bob", snap.deviceName)
        assertEquals(7, snap.sampleCount)
        assertEquals(group, snap.groupID)

        // Round-trips through a fresh fetch, not just the save-path snapshot.
        val fetched = store.fetchAllSessions().first()
        assertEquals("bob", fetched.deviceName)
        assertEquals(7, fetched.sampleCount)
        assertEquals(group, fetched.groupID)
    }

    /**
     * A missed advertisement can hand the app "" as the display name — the
     * save choke point must normalise it to null so readers' fallbacks
     * (serial keys, section titles) fire instead of rendering blanks.
     */
    @Test
    fun `empty deviceName normalises to null`() = runTest {
        val store = makeStore()
        val snap = store.saveSession(
            deviceID = randomDeviceID(),
            deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = cartesianSamples(2),
            persistable = CartesianFloatPersistable,
            deviceName = "",
        )
        assertNull(snap.deviceName)
    }

    @Test
    fun `stamps default to null for solo sessions`() = runTest {
        val store = makeStore()
        val snap = store.saveSession(
            deviceID = randomDeviceID(),
            deviceInfo = makeDeviceInfo(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            samples = cartesianSamples(3),
            persistable = CartesianFloatPersistable,
        )
        assertNull(snap.deviceName)
        assertNull(snap.groupID)
        assertEquals(3, snap.sampleCount)
    }

    /**
     * Records that predate the attribution columns carry no stamps — the
     * snapshot must still report the true sample count (derived from the
     * samples table, never a stamped value) and null attribution.
     */
    @Test
    fun `pre-migration records read as unattributed with a counted sample set`() = runTest {
        val dao = FakePersistenceDao()
        val store = PersistenceStore(dao)
        val record = SessionRecord(
            id = UUID.randomUUID().toString(),
            deviceID = randomDeviceID(),
            sensorKind = CartesianFloatPersistable.persistenceKind,
            startDate = someInstant,
            endDate = someInstant,
            deviceSerial = "0123FF",
            deviceModel = "8",
            deviceFirmware = "1.7.2",
        )
        dao.insertSession(record)
        dao.insertSamples(
            (0 until 4).map { i ->
                SampleRecord(
                    sessionId = record.id,
                    date = Instant.fromEpochSeconds(i.toLong()),
                    tickMs = i.toDouble(),
                    f0 = 0f,
                )
            },
        )

        val snap = store.fetchAllSessions().first()
        assertEquals(4, snap.sampleCount)
        assertNull(snap.deviceName)
        assertNull(snap.groupID)
    }
}
