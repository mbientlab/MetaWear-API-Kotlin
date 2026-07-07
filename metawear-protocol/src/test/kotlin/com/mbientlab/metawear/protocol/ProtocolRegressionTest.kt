package com.mbientlab.metawear.protocol

import com.mbientlab.metawear.LoggerChunk
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.RawLogEntry
import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.decodeEntries
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.sensor.DataProcessor
import com.mbientlab.metawear.sensor.Led
import com.mbientlab.metawear.sensor.LedPattern
import com.mbientlab.metawear.transport.MockBleTransport
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression guards for real
 * bugs: math-processor firmware op codes, LED repeat-count encoding, log
 * chunk reassembly at high ODR (same-tick samples), and MAC-address response
 * length handling.
 */
class ProtocolRegressionTest {

    // ---- Math processor — firmware op codes ----
    //
    // Regression guard for a real bug: an earlier draft of `Math.Operation`
    // used a 0-indexed table from a buggy protocol document, which made ADD a
    // no-op and SUBTRACT perform addition on the board. The firmware op codes
    // (verified against `MblMwMathOperation` in MetaWear-SDK-Cpp, written to
    // the wire verbatim) are pinned here one by one so any future renumbering
    // fails loudly.

    private val firmwareOpCodes = listOf(
        DataProcessor.Math.Operation.ADD to 1,
        DataProcessor.Math.Operation.MULTIPLY to 2,
        DataProcessor.Math.Operation.DIVIDE to 3,
        DataProcessor.Math.Operation.MODULO to 4,
        DataProcessor.Math.Operation.EXPONENT to 5,
        DataProcessor.Math.Operation.SQRT to 6,
        DataProcessor.Math.Operation.LSHIFT to 7,
        DataProcessor.Math.Operation.RSHIFT to 8,
        DataProcessor.Math.Operation.SUBTRACT to 9,
        DataProcessor.Math.Operation.ABS to 10,
        DataProcessor.Math.Operation.CONSTANT to 11,
    )

    @Test
    fun `op codes match firmware`() {
        for ((op, expected) in firmwareOpCodes) {
            assertEquals(expected, op.value, op.name)
        }
    }

    @Test
    fun `op code table is exhaustive`() {
        // No phantom operations: the firmware defines exactly these 11.
        // Negate/floor/ceil/round do not exist on the wire.
        assertEquals(firmwareOpCodes.size, DataProcessor.Math.Operation.entries.size)
    }

    @Test
    fun `configBytes put op code at byte 1`() {
        // subtract is the case the old table got most wrong (1 → firmware add).
        val config = DataProcessor.Math(
            operation = DataProcessor.Math.Operation.SUBTRACT,
            rhs = 5,
            signed = true,
        )
        val configBytes = config.configBytes(inputLength = 4, inputChannels = 1, inputSigned = true)
        // [byte0, op, rhs(4 LE), n_channels]
        assertEquals(7, configBytes.size)
        assertEquals(9, configBytes[1].toInt() and 0xFF)
        assertArrayEquals(bytes(0x05, 0x00, 0x00, 0x00), configBytes.copyOfRange(2, 6))
    }

    // ---- LED repeat count encoding ----
    //
    // The firmware treats a raw repeat count of 0 as undefined behaviour; the
    // C++ SDK's guidance is "use 0xFF, not 0". The encoder must never emit 0.

    @Test
    fun `default pattern repeats indefinitely`() {
        assertEquals(0xFF, LedPattern().repeatCount)
    }

    @Test
    fun `explicit zero is encoded as indefinite`() {
        val pattern = LedPattern(repeatCount = 0)
        val cmd = Led.SetPattern(Led.Color.GREEN, pattern).commandData
        // [0x02, 0x03, channel, mode, hi, lo, rise(2), high(2), fall(2),
        //  pulse(2), delay(2), repeat] — repeat is the final byte.
        assertEquals(17, cmd.size)
        assertEquals(0xFF, cmd.last().toInt() and 0xFF)
    }

    @Test
    fun `finite count is preserved`() {
        val pattern = LedPattern(repeatCount = 3)
        val cmd = Led.SetPattern(Led.Color.BLUE, pattern).commandData
        assertEquals(3, cmd.last().toInt() and 0xFF)
    }

    // ---- Log reassembly at high ODR (same-tick samples) ----
    //
    // One tick is ≈1.465 ms, so at 800/1600 Hz two distinct samples can land
    // on the same tick. The decoder pairs chunk entries by per-logger-ID
    // arrival order (like the C++ SDK) — an earlier draft grouped by identical
    // (resetUID, tick), which collapsed same-tick samples into one and dropped
    // data. Fixture: two 2-chunk samples, all four entries at tick=1.

    private fun TestScope.device(): MetaWearDevice =
        MetaWearDevice("AA:BB:CC:DD:EE:FF", MockBleTransport(), backgroundScope)

    @Test
    fun `two samples sharing a tick both decode`() = runTest {
        val entries = listOf(
            // Sample 1: chunk id 0 (data 0x00004000), chunk id 1 (data 0x00002000)
            RawLogEntry.fromEntryBytes(bytes(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00)),
            RawLogEntry.fromEntryBytes(bytes(0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00)),
            // Sample 2: same tick, different data (0x00008000 / 0x00001000)
            RawLogEntry.fromEntryBytes(bytes(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x80, 0x00, 0x00)),
            RawLogEntry.fromEntryBytes(bytes(0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00)),
        )
        val chunks = listOf(LoggerChunk(id = 0, byteCount = 4), LoggerChunk(id = 1, byteCount = 2))

        val samples = device().decodeEntries(entries, chunks) { it }

        assertEquals(2, samples.size)
        val payloads = samples.map { it.value }
        assertTrue(payloads.any { it.contentEquals(bytes(0x00, 0x40, 0x00, 0x00, 0x00, 0x20)) })
        assertTrue(payloads.any { it.contentEquals(bytes(0x00, 0x80, 0x00, 0x00, 0x00, 0x10)) })
    }

    @Test
    fun `chunks pair by arrival order not interleaved`() = runTest {
        // Chunk entries arrive grouped per sample; pairing index i of queue 0
        // with index i of queue 1 must not mix sample 1's first chunk with
        // sample 2's second chunk.
        val entries = listOf(
            RawLogEntry.fromEntryBytes(bytes(0x00, 0x0A, 0x00, 0x00, 0x00, 0x11, 0x11, 0x11, 0x11)),
            RawLogEntry.fromEntryBytes(bytes(0x01, 0x0A, 0x00, 0x00, 0x00, 0x22, 0x22, 0x22, 0x22)),
            RawLogEntry.fromEntryBytes(bytes(0x00, 0x0B, 0x00, 0x00, 0x00, 0x33, 0x33, 0x33, 0x33)),
            RawLogEntry.fromEntryBytes(bytes(0x01, 0x0B, 0x00, 0x00, 0x00, 0x44, 0x44, 0x44, 0x44)),
        )
        val chunks = listOf(LoggerChunk(id = 0, byteCount = 4), LoggerChunk(id = 1, byteCount = 2))

        val samples = device().decodeEntries(entries, chunks) { it }

        assertEquals(2, samples.size)
        assertArrayEquals(bytes(0x11, 0x11, 0x11, 0x11, 0x22, 0x22), samples[0].value)
        assertArrayEquals(bytes(0x33, 0x33, 0x33, 0x33, 0x44, 0x44), samples[1].value)
    }

    @Test
    fun `incomplete trailing sample is dropped`() = runTest {
        // Last sample's second chunk was cut off by the end of the download.
        val entries = listOf(
            RawLogEntry.fromEntryBytes(bytes(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00)),
            RawLogEntry.fromEntryBytes(bytes(0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00)),
            RawLogEntry.fromEntryBytes(bytes(0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x80, 0x00, 0x00)),
        )
        val chunks = listOf(LoggerChunk(id = 0, byteCount = 4), LoggerChunk(id = 1, byteCount = 2))

        val samples = device().decodeEntries(entries, chunks) { it }
        assertEquals(1, samples.size)
    }

    // ---- MAC address response lengths ----
    //
    // The payload is 6 bytes on older firmware and 7 bytes (leading
    // address-type byte) on current firmware. C++ `convert_to_mac_address`
    // handles both via `offset = len == 7 ? 1 : 0`; this parser must too.

    @Test
    fun `seven byte payload current firmware`() {
        // mbientlab python reference vector (test_settings.py::test_mac_address)
        val packet = bytes(0x11, 0x8B, 0x01, 0x07, 0x7B, 0x52, 0x8F, 0xC9, 0xE8)
        assertEquals("E8:C9:8F:52:7B:07", PacketParser.parseMacAddress(packet))
    }

    @Test
    fun `six byte payload older firmware`() {
        val packet = bytes(0x11, 0x8B, 0x07, 0x7B, 0x52, 0x8F, 0xC9, 0xE8)
        assertEquals("E8:C9:8F:52:7B:07", PacketParser.parseMacAddress(packet))
    }

    @Test
    fun `too short throws`() {
        val error = runCatching {
            PacketParser.parseMacAddress(bytes(0x11, 0x8B, 0x01, 0x02))
        }.exceptionOrNull()
        assertTrue(error is MetaWearException)
    }
}
