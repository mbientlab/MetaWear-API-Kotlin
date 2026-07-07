package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Streamable

// Port of MWSwitch.swift — the switch module (0x01), i.e. the board's
// mechanical push button.
//
// Mirrors C++ `switch.{h,cpp}`. The switch module exposes exactly one thing: a
// streaming signal for the physical push button. The firmware emits a 1-byte
// state (0 = released, 1 = pressed) on every transition.
//
// Registers:
//   STATE = 0x01   subscribe (payload 0x01) / unsubscribe (payload 0x00)
//                  state-change notifications arrive on the same register

/**
 * Streams press / release events from the MetaWear's physical button.
 * Port of `MWSwitch` (Swift).
 *
 * ```kotlin
 * val stream = device.startStream(Switch())
 * stream.collect { event -> println(if (event.value) "pressed" else "released") }
 * ```
 *
 * The legacy Combine SDK called this `MWMechanicalButton`; this SDK
 * standardises on `Switch` to match the C++ module name (`MBL_MW_MODULE_SWITCH`).
 */
class Switch : Streamable<Boolean> {

    override val module: Module = Module.SWITCH

    /** Register 0x01 (STATE) delivers state-change notifications: `[0x01, 0x01, state]`. */
    override val dataRegister: Int = 0x01
    // `packedDataRegister` defaults to `null` via the Sensor interface — the
    // switch has no packed-data variant.

    /** No configuration needed — the switch fires on hardware events only. */
    override val configureCommands: List<ByteArray> = emptyList()

    // The switch's "subscribe" and "enable" are the same write
    // (`[0x01, 0x01, 0x01]`) — `mbl_mw_datasignal_subscribe` is the only
    // command the C++/Combine SDKs ever send for this module. The generic
    // `startStream` already issues `[module, dataRegister, 0x01]` before
    // walking the enable/start commands, and `stopStreaming` issues the
    // matching `[module, dataRegister, 0x00]` on the way out, so all four
    // module-level hooks below are deliberate no-ops. Re-issuing the same
    // write would have been at best redundant and at worst toggled
    // notifications back off on certain firmwares — which presented as a
    // silent stream during hardware testing.
    override val enableCommand: ByteArray = ByteArray(0)
    override val startCommand: ByteArray = ByteArray(0)
    override val stopCommand: ByteArray = ByteArray(0)
    override val disableCommand: ByteArray = ByteArray(0)

    /**
     * Parse a switch-state packet `[0x01, 0x01, state]`.
     * @return `true` if the button is currently pressed, `false` on release.
     */
    override fun parseSample(packet: ByteArray): Boolean {
        if (packet.size < 3) {
            throw MetaWearException.OperationFailed("Switch packet too short: ${packet.size} bytes")
        }
        return packet[2].toInt() == 0x01
    }

    // `parsePackedSamples` falls through to the Streamable default (returns
    // an empty list) — the switch has no packed-data register.
}
