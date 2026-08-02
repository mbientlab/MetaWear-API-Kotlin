package com.mbientlab.metawear.persistence

import com.mbientlab.metawear.model.DataTable
import com.mbientlab.metawear.model.DeviceInformation
import com.mbientlab.metawear.model.LoggedSample
import java.util.UUID

/**
 * Store for persisted MetaWear log sessions.
 *
 * All methods are `suspend` and delegate to Room's suspend DAO, which runs
 * queries on Room's own dispatcher and serializes writes internally — so
 * instances are safe to call from any coroutine context.
 *
 * ### Typical usage
 * ```kotlin
 * // App startup — create the database once (one database per app)
 * val database = PersistenceDatabase.create(context)
 * val store = PersistenceStore(database)
 *
 * // After downloading logs:
 * val snapshot = store.saveSession(
 *     deviceID = device.identifier,
 *     deviceInfo = device.deviceInfo!!,
 *     sensorKind = CartesianFloatPersistable.persistenceKind,
 *     samples = downloadedSamples,
 *     persistable = CartesianFloatPersistable)
 *
 * // Later — list sessions for this device:
 * val sessions = store.fetchSessions(deviceID = device.identifier)
 * ```
 */
class PersistenceStore(private val dao: PersistenceDao) {

    constructor(database: PersistenceDatabase) : this(database.persistenceDao())

    // ---- Save ----

    /**
     * Persist a completed download session.
     *
     * @param deviceID The device identifier (MAC address on Android).
     * @param deviceInfo Device information read during `connect()`.
     * @param sensorKind The discriminator stored on the session record —
     *   normally `persistable.persistenceKind`.
     * @param samples The typed logged samples returned by `downloadLogs`.
     * @param persistable The codec that packs [samples] into the flat record layout.
     * @param label Optional user-facing sensor + settings string for history lists.
     * @param deviceName Display name of the board at capture time. Must be
     *   stamped now or never — boards go off air and advertised-name caches
     *   are per-host, so there is no retroactive path from [deviceID] to a
     *   name.
     * @param groupID Group-capture batch identifier, when several boards were
     *   logged together. A batch only exists at capture time.
     * @return A [SessionSnapshot] describing the newly created session.
     * @throws PersistenceException.EmptySampleSet if [samples] is empty.
     */
    suspend fun <S : Any> saveSession(
        deviceID: String,
        deviceInfo: DeviceInformation,
        sensorKind: String,
        samples: List<LoggedSample<S>>,
        persistable: Persistable<S>,
        label: String? = null,
        deviceName: String? = null,
        groupID: String? = null,
    ): SessionSnapshot {
        if (samples.isEmpty()) throw PersistenceException.EmptySampleSet

        val session = SessionRecord(
            id = UUID.randomUUID().toString(),
            deviceID = deviceID,
            sensorKind = sensorKind,
            startDate = samples.first().date,
            endDate = samples.last().date,
            deviceSerial = deviceInfo.serialNumber,
            deviceModel = deviceInfo.modelNumber,
            deviceFirmware = deviceInfo.firmwareRevision,
            label = label,
            // A missed advertisement can surface as "" — normalise here, at
            // the single choke point, so readers' null-name fallbacks (serial
            // keys, section titles) fire instead of rendering blank headers.
            deviceName = deviceName?.takeIf { it.isNotEmpty() },
            groupID = groupID,
        )
        val records = samples.map { logged ->
            val v = persistable.persistenceValues(logged.value)
            SampleRecord(
                sessionId = session.id,
                date = logged.date,
                tickMs = logged.tickMs,
                f0 = v.f0,
                f1 = v.f1,
                f2 = v.f2,
                f3 = v.f3,
                accuracy = v.accuracy,
            )
        }
        dao.insertSessionWithSamples(session, records)
        return SessionSnapshot.from(session, sampleCount = samples.size)
    }

    // ---- Fetch sessions ----

    /** All sessions for one device, sorted newest-first. */
    suspend fun fetchSessions(deviceID: String): List<SessionSnapshot> =
        dao.sessionsForDevice(deviceID).map { SessionSnapshot.from(it.session, it.sampleCount) }

    /** All sessions across all devices, sorted newest-first. */
    suspend fun fetchAllSessions(): List<SessionSnapshot> =
        dao.allSessions().map { SessionSnapshot.from(it.session, it.sampleCount) }

    // ---- Fetch samples ----

    /**
     * Reconstruct typed samples from a previously saved session, in ascending
     * tick order.
     *
     * @param sessionID The [SessionSnapshot.id] returned by [fetchSessions] or [saveSession].
     * @param persistable The expected sample codec. Its `persistenceKind` must
     *   match the session's stored `sensorKind`.
     * @throws PersistenceException.SessionNotFound
     * @throws PersistenceException.KindMismatch
     */
    suspend fun <S : Any> fetchSamples(
        sessionID: String,
        persistable: Persistable<S>,
    ): List<LoggedSample<S>> {
        val session = requireSession(sessionID)
        if (session.sensorKind != persistable.persistenceKind) {
            throw PersistenceException.KindMismatch(
                stored = session.sensorKind,
                requested = persistable.persistenceKind,
            )
        }
        return dao.samplesForSession(sessionID).map { r ->
            LoggedSample(
                date = r.date,
                tickMs = r.tickMs,
                value = persistable.fromPersistence(r.f0, r.f1, r.f2, r.f3, r.accuracy),
            )
        }
    }

    // ---- Export ----

    /**
     * Build a [DataTable] (suitable for CSV export) from a persisted session.
     *
     * @param sessionID The [SessionSnapshot.id] of the session to export.
     * @param persistable The sample codec — must match the session's `sensorKind`.
     */
    suspend fun <S : Any> exportTable(
        sessionID: String,
        persistable: Persistable<S>,
    ): DataTable {
        val samples = fetchSamples(sessionID, persistable)
        return DataTable.fromLogged(samples, persistable.persistenceKind, persistable.sampleType)
    }

    // ---- Delete ----

    /** Delete one session (and all its samples via cascade). No-op if absent. */
    suspend fun deleteSession(id: String) {
        dao.deleteSession(id)
    }

    /** Delete all sessions for a given device. */
    suspend fun deleteAllSessions(deviceID: String) {
        dao.deleteSessionsForDevice(deviceID)
    }

    /** Delete every session in the store. */
    suspend fun deleteAll() {
        dao.deleteAllSessions()
    }

    // ---- Private helpers ----

    private suspend fun requireSession(id: String): SessionRecord =
        dao.sessionById(id) ?: throw PersistenceException.SessionNotFound
}
