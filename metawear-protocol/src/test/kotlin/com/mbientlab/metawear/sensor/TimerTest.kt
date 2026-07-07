package com.mbientlab.metawear.sensor

import app.cash.turbine.test
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.autoReplyModuleDiscovery
import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.makeConnectableTransport
import com.mbientlab.metawear.transport.MockBleTransport
import com.mbientlab.metawear.transport.Uuids
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ported from MWTimerTests.swift. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent
class TimerTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    private suspend fun TestScope.connectedDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice(mac, transport, backgroundScope)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport)
        device.connect()
        discovery.cancel()
        return device to transport
    }

    /**
     * Create a timer, injecting the board's ID-assignment response.
     * Real firmware replies with `[0x0C, 0x02, timer_id]` — a plain
     * notification (high bit clear), NOT a read response. Mirror that here.
     */
    private suspend fun TestScope.createTimer(
        device: MetaWearDevice,
        transport: MockBleTransport,
        periodMs: Long = 1000,
        repetitions: Int = MetaWearTimer.INFINITE,
        immediate: Boolean = false,
        boardAssignedId: Int = 0,
    ): MetaWearTimer {
        val created = async { device.createTimer(periodMs, repetitions, immediate) }
        runCurrent() // create command written; notify waiter parked
        transport.inject(bytes(0x0C, 0x02, boardAssignedId), Uuids.notify)
        runCurrent()
        return created.await()
    }

    /** The 9-byte create command `[0x0C, 0x02, period(4), reps(2), immediate]`. */
    private fun findCreateCommand(transport: MockBleTransport): ByteArray? =
        transport.writtenCommands.firstOrNull {
            it.size == 9 && (it[0].toInt() and 0xFF) == 0x0C && (it[1].toInt() and 0xFF) == 0x02
        }

    // ---- Timer creation ----

    @Test
    fun `createTimer sends period, repetitions, and immediate flag`() = runTest {
        val (device, transport) = connectedDevice()
        createTimer(device, transport, periodMs = 500, repetitions = 10, immediate = true)

        val createCmd = findCreateCommand(transport)
        assertNotNull(createCmd, "createTimer must send [0x0C, 0x02, ...]")
        // period=500ms LE: [0xF4, 0x01, 0x00, 0x00]; repetitions=10 LE: [0x0A, 0x00]; immediate: 0x01
        assertArrayEquals(bytes(0x0C, 0x02, 0xF4, 0x01, 0x00, 0x00, 0x0A, 0x00, 0x01), createCmd)
    }

    @Test
    fun `createTimer encodes infinite repetitions as 0xFFFF`() = runTest {
        val (device, transport) = connectedDevice()
        createTimer(device, transport, repetitions = MetaWearTimer.INFINITE)

        val createCmd = findCreateCommand(transport)!!
        // 0xFFFF LE: [0xFF, 0xFF]
        assertEquals(0xFF, createCmd[6].toInt() and 0xFF)
        assertEquals(0xFF, createCmd[7].toInt() and 0xFF)
    }

    @Test
    fun `createTimer returns the board-assigned id`() = runTest {
        val (device, transport) = connectedDevice()
        val timer = createTimer(device, transport, boardAssignedId = 3)
        assertEquals(3, timer.id)
    }

    @Test
    fun `createTimer preserves period and repetitions`() = runTest {
        val (device, transport) = connectedDevice()
        val timer = createTimer(device, transport, periodMs = 2500, repetitions = 5)
        assertEquals(2500L, timer.periodMs)
        assertEquals(5, timer.repetitions)
    }

    // ---- Start / Stop / Remove ----

    @Test
    fun `startTimer sends the start command`() = runTest {
        val (device, transport) = connectedDevice()
        val timer = createTimer(device, transport, boardAssignedId = 1)
        device.startTimer(timer)

        assertTrue(
            transport.writtenCommands.any { it.contentEquals(bytes(0x0C, 0x03, 0x01)) },
            "startTimer must send [0x0C, 0x03, id]",
        )
    }

    @Test
    fun `stopTimer sends the stop command`() = runTest {
        val (device, transport) = connectedDevice()
        val timer = createTimer(device, transport, boardAssignedId = 2)
        device.stopTimer(timer)

        assertTrue(
            transport.writtenCommands.any { it.contentEquals(bytes(0x0C, 0x04, 0x02)) },
            "stopTimer must send [0x0C, 0x04, id]",
        )
    }

    @Test
    fun `removeTimer sends the remove command`() = runTest {
        val (device, transport) = connectedDevice()
        val timer = createTimer(device, transport, boardAssignedId = 0)
        device.removeTimer(timer)

        assertTrue(
            transport.writtenCommands.any { it.contentEquals(bytes(0x0C, 0x05, 0x00)) },
            "removeTimer must send [0x0C, 0x05, id]",
        )
    }

    @Test
    fun `removeAllTimers sweeps all eight timer slots`() = runTest {
        val (device, transport) = connectedDevice()
        transport.clearWrites()
        device.removeAllTimers()

        for (id in 0 until 8) {
            assertTrue(transport.writtenCommands.any { it.contentEquals(bytes(0x0C, 0x05, id)) })
        }
    }

    @Test
    fun `setTimerNotify enable`() = runTest {
        val (device, transport) = connectedDevice()
        val timer = createTimer(device, transport, boardAssignedId = 0)
        device.setTimerNotify(timer, enabled = true)

        assertTrue(transport.writtenCommands.any { it.contentEquals(bytes(0x0C, 0x07, 0x00, 0x01)) })
    }

    @Test
    fun `setTimerNotify disable`() = runTest {
        val (device, transport) = connectedDevice()
        val timer = createTimer(device, transport, boardAssignedId = 0)
        device.setTimerNotify(timer, enabled = false)

        assertTrue(transport.writtenCommands.any { it.contentEquals(bytes(0x0C, 0x07, 0x00, 0x00)) })
    }

    // ---- Notification stream ----

    @Test
    fun `streamTimer delivers ticks`() = runTest {
        val (device, transport) = connectedDevice()
        val timer = createTimer(device, transport, boardAssignedId = 0)

        device.streamTimer(timer).test {
            // Inject two tick notifications for our timer
            transport.inject(bytes(0x0C, 0x06, 0x00), Uuids.notify)
            transport.inject(bytes(0x0C, 0x06, 0x00), Uuids.notify)
            assertEquals(0, awaitItem())
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `streamTimer filters ticks from other timer ids`() = runTest {
        val (device, transport) = connectedDevice()
        val timer = createTimer(device, transport, boardAssignedId = 1)

        device.streamTimer(timer).test {
            // Tick for timer 0 (different ID — must be filtered), then ours.
            transport.inject(bytes(0x0C, 0x06, 0x00), Uuids.notify)
            transport.inject(bytes(0x0C, 0x06, 0x01), Uuids.notify)
            assertEquals(1, awaitItem(), "Must filter ticks from other timer IDs")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- Period encoding ----

    @Test
    fun `period 1000 ms encodes little-endian`() = runTest {
        val (device, transport) = connectedDevice()
        createTimer(device, transport, periodMs = 1000)

        // 1000 = 0x000003E8 LE: [0xE8, 0x03, 0x00, 0x00]
        val cmd = findCreateCommand(transport)!!
        assertArrayEquals(bytes(0x0C, 0x02, 0xE8, 0x03, 0x00, 0x00, 0xFF, 0xFF, 0x00), cmd)
    }

    @Test
    fun `period 60000 ms encodes little-endian`() = runTest {
        val (device, transport) = connectedDevice()
        createTimer(device, transport, periodMs = 60_000)

        // 60000 = 0x0000EA60 LE: [0x60, 0xEA, 0x00, 0x00]
        val cmd = findCreateCommand(transport)!!
        assertArrayEquals(bytes(0x0C, 0x02, 0x60, 0xEA, 0x00, 0x00, 0xFF, 0xFF, 0x00), cmd)
    }
}
