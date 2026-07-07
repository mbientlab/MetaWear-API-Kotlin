package com.mbientlab.metawear.persistence

import kotlinx.datetime.Instant

/**
 * An immutable value-type snapshot of a [SessionRecord].
 *
 * Returned from [PersistenceStore] fetch methods so callers can work with
 * session metadata without holding a reference to the database entity.
 */
data class SessionSnapshot(
    /**
     * Stable UUID string assigned when the session was created. Pass this back
     * to [PersistenceStore] methods that operate on a specific session.
     */
    val id: String,
    /** Identifier of the board that produced the samples (MAC address on Android). */
    val deviceID: String,
    /** Discriminator matching [Persistable.persistenceKind]. */
    val sensorKind: String,
    /** Wall-clock timestamp of the first sample in the session. */
    val startDate: Instant,
    /** Wall-clock timestamp of the last sample in the session. */
    val endDate: Instant,
    /** Number of persisted samples in the session. */
    val sampleCount: Int,
    /** Device serial copied from the Device Information Service at capture time. */
    val deviceSerial: String,
    /** Device model number copied from the Device Information Service. */
    val deviceModel: String,
    /** Firmware revision copied from the Device Information Service. */
    val deviceFirmware: String,
    /**
     * User-facing sensor + settings string, e.g. "Gyroscope · ±2000 dps · 25 Hz".
     * Null for records persisted without one — callers should fall back to
     * [sensorKind] when null.
     */
    val label: String? = null,
) {
    internal companion object {
        fun from(record: SessionRecord, sampleCount: Int): SessionSnapshot = SessionSnapshot(
            id = record.id,
            deviceID = record.deviceID,
            sensorKind = record.sensorKind,
            startDate = record.startDate,
            endDate = record.endDate,
            sampleCount = sampleCount,
            deviceSerial = record.deviceSerial,
            deviceModel = record.deviceModel,
            deviceFirmware = record.deviceFirmware,
            label = record.label,
        )
    }
}
