package com.mbientlab.metawear.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The Room database for the full MetaWear persistence schema.
 *
 * Create one database per app process and share it between [PersistenceStore]
 * instances:
 *
 * ```kotlin
 * // App startup — create the database once
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
@Database(
    entities = [SessionRecord::class, SampleRecord::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(InstantConverters::class)
abstract class PersistenceDatabase : RoomDatabase() {

    abstract fun persistenceDao(): PersistenceDao

    companion object {
        /** Default on-disk database file name. */
        const val DEFAULT_NAME: String = "metawear-sessions.db"

        /** Create the app-wide on-disk database. */
        fun create(context: Context, name: String = DEFAULT_NAME): PersistenceDatabase =
            Room.databaseBuilder(context.applicationContext, PersistenceDatabase::class.java, name)
                .build()

        /**
         * Create an in-memory database for tests and previews. Contents are
         * discarded when the process exits.
         */
        fun createInMemory(context: Context): PersistenceDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, PersistenceDatabase::class.java)
                .build()
    }
}
