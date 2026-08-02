package com.mbientlab.metawear.app

import com.mbientlab.metawear.persistence.PersistenceDao
import com.mbientlab.metawear.persistence.SampleRecord
import com.mbientlab.metawear.persistence.SessionRecord
import com.mbientlab.metawear.persistence.SessionWithCount

/**
 * Plain in-memory stand-in for the Room DAO so JVM tests can drive the real
 * `PersistenceStore` (and everything built on it) without an Android runtime.
 * Ordering mirrors the SQL: sessions newest-first, samples ascending by tick.
 */
class InMemoryPersistenceDao : PersistenceDao {

    private val lock = Any()
    private val sessions = mutableListOf<SessionRecord>()
    private val samples = mutableListOf<SampleRecord>()

    override suspend fun insertSession(session: SessionRecord) {
        synchronized(lock) {
            sessions.removeAll { it.id == session.id }
            sessions.add(session)
        }
    }

    override suspend fun insertSamples(samples: List<SampleRecord>) {
        synchronized(lock) { this.samples.addAll(samples) }
    }

    override suspend fun sessionsForDevice(deviceID: String): List<SessionWithCount> =
        synchronized(lock) {
            sessions.filter { it.deviceID == deviceID }
                .sortedByDescending { it.startDate }
                .map { SessionWithCount(it, samples.count { s -> s.sessionId == it.id }) }
        }

    override suspend fun allSessions(): List<SessionWithCount> =
        synchronized(lock) {
            sessions.sortedByDescending { it.startDate }
                .map { SessionWithCount(it, samples.count { s -> s.sessionId == it.id }) }
        }

    override suspend fun sessionById(id: String): SessionRecord? =
        synchronized(lock) { sessions.firstOrNull { it.id == id } }

    override suspend fun samplesForSession(sessionId: String): List<SampleRecord> =
        synchronized(lock) { samples.filter { it.sessionId == sessionId }.sortedBy { it.tickMs } }

    override suspend fun deleteSession(id: String) {
        synchronized(lock) {
            sessions.removeAll { it.id == id }
            samples.removeAll { it.sessionId == id }
        }
    }

    override suspend fun deleteSessionsForDevice(deviceID: String) {
        synchronized(lock) {
            val ids = sessions.filter { it.deviceID == deviceID }.map { it.id }.toSet()
            sessions.removeAll { it.deviceID == deviceID }
            samples.removeAll { it.sessionId in ids }
        }
    }

    override suspend fun deleteAllSessions() {
        synchronized(lock) {
            sessions.clear()
            samples.clear()
        }
    }
}
