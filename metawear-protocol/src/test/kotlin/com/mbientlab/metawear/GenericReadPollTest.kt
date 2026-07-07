package com.mbientlab.metawear

import app.cash.turbine.test
import com.mbientlab.metawear.sensor.Humidity
import com.mbientlab.metawear.sensor.LogLength
import com.mbientlab.metawear.sensor.MacAddress
import com.mbientlab.metawear.transport.MockBleTransport
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Generic read/poll coverage — the readable-specific pieces not
// already covered by MetaWearDeviceTest's battery read/poll suite: humidity,
// log length, and MAC address reads plus humidity polling.

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GenericReadPollTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    /** Modules the stub board reports present: IMU set + logging, settings, humidity. */
    private val presentModules = setOf(0x03, 0x13, 0x12, 0x15, 0x19, 0x0B, 0x11, 0x16)

    private suspend fun TestScope.connectedDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice(mac, transport, backgroundScope)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport, presentModules)
        device.connect()
        discovery.cancel()
        return device to transport
    }

    // ---- Generic device.read(...) ----

    @Test
    fun `read humidity yields timestamped float`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x16 && (cmd[1].toInt() and 0xFF) == 0x81) {
                // BME280 vector: raw 64711 / 1024 = 63.1943359375 %
                bytes(0x16, 0x81, 0xC7, 0xFC, 0x00, 0x00)
            } else {
                null
            }
        }

        val before = Clock.System.now()
        val sample = device.read(Humidity())
        val after = Clock.System.now()
        replies.cancel()

        assertEquals(64711.0f / 1024.0f, sample.value)
        assertTrue(sample.time >= before)
        assertTrue(sample.time <= after)
    }

    @Test
    fun `read log length yields entry count`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x0B && (cmd[1].toInt() and 0xFF) == 0x85) {
                // 0x0000_04D2 = 1234 entries
                bytes(0x0B, 0x85, 0xD2, 0x04, 0x00, 0x00)
            } else {
                null
            }
        }

        val sample = device.read(LogLength())
        replies.cancel()
        assertEquals(1234L, sample.value)
    }

    @Test
    fun `read mac address yields colon string`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x11 && (cmd[1].toInt() and 0xFF) == 0x8B) {
                bytes(0x11, 0x8B, 0x01, 0x07, 0x7B, 0x52, 0x8F, 0xC9, 0xE8)
            } else {
                null
            }
        }

        val sample = device.read(MacAddress())
        replies.cancel()
        assertEquals("E8:C9:8F:52:7B:07", sample.value)
    }

    // ---- device.poll(...) ----

    @Test
    fun `poll delivers multiple samples`() = runTest {
        val (device, transport) = connectedDevice()
        // Serve every humidity read with a 48% reading (0xC000 / 1024 = 48).
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x16 && (cmd[1].toInt() and 0xFF) == 0x81) {
                bytes(0x16, 0x81, 0x00, 0xC0, 0x00, 0x00)
            } else {
                null
            }
        }

        device.poll(Humidity(), every = 10.milliseconds).test {
            val samples = listOf(awaitItem(), awaitItem(), awaitItem())
            assertTrue(samples.all { it.value == 48.0f })
            // Times should be non-decreasing.
            samples.zipWithNext().forEach { (a, b) -> assertTrue(a.time <= b.time) }
            cancelAndIgnoreRemainingEvents()
        }
        replies.cancel()
    }

    @Test
    fun `poll cancellation stops stream`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x16 && (cmd[1].toInt() and 0xFF) == 0x81) {
                bytes(0x16, 0x81, 0x00, 0xC0, 0x00, 0x00)
            } else {
                null
            }
        }

        // Consume one sample, then abandon the flow — cancellation must
        // propagate cleanly (no crash / deadlock).
        device.poll(Humidity(), every = 10.milliseconds).test {
            assertEquals(48.0f, awaitItem().value)
            cancelAndIgnoreRemainingEvents()
        }
        replies.cancel()
    }
}
