package com.mbientlab.metawear.persistence

import androidx.room.TypeConverter
import kotlinx.datetime.Instant

/**
 * Persists `kotlinx.datetime.Instant` as epoch milliseconds.
 *
 * Millisecond resolution matches the SDK's timestamp fidelity: sample dates are
 * derived from the device tick clock, which itself counts milliseconds.
 */
class InstantConverters {

    @TypeConverter
    fun fromInstant(value: Instant): Long = value.toEpochMilliseconds()

    @TypeConverter
    fun toInstant(value: Long): Instant = Instant.fromEpochMilliseconds(value)
}
