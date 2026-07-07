package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.autoReply
import com.mbientlab.metawear.autoReplyModuleDiscovery
import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.makeConnectableTransport
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.PacketParser
import com.mbientlab.metawear.protocol.Pollable
import com.mbientlab.metawear.transport.MockBleTransport
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Miscellaneous readables: log length, log time, and MAC address.

// ---- LogLength — shape + parsing ----

class LogLengthTest {

    @Test fun module_dataRegister() {
        val r = LogLength()
        assertEquals(Module.LOGGING, r.module)
        assertEquals(0x05, r.dataRegister)
    }

    @Test fun readCommand_hasReadBit() =
        assertArrayEquals(bytes(0x0B, 0x85), LogLength().readCommand)

    // Raw UInt32 LE at offset 2 → 0x0000_04D2 = 1234 entries
    @Test fun parse_uint32LE() =
        assertEquals(1234L, LogLength().parseSample(bytes(0x0B, 0x85, 0xD2, 0x04, 0x00, 0x00)))

    @Test fun parse_zero() =
        assertEquals(0L, LogLength().parseSample(bytes(0x0B, 0x85, 0x00, 0x00, 0x00, 0x00)))

    @Test fun parse_maxUInt32() =
        assertEquals(4294967295L, LogLength().parseSample(bytes(0x0B, 0x85, 0xFF, 0xFF, 0xFF, 0xFF)))

    @Test fun parse_shortPacket_throws() {
        val error = runCatching { LogLength().parseSample(bytes(0x0B, 0x85, 0x00, 0x00)) }.exceptionOrNull()
        assertTrue(error is MetaWearException.OperationFailed)
        assertEquals("Operation failed: Log length packet too short: 4 bytes", error?.message)
    }
}

// ---- LastResetTime — shape + parsing ----

class LastResetTimeTest {

    @Test fun module_dataRegister() {
        val r = LastResetTime()
        assertEquals(Module.LOGGING, r.module)
        assertEquals(0x04, r.dataRegister)
    }

    @Test fun readCommand_hasReadBit() =
        assertArrayEquals(bytes(0x0B, 0x84), LastResetTime().readCommand)

    // tick=0, reset_uid=0 → reset time ≈ now
    @Test fun parse_zeroTick_returnsNow() {
        val before = Clock.System.now()
        val parsed = LastResetTime().parseSample(bytes(0x0B, 0x84, 0x00, 0x00, 0x00, 0x00, 0x00))
        val after = Clock.System.now()

        assertTrue(parsed.epoch >= before - 100.milliseconds)
        assertTrue(parsed.epoch <= after + 100.milliseconds)
        assertEquals(0, parsed.resetUID)
    }

    // tick=1000 → 1000 × 1.4648 ms ≈ 1.465 s in the past
    @Test fun parse_1000tick_returnsPast() {
        val now = Clock.System.now()
        val parsed = LastResetTime().parseSample(bytes(0x0B, 0x84, 0xE8, 0x03, 0x00, 0x00, 0x03)) // 1000 LE, reset_uid=3

        val elapsedMs = (now - parsed.epoch).toDouble(DurationUnit.MILLISECONDS)
        val expectedMs = 1000.0 * PacketParser.MS_PER_TICK // ≈ 1464.8 ms
        assertTrue(abs(elapsedMs - expectedMs) < 100.0)
        assertEquals(3, parsed.resetUID)
    }

    // The trailing reset_uid byte is masked to 3 bits (RESET_UID_MASK = 0x07)
    // — anything in the upper bits is firmware bookkeeping we ignore.
    @Test fun parse_resetUID_masksToLowThreeBits() {
        val parsed = LastResetTime().parseSample(bytes(0x0B, 0x84, 0x00, 0x00, 0x00, 0x00, 0xFF))
        assertEquals(0x07, parsed.resetUID)
    }

    @Test fun parse_shortPacket_throws() {
        // 6 bytes — missing the trailing reset_uid — is considered short.
        val error = runCatching {
            LastResetTime().parseSample(bytes(0x0B, 0x84, 0x00, 0x00, 0x00, 0x00))
        }.exceptionOrNull()
        assertTrue(error is MetaWearException.OperationFailed)
        assertEquals("Operation failed: Log time packet too short: 6 bytes", error?.message)
    }
}

// ---- MacAddress — alias of Settings.ReadMacAddress ----
//
// MacAddress is a typealias for the canonical
// Settings.ReadMacAddress; these tests pin the shared wire identity through
// the alias so a future divergence fails loudly.

class MacAddressTest {

    @Test fun module_dataRegister() {
        val r = MacAddress()
        assertEquals(Module.SETTINGS, r.module)
        assertEquals(0x0B, r.dataRegister)
    }

    @Test fun readCommand_hasReadBit() =
        assertArrayEquals(bytes(0x11, 0x8B), MacAddress().readCommand)

    // 6-byte payload (older firmware): little-endian MAC bytes, canonical form reversed.
    @Test fun parse_sixBytePayload() =
        assertEquals(
            "E5:C7:19:EF:F5:CA",
            MacAddress().parseSample(bytes(0x11, 0x8B, 0xCA, 0xF5, 0xEF, 0x19, 0xC7, 0xE5)),
        )

    // 7-byte payload (newer firmware): leading address-type byte is skipped.
    @Test fun parse_sevenBytePayload_skipsAddressType() =
        assertEquals(
            "E5:C7:19:EF:F5:CA",
            MacAddress().parseSample(bytes(0x11, 0x8B, 0x01, 0xCA, 0xF5, 0xEF, 0x19, 0xC7, 0xE5)),
        )
}

// ---- Pollable conformances ----
//
// The types declare Pollable directly, so the compile-time assignments below
// are the assertions. Conformances for other modules' readables (battery,
// humidity, power/charge status, …) live with those types.

class MiscReadablesPollableConformanceTest {

    @Test fun logLength_isPollable() {
        val pollable: Pollable<Long> = LogLength()
        assertEquals(Module.LOGGING, pollable.module)
    }

    @Test fun lastResetTime_isPollable() {
        val pollable: Pollable<LastResetTime.Reading> = LastResetTime()
        assertEquals(Module.LOGGING, pollable.module)
    }

    @Test fun macAddress_isPollable() {
        val pollable: Pollable<String> = MacAddress()
        assertEquals(Module.SETTINGS, pollable.module)
    }
}

// ---- Mock-device one-shot reads ----

class MiscReadablesDeviceTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    private suspend fun TestScope.connectedDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice(mac, transport, backgroundScope)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport)
        device.connect()
        discovery.cancel()
        transport.clearWrites()
        return device to transport
    }

    @Test
    fun `read log length returns entry count`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x0B && (cmd[1].toInt() and 0xFF) == 0x85) {
                bytes(0x0B, 0x85, 0xD2, 0x04, 0x00, 0x00)
            } else {
                null
            }
        }

        val length = device.read(LogLength())
        replies.cancel()

        assertEquals(1234L, length.value)
    }

    @Test
    fun `read MAC address returns canonical string`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x11 && (cmd[1].toInt() and 0xFF) == 0x8B) {
                bytes(0x11, 0x8B, 0xCA, 0xF5, 0xEF, 0x19, 0xC7, 0xE5)
            } else {
                null
            }
        }

        val address = device.read(MacAddress())
        replies.cancel()

        assertEquals("E5:C7:19:EF:F5:CA", address.value)
    }

    @Test
    fun `read last reset time returns epoch and reset uid`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x0B && (cmd[1].toInt() and 0xFF) == 0x84) {
                bytes(0x0B, 0x84, 0xE8, 0x03, 0x00, 0x00, 0x03) // tick=1000, reset_uid=3
            } else {
                null
            }
        }

        val reset = device.read(LastResetTime())
        replies.cancel()

        assertEquals(3, reset.value.resetUID)
    }
}
