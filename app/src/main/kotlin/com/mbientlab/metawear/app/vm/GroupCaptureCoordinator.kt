package com.mbientlab.metawear.app.vm

import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.data.ConfiguredSensor
import com.mbientlab.metawear.app.data.LogDownloader
import com.mbientlab.metawear.app.data.LogSessionRecord
import com.mbientlab.metawear.app.data.LogSessionRegistry
import com.mbientlab.metawear.app.data.OrphanLogState
import com.mbientlab.metawear.app.data.RecordingHeartbeat
import com.mbientlab.metawear.app.data.startLoggingOn
import com.mbientlab.metawear.app.data.stopLoggingOn
import com.mbientlab.metawear.clearLog
import com.mbientlab.metawear.flushLogPage
import com.mbientlab.metawear.persistence.PersistenceStore
import com.mbientlab.metawear.sensor.LogLength
import com.mbientlab.metawear.sensor.LoggingEnabled
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock

/**
 * Fleet-wide logging: starts on-board logging across several boards under one
 * shared group id, then stops + downloads them all. Walks the fleet
 * SEQUENTIALLY (the radio is shared): connect → act → disconnect, one board
 * at a time. Owned by the app container so a walk survives navigation.
 */
class GroupCaptureCoordinator(
    private val persistence: PersistenceStore,
    private val registry: LogSessionRegistry,
    /** Connect bound: one absent board must cost one timeout, not wedge the walk. */
    private val connectTimeoutMs: Long = 15_000,
    /** Entries-landing verification: poll cadence and bound (~90 s default). */
    private val verifyPeriodMs: Long = 2_000,
    private val verifyAttempts: Int = 45,
) {

    /** One board in a fleet walk. */
    data class Member(val device: MetaWearDevice, val name: String)

    sealed class BoardPhase {
        data object Pending : BoardPhase()
        data object Connecting : BoardPhase()
        data object Starting : BoardPhase()
        data object Verifying : BoardPhase()
        data object Logging : BoardPhase()
        data object Stopping : BoardPhase()
        data object Downloading : BoardPhase()
        data class Saved(val count: Int) : BoardPhase()
        data class SavedWithIssues(val count: Int, val message: String) : BoardPhase()
        data class Skipped(val message: String) : BoardPhase()
        data class Failed(val message: String) : BoardPhase()
    }

    data class BoardProgress(val id: String, val name: String, val phase: BoardPhase = BoardPhase.Pending)

    enum class PassKind { START, COLLECT }

    private val _boards = MutableStateFlow<List<BoardProgress>>(emptyList())
    val boards: StateFlow<List<BoardProgress>> = _boards.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _lastPass = MutableStateFlow<PassKind?>(null)
    val lastPass: StateFlow<PassKind?> = _lastPass.asStateFlow()

    private class BoardBusyException : Exception("board busy")
    private class ConnectTimeoutException : Exception("connect timeout")

    /**
     * Start logging [selections] on every member, all stamped with one
     * freshly minted group id. Boards already carrying a pending session are
     * skipped (download first — starting would clear their flash).
     */
    suspend fun startAll(members: List<Member>, selections: List<SensorSelection>) {
        if (_isBusy.value || members.isEmpty() || selections.isEmpty()) return
        _isBusy.value = true
        _lastPass.value = PassKind.START
        _boards.value = members.map { BoardProgress(it.device.identifier, it.name) }
        val groupID = UUID.randomUUID().toString()
        try {
            for (member in members) {
                startOne(member, selections, groupID)
            }
        } finally {
            _isBusy.value = false
        }
    }

    private suspend fun startOne(member: Member, selections: List<SensorSelection>, groupID: String) {
        val device = member.device
        val id = device.identifier

        if (registry.pendingFor(id).isNotEmpty()) {
            setPhase(id, BoardPhase.Skipped("Already has a session — download it first"))
            return
        }

        setPhase(id, BoardPhase.Connecting)
        val ownsConnection = try {
            connectIfNeeded(device)
        } catch (e: Exception) {
            setPhase(id, BoardPhase.Failed(friendlyConnectError(e)))
            return
        }

        try {
            setPhase(id, BoardPhase.Starting)
            // Always start on a clean slate — wipes unclaimed foreign data by
            // design (surfaced foreign logs get downloaded elsewhere first).
            runCatching { device.clearLog() }

            val started = mutableListOf<Pair<ConfiguredSensor, LogSessionRecord>>()
            try {
                val startedAt = Clock.System.now()
                for (selection in selections) {
                    val sensor = ConfiguredSensor.make(selection, device.modules)
                    val handles = sensor.startLoggingOn(device)
                    val record = LogSessionRecord(
                        deviceId = id,
                        selection = selection,
                        startDate = startedAt,
                        polledHandles = handles,
                        groupID = groupID,
                    )
                    registry.add(record)
                    started.add(sensor to record)
                }
            } catch (e: Exception) {
                rollBack(device, started)
                setPhase(id, BoardPhase.Failed(e.message ?: "Logging did not start"))
                return
            }

            setPhase(id, BoardPhase.Verifying)
            if (confirmEntriesLanding(device)) {
                // Blink while recording; re-arm across disconnects. Persist
                // the event ids so cleanup survives an app restart.
                val ledIds = RecordingHeartbeat.arm(device)
                if (ledIds.isNotEmpty()) {
                    started.forEach { (_, record) ->
                        registry.update(record.id) { it.copy(ledEventIds = ledIds) }
                    }
                }
                setPhase(id, BoardPhase.Logging)
            } else {
                rollBack(device, started)
                setPhase(
                    id,
                    BoardPhase.Failed(
                        "The board's flash is still busy (housekeeping after a clear). " +
                            "Wait a minute and start again.",
                    ),
                )
            }
        } finally {
            if (ownsConnection) runCatching { device.disconnect() }
        }
    }

    private suspend fun rollBack(device: MetaWearDevice, started: List<Pair<ConfiguredSensor, LogSessionRecord>>) {
        for ((sensor, record) in started) {
            runCatching { sensor.stopLoggingOn(device, record.polledHandles) }
        }
        registry.remove(started.map { it.second.id })
    }

    /** Stop + download every member's pending sessions (or recover foreign data). */
    suspend fun stopAndDownloadAll(members: List<Member>) {
        if (_isBusy.value || members.isEmpty()) return
        _isBusy.value = true
        _lastPass.value = PassKind.COLLECT
        _boards.value = members.map { BoardProgress(it.device.identifier, it.name) }
        try {
            for (member in members) {
                val device = member.device
                setPhase(device.identifier, BoardPhase.Connecting)
                val ownsConnection = try {
                    connectIfNeeded(device)
                } catch (e: Exception) {
                    setPhase(device.identifier, BoardPhase.Skipped(friendlyConnectError(e)))
                    continue
                }
                try {
                    collectOne(member)
                } finally {
                    if (ownsConnection) runCatching { device.disconnect() }
                }
            }
        } finally {
            _isBusy.value = false
        }
    }

    /** Never throws — every outcome lands in the board's phase. */
    private suspend fun collectOne(member: Member) {
        val device = member.device
        val id = device.identifier

        // LED first (it must not outlive the session even if download fails),
        // then dismantle the disconnect re-arm events by their persisted ids.
        val pendingAtStart = registry.pendingFor(id)
        RecordingHeartbeat.disarm(device, pendingAtStart.flatMap { it.ledEventIds }.distinct())

        val running = pendingAtStart.filter { it.status == LogSessionRecord.Status.RUNNING }
        if (running.isNotEmpty()) {
            setPhase(id, BoardPhase.Stopping)
            for (record in running) {
                val sensor = ConfiguredSensor.make(record.selection, device.modules)
                runCatching { sensor.stopLoggingOn(device, record.polledHandles) }
                registry.updateStatus(record.id, LogSessionRecord.Status.STOPPED)
            }
            runCatching { device.flushLogPage() }
        }

        setPhase(id, BoardPhase.Downloading)
        val pending = registry.pendingFor(id)
        val result = if (pending.isNotEmpty()) {
            LogDownloader.downloadAll(device, persistence, registry, pending, deviceName = member.name)
        } else {
            // Catch-all: this app has nothing pending, but the board might.
            val entryCount = runCatching { device.read(LogLength()).value }.getOrNull() ?: 0L
            val loggingEnabled = runCatching { device.read(LoggingEnabled()).value }.getOrNull() ?: false
            if (entryCount == 0L && !loggingEnabled) {
                setPhase(id, BoardPhase.Skipped("Nothing to download"))
                return
            }
            LogDownloader.downloadForeign(
                device, persistence,
                OrphanLogState(entryCount, id, loggingEnabled),
                deviceName = member.name,
            )
        }

        setPhase(
            id,
            when {
                result.failed -> BoardPhase.Failed(result.message ?: "Download did not complete")
                result.warning != null && result.snapshots.isEmpty() -> BoardPhase.Failed(result.warning)
                result.warning != null -> BoardPhase.SavedWithIssues(result.snapshots.size, result.warning)
                else -> BoardPhase.Saved(result.snapshots.size)
            },
        )
    }

    /**
     * Ensure a usable link. Returns `true` when this call opened it (and must
     * close it); `false` when the app already held the link (borrowed).
     */
    private suspend fun connectIfNeeded(device: MetaWearDevice): Boolean = when (device.state.value) {
        DeviceState.Idle -> false   // borrowed — caller must NOT disconnect
        DeviceState.Disconnected -> {
            val connected = withTimeoutOrNull(connectTimeoutMs) { device.connect() } != null
            if (!connected) {
                // Cancel any half-open attempt so the next board isn't wedged
                // — but only if a connection is actually in flight.
                if (device.state.value != DeviceState.Disconnected) {
                    runCatching { device.disconnect() }
                }
                throw ConnectTimeoutException()
            }
            true
        }
        else -> throw BoardBusyException()   // another flow owns the board
    }

    /**
     * Entry-count rising is the only trustworthy "genuinely recording"
     * signal: NAND garbage collection after a clear silently swallows samples
     * while reporting a running session.
     */
    private suspend fun confirmEntriesLanding(device: MetaWearDevice): Boolean {
        repeat(verifyAttempts) {
            delay(verifyPeriodMs)
            val count = runCatching { device.read(LogLength()).value }.getOrNull()
            if (count != null && count > 0) return true
        }
        return false
    }

    private fun friendlyConnectError(e: Exception): String = when (e) {
        is ConnectTimeoutException ->
            "Not found nearby — bring the board closer and retry, or download from it individually later"
        is BoardBusyException -> "In use elsewhere in the app — leave its screen and retry"
        else -> e.message ?: "Connection failed"
    }

    private fun setPhase(id: String, phase: BoardPhase) {
        _boards.value = _boards.value.map { if (it.id == id) it.copy(phase = phase) else it }
    }
}
