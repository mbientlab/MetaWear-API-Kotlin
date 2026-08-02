package com.mbientlab.metawear.app.data

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.RawLogEntry
import com.mbientlab.metawear.clearLog
import com.mbientlab.metawear.createAnonymousDataSignals
import com.mbientlab.metawear.downloadLogs
import com.mbientlab.metawear.model.AnonymousSignal
import com.mbientlab.metawear.model.DeviceInformation
import com.mbientlab.metawear.model.LoggedSample
import com.mbientlab.metawear.persistence.CartesianFloatPersistable
import com.mbientlab.metawear.persistence.CorrectedCartesianFloatPersistable
import com.mbientlab.metawear.persistence.EulerAnglesPersistable
import com.mbientlab.metawear.persistence.FloatPersistable
import com.mbientlab.metawear.persistence.Persistable
import com.mbientlab.metawear.persistence.PersistenceStore
import com.mbientlab.metawear.persistence.QuaternionPersistable
import com.mbientlab.metawear.persistence.SessionSnapshot
import com.mbientlab.metawear.stopOnBoardLogging

/**
 * The download engine shared by the solo download screen and the group
 * coordinator: one raw drain of the board's circular log (per-loggable drains
 * would find the log empty on the second read), typed decode + persist per
 * pending record, then a single clear. The foreign path recovers whatever the
 * board's own logger configuration can decode.
 */
object LogDownloader {

    /** Outcome of one board's download pass. */
    data class Result(
        val snapshots: List<SessionSnapshot>,
        val warning: String?,
        /** Set when the pass failed outright; [message] carries the reason. */
        val failed: Boolean = false,
        val message: String? = null,
    )

    /** Progress callback: (fraction 0..1, entries so far, total or null). */
    fun interface Progress {
        fun report(fraction: Double, entries: Long, total: Long?)
    }

    /**
     * Drain + decode + persist this app's own pending session records.
     * Registry statuses are updated per record; the board's flash is cleared
     * once everything decoded.
     */
    suspend fun downloadAll(
        device: MetaWearDevice,
        persistence: PersistenceStore,
        registry: LogSessionRegistry,
        records: List<LogSessionRecord>,
        deviceName: String?,
        onProgress: Progress = Progress { _, _, _ -> },
    ): Result {
        val info = device.deviceInfo
            ?: return Result(emptyList(), null, failed = true, message = "Device info unavailable")

        val entries = try {
            drain(device, expectEntries = true, onProgress)
        } catch (e: Exception) {
            return Result(emptyList(), null, failed = true, message = e.message ?: "Download failed")
        }

        val snapshots = mutableListOf<SessionSnapshot>()
        var warning: String? = null
        for (record in records) {
            val sensor = ConfiguredSensor.make(record.selection, device.modules)
            runCatching {
                sensor.decodeAndSave(
                    device, persistence, entries, info,
                    deviceName = deviceName,
                    groupID = record.groupID,
                )
            }
                .onSuccess { snapshot ->
                    if (snapshot != null) {
                        snapshots.add(snapshot)
                        registry.updateStatus(record.id, LogSessionRecord.Status.DOWNLOADED)
                    } else {
                        // Close a decoded-empty record as failed so it stops
                        // haunting the pending list.
                        warning = "No samples decoded for ${record.selection.key.title}"
                        registry.updateStatus(record.id, LogSessionRecord.Status.FAILED)
                    }
                }
                .onFailure {
                    warning = it.message
                    registry.updateStatus(record.id, LogSessionRecord.Status.FAILED)
                }
        }

        runCatching { device.clearLog() }
        return Result(snapshots, warning)
    }

    /**
     * Recover a foreign log — data this app has no pending records for —
     * by rebuilding decoders from the board's own logger configuration.
     * The board's log is only cleared once something (or provably nothing)
     * was recovered; a failed partial drain keeps the flash intact.
     */
    suspend fun downloadForeign(
        device: MetaWearDevice,
        persistence: PersistenceStore,
        state: OrphanLogState,
        deviceName: String?,
        onProgress: Progress = Progress { _, _, _ -> },
    ): Result {
        // Never drain a board the state wasn't evaluated on.
        if (device.identifier != state.deviceId) {
            return Result(
                emptyList(), null, failed = true,
                message = "This session belongs to a different board — reconnect to ${state.deviceId}.",
            )
        }
        val info = device.deviceInfo
            ?: return Result(emptyList(), null, failed = true, message = "Device info unavailable")

        // Stop sampling so the readout doesn't race writes (no-op if stopped).
        runCatching { device.stopOnBoardLogging() }

        val signals = runCatching { device.createAnonymousDataSignals() }.getOrDefault(emptyList())
        if (signals.isEmpty()) {
            runCatching { device.clearLog() }
            return Result(
                emptyList(),
                warning = "No recoverable sensor data was found on the board; its log was cleared.",
            )
        }

        val entries = try {
            drain(device, expectEntries = state.entryCount > 0 || state.isActivelyLogging, onProgress)
        } catch (e: Exception) {
            return Result(emptyList(), null, failed = true, message = e.message ?: "Download failed")
        }

        val snapshots = mutableListOf<SessionSnapshot>()
        for (signal in signals) {
            val decoded = AnonymousSignalDecoder.decode(entries, signal, device.logReferenceDate)
            if (decoded.isEmpty()) continue
            val outputsPerSample = decoded.first().value.size
            for (outputIndex in 0 until outputsPerSample) {
                runCatching {
                    saveOutputs(persistence, device.identifier, info, deviceName, signal, decoded, outputIndex)
                }.onSuccess { it?.let(snapshots::add) }
            }
        }

        if (snapshots.isEmpty() && state.entryCount > 0) {
            // The readout pointer is spent — an unconditional clear here would
            // erase the session. Keep the flash so a retry can try again.
            return Result(
                emptyList(), null, failed = true,
                message = "No sensor data could be recovered from the download. The board's log " +
                    "was kept — retry the download, or discard it from the Logging screen.",
            )
        }
        runCatching { device.clearLog() }
        return Result(
            snapshots,
            warning = if (snapshots.isEmpty()) {
                "The session ended before any entries reached the board's flash memory."
            } else {
                null
            },
        )
    }

    private suspend fun drain(
        device: MetaWearDevice,
        expectEntries: Boolean,
        onProgress: Progress,
    ): List<RawLogEntry> {
        var entries: List<RawLogEntry> = emptyList()
        device.downloadLogs(expectEntries = expectEntries).collect { progress ->
            entries = progress.data
            onProgress.report(
                progress.percentComplete,
                progress.entriesDownloaded ?: entries.size.toLong(),
                progress.totalEntries,
            )
        }
        return entries
    }

    /** Persist one output lane of a recovered signal as a session. */
    private suspend fun saveOutputs(
        persistence: PersistenceStore,
        deviceId: String,
        info: DeviceInformation,
        deviceName: String?,
        signal: AnonymousSignal,
        decoded: List<LoggedSample<List<AnonymousSignal.Output>>>,
        outputIndex: Int,
    ): SessionSnapshot? {
        val label = foreignLabel(signal.identifier)

        suspend fun <S : Any> save(persistable: Persistable<S>, value: (AnonymousSignal.Output) -> S?): SessionSnapshot? {
            val samples = decoded.mapNotNull { sample ->
                sample.value.getOrNull(outputIndex)?.let(value)?.let {
                    LoggedSample(sample.date, sample.tickMs, it)
                }
            }
            if (samples.isEmpty()) return null
            return persistence.saveSession(
                deviceID = deviceId,
                deviceInfo = info,
                sensorKind = persistable.persistenceKind,
                samples = samples,
                persistable = persistable,
                label = label,
                deviceName = deviceName,
            )
        }

        return when (decoded.first().value.getOrNull(outputIndex)) {
            is AnonymousSignal.Output.Cartesian ->
                save(CartesianFloatPersistable) { (it as? AnonymousSignal.Output.Cartesian)?.value }
            is AnonymousSignal.Output.Scalar ->
                save(FloatPersistable) { (it as? AnonymousSignal.Output.Scalar)?.value }
            is AnonymousSignal.Output.Quaternion ->
                save(QuaternionPersistable) { (it as? AnonymousSignal.Output.Quaternion)?.value }
            is AnonymousSignal.Output.Euler ->
                save(EulerAnglesPersistable) { (it as? AnonymousSignal.Output.Euler)?.value }
            is AnonymousSignal.Output.CorrectedCartesian ->
                save(CorrectedCartesianFloatPersistable) { (it as? AnonymousSignal.Output.CorrectedCartesian)?.value }
            null -> null
        }
    }
}
