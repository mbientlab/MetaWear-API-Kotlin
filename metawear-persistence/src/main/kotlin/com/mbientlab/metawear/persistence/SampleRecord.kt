package com.mbientlab.metawear.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * One persisted sensor sample.
 *
 * All sensor value types are stored as up to four `Float` fields plus an
 * `accuracy` byte. This flat layout means a single entity handles
 * CartesianFloat, Quaternion, EulerAngles, CorrectedCartesianFloat, Float and
 * Boolean without branching in the persistence layer — see [PersistedValues]
 * for the field mapping by sensor kind.
 */
@Entity(
    tableName = "samples",
    foreignKeys = [
        ForeignKey(
            entity = SessionRecord::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            // Cascade delete: removing a session removes its samples.
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class SampleRecord(
    @PrimaryKey(autoGenerate = true) val pk: Long = 0,
    /** Owning [SessionRecord.id]. */
    val sessionId: String,
    /** Wall-clock timestamp. */
    val date: Instant,
    /** Elapsed milliseconds since the MetaWear last reset. */
    val tickMs: Double,
    /** Primary value component. */
    val f0: Float,
    val f1: Float = 0f,
    val f2: Float = 0f,
    val f3: Float = 0f,
    /** CorrectedCartesianFloat accuracy (0 for all other types). */
    val accuracy: Int = 0,
)
