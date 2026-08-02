package com.mbientlab.metawear.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * A single download session for one sensor on one device.
 *
 * One row is created each time `downloadLogs` completes. Its samples are
 * cascade-deleted when the session row is removed (see [SampleRecord]'s
 * foreign key).
 *
 * Schema notes:
 * - Devices are keyed by their Android MAC address string, so [deviceID] is an
 *   opaque `String` (same convention as `ScanResult.identifier` in the
 *   transport seam).
 * - [id] is a store-assigned random UUID string; all lookups go through it.
 * - Instants persist as epoch milliseconds (see `InstantConverters`).
 */
@Entity(tableName = "sessions")
data class SessionRecord(
    /** Stable, store-assigned session identifier. Use this for all lookups. */
    @PrimaryKey val id: String,
    /** Identifier of the source device (MAC address on Android). */
    val deviceID: String,
    /** Discriminator matching [Persistable.persistenceKind] (e.g. "cartesian"). */
    val sensorKind: String,
    /** Wall-clock timestamp of the first sample. */
    val startDate: Instant,
    /** Wall-clock timestamp of the last sample. */
    val endDate: Instant,
    /** Denormalised device info — stored once per session for offline display. */
    val deviceSerial: String,
    val deviceModel: String,
    val deviceFirmware: String,
    /**
     * User-facing description of the sensor + settings the session captured
     * (e.g. "Gyroscope · ±2000 dps · 25 Hz"). Optional because [sensorKind]
     * alone is enough to load the samples; this is a display hint for the
     * history list.
     */
    val label: String? = null,
    /**
     * Display name of the board at capture time ("bob", "MetaWear"). Must be
     * stamped at save: boards go off air and advertised-name caches are
     * per-host, so there is no reliable retroactive path from [deviceID] to a
     * name. Null on records written before this column existed — readers fall
     * back to [deviceSerial]/[deviceModel].
     */
    val deviceName: String? = null,
    /**
     * Group-capture batch this session belongs to, when several boards were
     * logged together (UUID string shared by every session in the batch).
     * Null for solo sessions and all records that predate group logging.
     */
    val groupID: String? = null,
)
