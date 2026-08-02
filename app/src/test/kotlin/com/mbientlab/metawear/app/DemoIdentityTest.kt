package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.demo.DemoBleTransport
import com.mbientlab.metawear.transport.Uuids
import com.mbientlab.metawear.transport.WriteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The demo fleet's identity contract. Multi-board flows (group logging,
 * per-board attribution) are validated against several [DemoBleTransport]
 * instances — that only works if each board wears a distinct, STABLE
 * identity, and board 0 stays byte-for-byte compatible with the legacy
 * single demo device (its identifier may be persisted in remembered-device
 * maps from older builds).
 */
class DemoIdentityTest {

    private fun <T> withScope(block: suspend (CoroutineScope) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return try {
            runBlocking { withTimeout(10_000) { block(scope) } }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `board zero is the legacy demo device`() {
        val board = DemoBleTransport.Identity.board(0)
        assertEquals(DemoBleTransport.DEVICE_IDENTIFIER, board.identifier)
        assertEquals("DEMO01", board.serial)
        assertEquals(listOf(0x01, 0xE0, 0x0D, 0x0E, 0x3D, 0xDE), board.macLSBFirst)
        assertEquals(0.0, board.phaseOffset)
    }

    @Test
    fun `fleet identities are distinct and stable`() {
        val fleet = (0 until 3).map { DemoBleTransport.Identity.board(it) }
        assertEquals(3, fleet.map { it.identifier }.toSet().size)
        assertEquals(listOf("DEMO01", "DEMO02", "DEMO03"), fleet.map { it.serial })
        assertEquals(3, fleet.map { it.macLSBFirst }.toSet().size)
        // Stable across calls — persisted identifier maps depend on it.
        assertEquals(fleet[2], DemoBleTransport.Identity.board(2))
    }

    @Test
    fun `transport serves its identity serial`() = withScope { scope ->
        val transport = DemoBleTransport(scope, DemoBleTransport.Identity.board(1))
        val serial = transport.read(Uuids.serialNumber)
        assertEquals("DEMO02", serial.toString(Charsets.UTF_8))
    }

    /**
     * The waveform phase offset must NOT skew the logging clock: each board's
     * time reference derives from the `[0x0B, 0x84]` tick, so a leaked offset
     * would misalign "simultaneous" demo logs across the fleet by up to
     * 1.8 s.
     */
    @Test
    fun `logging clock is wall-true across the fleet`() = withScope { scope ->
        val ticks = mutableListOf<Long>()
        for (index in listOf(0, 2)) {
            val identity = DemoBleTransport.Identity.board(index)
            val transport = DemoBleTransport(scope, identity)
            val notifications = transport.notifications(Uuids.notify)
            transport.connect(identity.identifier)
            transport.write(byteArrayOf(0x0B, 0x84.toByte()), Uuids.command, WriteType.WITHOUT_RESPONSE)
            val packet = notifications.first()
            assertEquals(7, packet.size)
            ticks.add(
                (packet[2].toLong() and 0xFF) or ((packet[3].toLong() and 0xFF) shl 8) or
                    ((packet[4].toLong() and 0xFF) shl 16) or ((packet[5].toLong() and 0xFF) shl 24),
            )
        }
        // Board 2's phase offset is 1.8 s ≈ 1229 ticks; wall-true clocks read
        // within a handful of ticks of each other (test overhead).
        val delta = if (ticks[0] > ticks[1]) ticks[0] - ticks[1] else ticks[1] - ticks[0]
        assertTrue(delta < 100, "fleet logging clocks diverged by $delta ticks")
    }

    @Test
    fun `transport serves its identity MAC`() = withScope { scope ->
        val identity = DemoBleTransport.Identity.board(2)
        val transport = DemoBleTransport(scope, identity)
        val notifications = transport.notifications(Uuids.notify)
        transport.connect(identity.identifier)
        // Settings MAC read: [0x11, 0x8B] → [0x11, 0x8B, 0x01(type)] + 6-byte LE MAC
        transport.write(byteArrayOf(0x11, 0x8B.toByte()), Uuids.command, WriteType.WITHOUT_RESPONSE)
        val packet = notifications.first()
        assertArrayEquals(
            byteArrayOf(
                0x11, 0x8B.toByte(), 0x01,
                0x03, 0xE0.toByte(), 0x0D, 0x0E, 0x3D, 0xDE.toByte(),
            ),
            packet,
        )
    }
}
