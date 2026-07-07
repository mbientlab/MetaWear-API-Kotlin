package com.mbientlab.metawear.persistence

/**
 * In-memory fake [PersistenceDao] that mirrors the SQL contracts of the
 * generated Room implementation (sort orders, sample counts, cascade deletes).
 * It lets the store tests run on a plain JVM. The generated Room
 * implementation itself is exercised against a real database in
 * `src/androidTest`.
 */
class FakePersistenceDao : PersistenceDao {

    private val sessions = mutableListOf<SessionRecord>()
    private val samples = mutableListOf<SampleRecord>()
    private var nextPk = 1L

    override suspend fun insertSession(session: SessionRecord) {
        sessions += session
    }

    override suspend fun insertSamples(samples: List<SampleRecord>) {
        for (sample in samples) this.samples += sample.copy(pk = nextPk++)
    }

    override suspend fun sessionsForDevice(deviceID: String): List<SessionWithCount> =
        sessions.filter { it.deviceID == deviceID }
            .sortedByDescending { it.startDate }
            .map { SessionWithCount(it, countFor(it.id)) }

    override suspend fun allSessions(): List<SessionWithCount> =
        sessions.sortedByDescending { it.startDate }
            .map { SessionWithCount(it, countFor(it.id)) }

    override suspend fun sessionById(id: String): SessionRecord? =
        sessions.firstOrNull { it.id == id }

    override suspend fun samplesForSession(sessionId: String): List<SampleRecord> =
        samples.filter { it.sessionId == sessionId }.sortedBy { it.tickMs }

    override suspend fun deleteSession(id: String) {
        sessions.removeAll { it.id == id }
        samples.removeAll { it.sessionId == id } // FK cascade
    }

    override suspend fun deleteSessionsForDevice(deviceID: String) {
        val ids = sessions.filter { it.deviceID == deviceID }.mapTo(mutableSetOf()) { it.id }
        sessions.removeAll { it.deviceID == deviceID }
        samples.removeAll { it.sessionId in ids } // FK cascade
    }

    override suspend fun deleteAllSessions() {
        sessions.clear()
        samples.clear() // FK cascade
    }

    private fun countFor(sessionId: String): Int = samples.count { it.sessionId == sessionId }
}
