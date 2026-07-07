package com.mbientlab.metawear.persistence

/**
 * Errors thrown by [PersistenceStore]. Port of `MWPersistenceError` (Swift),
 * expressed in the same sealed-exception style as
 * `com.mbientlab.metawear.model.MetaWearException`.
 */
sealed class PersistenceException(message: String) : Exception(message) {

    /** Attempted to save a session with zero samples. */
    object EmptySampleSet :
        PersistenceException("Attempted to save a session with zero samples")

    /** The stored `sensorKind` does not match the requested type's `persistenceKind`. */
    class KindMismatch(val stored: String, val requested: String) : PersistenceException(
        "Sensor kind mismatch: stored \"$stored\" does not match requested \"$requested\"",
    )

    /** A session with the given ID was not found in the store. */
    object SessionNotFound :
        PersistenceException("Session not found in the store")
}
