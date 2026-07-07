package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser
import com.mbientlab.metawear.protocol.Readable

// Debug module (0xFE).

/**
 * Commands for the MetaWear debug module (0xFE).
 * These control board lifecycle: reset, DFU bootloader, and clean disconnect.
 */
object Debug {

    /** Soft-reset the board. The BLE connection will drop; reconnect after ~1 second. */
    class Reset : Command {
        override val commandData: ByteArray = Packet.command(Module.DEBUG, 0x01)
    }

    /**
     * Jump to DFU (Device Firmware Update) bootloader mode.
     * The board will appear as a Nordic DFU target over BLE.
     */
    class JumpToBootloader : Command {
        override val commandData: ByteArray = Packet.command(Module.DEBUG, 0x02)
    }

    /**
     * Cleanly disconnect from the board (board-initiated disconnect).
     * Prefer this over dropping BLE from the host side so the board cleans up state.
     */
    class Disconnect : Command {
        override val commandData: ByteArray = Packet.command(Module.DEBUG, 0x06)
    }

    /**
     * Reset after garbage collection completes.
     * Used after macro / event recording to apply changes.
     */
    class ResetAfterGc : Command {
        override val commandData: ByteArray = Packet.command(Module.DEBUG, 0x05)
    }

    /** Enable low-power (sleep) mode. */
    class EnablePowerSave : Command {
        override val commandData: ByteArray = Packet.command(Module.DEBUG, 0x07)
    }

    // ---- Stack overflow assertion (firmware rev ≥ 2) ----
    //
    // Register 0x09 in C++ `debug.cpp` (`DebugRegister::STACK_OVERFLOW`).
    // C++ `mbl_mw_debug_set_stack_overflow_assertion` coerces any non-zero
    // enable value to exactly 1 before sending.

    /**
     * Enable or disable stack-overflow assertion monitoring.
     *
     * Emits `[0xFE, 0x09, enable]` where `enable` is 0 or 1. Python reference
     * vectors (from `test_debug.py::test_stack_overflow`):
     * ```
     * enable=false → [0xFE, 0x09, 0x00]
     * enable=true  → [0xFE, 0x09, 0x01]
     * ```
     */
    class SetStackOverflowAssertion(val enable: Boolean) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.DEBUG, 0x09, if (enable) 1 else 0)
    }

    /**
     * Decoded response of `mbl_mw_debug_read_stack_overflow_state`.
     *
     * Matches C++ `OverflowState` struct — 1-byte enable flag followed by a
     * little-endian UInt16 length counter (bytes used on the stack high-water mark).
     */
    data class OverflowState(
        /** Stack high-water mark, in bytes (0–65535). */
        val length: Int,
        /** Whether the stack-overflow assertion was armed at read time. */
        val assertEnabled: Boolean,
    )

    /**
     * One-shot read of the stack-overflow monitoring state.
     *
     * Emits `[0xFE, 0x89]` (register 0x09 with the read bit). Response shape
     * per C++ `overflow_status_received`:
     *   `[0xFE, 0x89, assert_en, length_lo, length_hi]`.
     */
    class ReadStackOverflowState : Readable<OverflowState> {
        override val module: Module = Module.DEBUG
        override val dataRegister: Int = 0x09
        override val readCommand: ByteArray = Packet.read(Module.DEBUG, 0x09)

        override fun parseSample(packet: ByteArray): OverflowState {
            // Decoded inline — the shared PacketParser does not carry a
            // debug-module decoder.
            if (packet.size < 5) {
                throw MetaWearException.OperationFailed(
                    "Packet too short for OverflowState: ${packet.size} bytes",
                )
            }
            return OverflowState(
                length = PacketParser.parseUInt16LE(packet, 3),
                assertEnabled = packet[2].toInt() != 0,
            )
        }
    }

    // ---- Schedule queue usage (firmware rev ≥ 2) ----
    //
    // Register 0x0A in C++ `debug.cpp` (`DebugRegister::SCHEDULE_QUEUE`).
    // Response is a raw byte-array telemetry snapshot; the C++ side routes it
    // through `DataInterpreter::BYTE_ARRAY` — no structured decoding.

    /**
     * One-shot read of the scheduler queue usage (debug/telemetry).
     *
     * Emits `[0xFE, 0x8A]`. Response payload is returned as unsigned bytes
     * (0..255) with the module/register header stripped. Python reference
     * vector (13 payload bytes):
     * ```
     * [0x03, 0x02, 0x01, 0x00, 0x10, 0x01, 0x01, 0x00, 0x00, 0x00, 0x1B, 0x00, 0x1E]
     * ```
     */
    class ReadScheduleQueueUsage : Readable<List<Int>> {
        override val module: Module = Module.DEBUG
        override val dataRegister: Int = 0x0A
        override val readCommand: ByteArray = Packet.read(Module.DEBUG, 0x0A)

        override fun parseSample(packet: ByteArray): List<Int> {
            // Decoded inline — just strip the 2-byte header and widen to
            // unsigned values.
            if (packet.size < 2) {
                throw MetaWearException.OperationFailed(
                    "Packet too short for schedule queue usage: ${packet.size} bytes",
                )
            }
            return packet.drop(2).map { it.toInt() and 0xFF }
        }
    }

    // ---- Spoof button event ----
    //
    // Register 0x03 in C++ `debug.cpp` (`DebugRegister::NOTIFICATION_SPOOF`).
    // C++ `mbl_mw_debug_spoof_button_event(value)` emits a hard-coded
    // notification-spoof payload pointing at the switch module:
    //   [0xFE, 0x03, 0x01, 0x01, 0x00, value]
    //               └──┬──┘  └─┬─┘  └──┬──┘
    //             switch mod, reg 1, data_id 0
    //
    // i.e. "pretend the switch module (0x01) sent a register-1 notification
    // with state byte `value`" — useful for driving event/macro pipelines on
    // the host without physically pressing the button.

    /**
     * Spoof a mechanical-button (switch module, reg 0x01) notification with
     * the given state byte. Firmware reacts as if the push-button fired.
     *
     * Python reference vector (`test_debug.py::test_switch_spoof`, value=0x07):
     * `[0xFE, 0x03, 0x01, 0x01, 0x00, 0x07]`.
     */
    class SpoofButtonEvent(val value: Int) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.DEBUG, 0x03, 0x01, 0x01, 0x00, value and 0xFF)
    }
}
