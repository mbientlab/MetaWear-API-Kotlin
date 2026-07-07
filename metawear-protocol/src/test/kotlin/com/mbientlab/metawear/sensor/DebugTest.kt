package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Module
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Ported from the debug-module suites of MWDebugTemperatureTests.swift
// (temperature is ported separately). Reference vectors from
// MetaWear-SDK-Cpp/test/test_debug.py.
//
// The MWFactoryResetTests.swift suite is NOT ported here: its command
// assertions all run through `MetaWearDevice.factoryReset()` (and mostly
// `startLogging`), neither of which exists in the Kotlin port yet. The two
// debug commands it pins — reset-after-GC `[0xFE, 0x05]` and reset
// `[0xFE, 0x01]` — are covered below.

/** Ported from the "Debug Commands" suite. */
class DebugCommandTest {

    // ---- Simple lifecycle commands ----

    @Test fun reset_command() =
        assertArrayEquals(bytes(0xFE, 0x01), Debug.Reset().commandData)

    @Test fun jumpToBootloader_command() =
        assertArrayEquals(bytes(0xFE, 0x02), Debug.JumpToBootloader().commandData)

    @Test fun disconnect_command() =
        assertArrayEquals(bytes(0xFE, 0x06), Debug.Disconnect().commandData)

    @Test fun resetAfterGc_command() =
        assertArrayEquals(bytes(0xFE, 0x05), Debug.ResetAfterGc().commandData)

    @Test fun enablePowerSave_command() =
        assertArrayEquals(bytes(0xFE, 0x07), Debug.EnablePowerSave().commandData)

    @Test fun reset_moduleAndRegister() {
        val data = Debug.Reset().commandData
        assertEquals(0xFE, data[0].toInt() and 0xFF) // debug module
        assertEquals(0x01, data[1].toInt() and 0xFF) // RESET register
    }

    // ---- Stack overflow assertion — Python test_stack_overflow / test_stack_overflow_disable ----

    @Test fun setStackOverflowAssertion_disable() =
        assertArrayEquals(bytes(0xFE, 0x09, 0x00), Debug.SetStackOverflowAssertion(false).commandData)

    @Test fun setStackOverflowAssertion_enable() =
        assertArrayEquals(bytes(0xFE, 0x09, 0x01), Debug.SetStackOverflowAssertion(true).commandData)

    // ---- Spoof button event — Python test_switch_spoof ----

    @Test fun spoofButtonEvent_value7() =
        // Python test_switch_spoof: value=0x07 → [0xFE, 0x03, 0x01, 0x01, 0x00, 0x07]
        assertArrayEquals(bytes(0xFE, 0x03, 0x01, 0x01, 0x00, 0x07), Debug.SpoofButtonEvent(0x07).commandData)

    @Test fun spoofButtonEvent_fixedPrefix() {
        // The firmware-facing spoof payload always starts with [switch module, reg 1, data_id 0].
        for (value in listOf(0x00, 0x01, 0xAB, 0xFF)) {
            val data = Debug.SpoofButtonEvent(value).commandData
            assertEquals(0xFE, data[0].toInt() and 0xFF)
            assertEquals(0x03, data[1].toInt() and 0xFF)
            assertEquals(0x01, data[2].toInt() and 0xFF)  // switch module id
            assertEquals(0x01, data[3].toInt() and 0xFF)  // switch state register
            assertEquals(0x00, data[4].toInt() and 0xFF)  // data_id
            assertEquals(value, data[5].toInt() and 0xFF)
        }
    }

    // ---- Stack overflow read — Python test_read_overflow_state ----

    @Test fun readStackOverflowState_command() =
        // [0xFE, 0x89] — register 0x09 with the read bit set.
        assertArrayEquals(bytes(0xFE, 0x89), Debug.ReadStackOverflowState().readCommand)

    // ---- Schedule queue read — Python test_read_schedule_queue_state ----

    @Test fun readScheduleQueueUsage_command() =
        // [0xFE, 0x8A] — register 0x0A with the read bit set.
        assertArrayEquals(bytes(0xFE, 0x8A), Debug.ReadScheduleQueueUsage().readCommand)

    @Test fun readables_moduleAndRegister() {
        val overflow = Debug.ReadStackOverflowState()
        assertEquals(Module.DEBUG, overflow.module)
        assertEquals(0x09, overflow.dataRegister)
        assertNull(overflow.packedDataRegister)

        val schedule = Debug.ReadScheduleQueueUsage()
        assertEquals(Module.DEBUG, schedule.module)
        assertEquals(0x0A, schedule.dataRegister)
        assertNull(schedule.packedDataRegister)
    }
}

/**
 * Ported from the "Debug Packet Parsing" suite.
 * Reference vectors from MetaWear-SDK-Cpp/test/test_debug.py.
 */
class DebugParsingTest {

    // Python test_handle_overflow_state:
    //   notify_mw_char([0xfe, 0x89, 0x00, 0x94, 0x0c])
    //   → OverflowState(length=0x0C94, assert_en=0)
    @Test fun parseOverflowState_pythonVector() {
        val state = Debug.ReadStackOverflowState().parseSample(bytes(0xFE, 0x89, 0x00, 0x94, 0x0C))
        assertEquals(0x0C94, state.length)
        assertFalse(state.assertEnabled)
    }

    @Test fun parseOverflowState_assertEnabled() {
        // Any non-zero assert byte → assertEnabled == true
        val state = Debug.ReadStackOverflowState().parseSample(bytes(0xFE, 0x89, 0x01, 0x00, 0x00))
        assertTrue(state.assertEnabled)
        assertEquals(0, state.length)
    }

    @Test fun parseOverflowState_lengthIsLittleEndian() {
        // length_lo=0xCD, length_hi=0xAB → 0xABCD
        val state = Debug.ReadStackOverflowState().parseSample(bytes(0xFE, 0x89, 0x00, 0xCD, 0xAB))
        assertEquals(0xABCD, state.length)
    }

    @Test fun parseOverflowState_shortPacket_throws() {
        // 4-byte packet — missing length high byte.
        assertThrows(MetaWearException.OperationFailed::class.java) {
            Debug.ReadStackOverflowState().parseSample(bytes(0xFE, 0x89, 0x00, 0x94))
        }
    }

    // Python test_handle_schedule_queue_state:
    //   notify_mw_char([0xfe, 0x8a, 0x03, 0x02, 0x01, 0x00, 0x10, 0x01, 0x01, 0x00, 0x00, 0x00, 0x1b, 0x00, 0x1e])
    //   → [0x03, 0x02, 0x01, 0x00, 0x10, 0x01, 0x01, 0x00, 0x00, 0x00, 0x1B, 0x00, 0x1E]
    @Test fun parseScheduleQueueUsage_pythonVector() {
        val usage = Debug.ReadScheduleQueueUsage().parseSample(
            bytes(
                0xFE, 0x8A,
                0x03, 0x02, 0x01, 0x00, 0x10, 0x01, 0x01,
                0x00, 0x00, 0x00, 0x1B, 0x00, 0x1E,
            ),
        )
        assertEquals(
            listOf(0x03, 0x02, 0x01, 0x00, 0x10, 0x01, 0x01, 0x00, 0x00, 0x00, 0x1B, 0x00, 0x1E),
            usage,
        )
    }

    @Test fun parseScheduleQueueUsage_emptyPayload() {
        // Exactly-2-byte packet (just the header) should parse to an empty list.
        assertEquals(emptyList<Int>(), Debug.ReadScheduleQueueUsage().parseSample(bytes(0xFE, 0x8A)))
    }

    @Test fun parseScheduleQueueUsage_shortPacket_throws() {
        // 1-byte packet — not enough to even contain the header.
        assertThrows(MetaWearException.OperationFailed::class.java) {
            Debug.ReadScheduleQueueUsage().parseSample(bytes(0xFE))
        }
    }
}
