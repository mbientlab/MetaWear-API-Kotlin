package com.mbientlab.metawear.persistence

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * A session row joined with its sample count, so listing sessions does not
 * require loading their samples.
 */
data class SessionWithCount(
    @Embedded val session: SessionRecord,
    val sampleCount: Int,
)

/**
 * Room data-access interface for persisted sessions and samples.
 *
 * [PersistenceStore] holds business logic (validation, kind checks, snapshot
 * and sample mapping); this interface holds the SQL. Keeping it an interface
 * also lets the store's logic be unit-tested on a plain JVM against an
 * in-memory fake, with the generated implementation exercised by the
 * instrumented tests.
 */
@Dao
interface PersistenceDao {

    // ---- Insert ----

    @Insert
    suspend fun insertSession(session: SessionRecord)

    @Insert
    suspend fun insertSamples(samples: List<SampleRecord>)

    /** Atomically insert a session and all of its samples. */
    @Transaction
    suspend fun insertSessionWithSamples(session: SessionRecord, samples: List<SampleRecord>) {
        insertSession(session)
        insertSamples(samples)
    }

    // ---- Sessions ----

    /** All sessions for one device, newest-first. */
    @Query(
        """SELECT sessions.*,
                  (SELECT COUNT(*) FROM samples WHERE samples.sessionId = sessions.id) AS sampleCount
           FROM sessions
           WHERE deviceID = :deviceID
           ORDER BY startDate DESC""",
    )
    suspend fun sessionsForDevice(deviceID: String): List<SessionWithCount>

    /** All sessions across all devices, newest-first. */
    @Query(
        """SELECT sessions.*,
                  (SELECT COUNT(*) FROM samples WHERE samples.sessionId = sessions.id) AS sampleCount
           FROM sessions
           ORDER BY startDate DESC""",
    )
    suspend fun allSessions(): List<SessionWithCount>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun sessionById(id: String): SessionRecord?

    // ---- Samples ----

    /** Samples of one session in ascending tick order. */
    @Query("SELECT * FROM samples WHERE sessionId = :sessionId ORDER BY tickMs ASC")
    suspend fun samplesForSession(sessionId: String): List<SampleRecord>

    // ---- Delete (samples cascade via the SampleRecord foreign key) ----

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("DELETE FROM sessions WHERE deviceID = :deviceID")
    suspend fun deleteSessionsForDevice(deviceID: String)

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()
}
