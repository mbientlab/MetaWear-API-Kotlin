package com.mbientlab.metawear.app

import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.data.LogSessionRecord
import com.mbientlab.metawear.app.data.LogSessionRegistry
import com.mbientlab.metawear.app.demo.DemoBleTransport
import com.mbientlab.metawear.app.vm.GroupCaptureCoordinator
import com.mbientlab.metawear.app.vm.GroupCaptureCoordinator.BoardPhase
import com.mbientlab.metawear.app.vm.GroupCaptureCoordinator.Member
import com.mbientlab.metawear.persistence.PersistenceStore
import com.mbientlab.metawear.transport.BleTransport
import com.mbientlab.metawear.transport.ScanResult
import com.mbientlab.metawear.transport.WriteType
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fleet walks over the demo-board fleet, end-to-end through the real device
 * stack and the real persistence store (in-memory DAO) — no Android anywhere.
 */
class GroupCaptureCoordinatorTest {

    private class Rig(val scope: CoroutineScope) {
        val persistence = PersistenceStore(InMemoryPersistenceDao())
        val registry = LogSessionRegistry(null)
        val coordinator = GroupCaptureCoordinator(
            persistence, registry,
            connectTimeoutMs = 1_500,
            verifyPeriodMs = 400,
            verifyAttempts = 10,
        )
        val members = (0 until 2).map { index ->
            val identity = DemoBleTransport.Identity.board(index)
            Member(
                device = MetaWearDevice(identity.identifier, DemoBleTransport(scope, identity), scope),
                name = if (index == 0) "Simulated MetaWear" else "Simulated MetaWear ${index + 1}",
            )
        }
        val accelSelection = listOf(SensorSelection(SensorKey.ACCELEROMETER))
    }

    private fun <T> withRig(block: suspend Rig.() -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return try {
            runBlocking { withTimeout(60_000) { Rig(scope).block() } }
        } finally {
            scope.cancel()
        }
    }

    /** A board that never answers a connect attempt. */
    private class PhantomTransport : BleTransport {
        override fun scan(services: List<UUID>?): Flow<ScanResult> = emptyFlow()
        override suspend fun connect(identifier: String): Unit = awaitCancellation()
        override suspend fun disconnect() = Unit
        override suspend fun write(data: ByteArray, characteristic: UUID, type: WriteType) = Unit
        override suspend fun read(characteristic: UUID): ByteArray = ByteArray(0)
        override fun notifications(characteristic: UUID): Flow<ByteArray> = emptyFlow()
        override suspend fun readRSSI(): Int = -100
    }

    @Test
    fun `startAll starts every board under one group id and disconnects`() = withRig {
        coordinator.startAll(members, accelSelection)

        coordinator.boards.value.forEach { board ->
            assertEquals(BoardPhase.Logging, board.phase, "board ${board.name}: ${board.phase}")
        }
        val records = registry.records.value.filter { it.groupID != null }
        assertEquals(2, records.size)
        assertEquals(1, records.map { it.groupID }.toSet().size)     // one shared batch
        assertEquals(2, records.map { it.deviceId }.toSet().size)    // one per board
        members.forEach { assertEquals(DeviceState.Disconnected, it.device.state.value) }
    }

    @Test
    fun `stop and download saves group-tagged named sessions`() = withRig {
        coordinator.startAll(members, accelSelection)
        delay(2_000)   // let both boards "record"
        coordinator.stopAndDownloadAll(members)

        coordinator.boards.value.forEach { board ->
            val phase = board.phase
            assertTrue(phase is BoardPhase.Saved && phase.count >= 1, "board ${board.name}: $phase")
        }
        // Pending list fully drained.
        assertTrue(registry.records.value.none { it.groupID != null && it.status != LogSessionRecord.Status.DOWNLOADED })

        val sessions = persistence.fetchAllSessions()
        assertEquals(2, sessions.size)
        assertEquals(1, sessions.mapNotNull { it.groupID }.toSet().size)
        assertEquals(2, sessions.map { it.deviceID }.toSet().size)
        sessions.forEach { session ->
            assertTrue(
                session.deviceName?.startsWith("Simulated MetaWear") == true,
                "session name was ${session.deviceName}",
            )
            assertTrue(session.sampleCount > 0)
        }
    }

    @Test
    fun `board with existing pending session is skipped on start`() = withRig {
        registry.add(
            LogSessionRecord(
                deviceId = members[0].device.identifier,
                selection = accelSelection[0],
                startDate = Clock.System.now(),
                status = LogSessionRecord.Status.STOPPED,
            ),
        )

        coordinator.startAll(members, accelSelection)

        assertEquals(
            BoardPhase.Skipped("Already has a session — download it first"),
            coordinator.boards.value[0].phase,
        )
        assertEquals(BoardPhase.Logging, coordinator.boards.value[1].phase)
        // The pre-existing solo record was not adopted into the group.
        assertNull(registry.records.value.first { it.status == LogSessionRecord.Status.STOPPED }.groupID)
    }

    @Test
    fun `unresolvable board fails fast without wedging the walk`() = withRig {
        val phantom = Member(
            device = MetaWearDevice("PH:AN:TO:M0:00:00", PhantomTransport(), scope),
            name = "Phantom",
        )

        coordinator.startAll(listOf(phantom, members[0]), accelSelection)

        assertFalse(coordinator.isBusy.value)
        assertTrue(coordinator.boards.value[0].phase is BoardPhase.Failed)
        assertEquals(BoardPhase.Logging, coordinator.boards.value[1].phase)
    }

    @Test
    fun `board with nothing to download is skipped on collect`() = withRig {
        coordinator.stopAndDownloadAll(listOf(members[0]))

        assertEquals(BoardPhase.Skipped("Nothing to download"), coordinator.boards.value[0].phase)
        assertTrue(registry.records.value.isEmpty())
    }
}
