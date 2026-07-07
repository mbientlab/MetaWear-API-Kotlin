package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser
import com.mbientlab.metawear.protocol.Pollable
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

// Small logging/settings readables that don't
// warrant their own file. Each implements `Pollable` (hence `Readable`) so it
// composes with the generic `device.read(...)` helper and `device.poll(...)`:
//
// ```kotlin
// val entries = device.read(LogLength())      // Timestamped<Long>
// val reset   = device.read(LastResetTime())  // Timestamped<Reading> — { epoch, resetUID }
// val mac     = device.read(MacAddress())     // Timestamped<String>
// ```

/**
 * One-shot read of the on-device log entry count.
 *
 * Mirrors `mbl_mw_logging_get_length` in the C++ SDK. The firmware responds
 * with the number of log entries currently stored on-device; each entry is
 * 4 bytes of parent-signal payload. Log downloads consume this value
 * internally, but exposing it as a readable lets callers poll capacity ahead
 * of a download.
 *
 * Request:  `[0x0B, 0x85]` (register 0x05 | READ)
 * Response: `[0x0B, 0x85, n0, n1, n2, n3]` — UInt32 LE entry count at offset 2.
 */
class LogLength : Pollable<Long> {

    override val module: Module = Module.LOGGING
    override val dataRegister: Int = 0x05

    /** `[0x0B, 0x85]` — LOG_LENGTH with the read bit set. */
    override val readCommand: ByteArray = Packet.read(Module.LOGGING, 0x05)

    override fun parseSample(packet: ByteArray): Long {
        if (packet.size < 6) {
            throw MetaWearException.OperationFailed("Log length packet too short: ${packet.size} bytes")
        }
        return PacketParser.parseUInt32LE(packet, 2)
    }
}

/**
 * One-shot read of the board's "last reset" wall-clock time.
 *
 * Mirrors the firmware's LOGGING_TIME register (0x04). The board responds with
 * its current tick counter plus a reset-UID byte; dividing the tick by the
 * board's clock (≈ 1.4648 ms/tick) gives milliseconds since the device's last
 * reset. Converting that to a wall-clock [Instant] yields the moment the board
 * was last powered on / reset.
 *
 * [Reading.epoch] is computed as `now − (tick × msPerTick)`. Accuracy degrades
 * with BLE round-trip jitter, so treat the value as approximate (±50 ms
 * typical). [Reading.resetUID] is the firmware's 3-bit per-boot counter —
 * *intended* to increment on every reset, but in the field neither field is
 * reliable on its own:
 *  - Some firmware revisions pause/persist the tick counter across
 *    `[0xFE, 0x05]`, so `epoch` looks ~unchanged across a real reboot.
 *  - Some other firmware revisions don't advance `resetUID` even after a
 *    confirmed reboot.
 * The two quirks appear to be mutually exclusive — if you need a robust "did
 * the board reboot?" signal, require **either** `resetUID` to change mod 8
 * **or** `epoch` to advance by more than ~1 s.
 *
 * Request:  `[0x0B, 0x84]`
 * Response: `[0x0B, 0x84, t0, t1, t2, t3, reset_uid]` — UInt32 LE tick at offset 2.
 */
class LastResetTime : Pollable<LastResetTime.Reading> {

    /**
     * Parsed LOGGING_TIME response.
     *
     * @property epoch Approximate wall-clock moment the board last booted,
     *   derived from the firmware tick counter and the local clock.
     * @property resetUID Firmware reset counter, already masked to its valid
     *   3-bit range (0..7) by [parseSample].
     */
    data class Reading(val epoch: Instant, val resetUID: Int)

    companion object {
        /**
         * Mask matching the C++ SDK's `RESET_UID_MASK` — the firmware encodes
         * `reset_uid` in the low 3 bits of its byte.
         */
        const val RESET_UID_MASK: Int = 0x07
    }

    override val module: Module = Module.LOGGING
    override val dataRegister: Int = 0x04

    /** `[0x0B, 0x84]` — LOGGING_TIME with the read bit set. */
    override val readCommand: ByteArray = Packet.read(Module.LOGGING, 0x04)

    override fun parseSample(packet: ByteArray): Reading {
        // Need [0x0B, 0x84, t0, t1, t2, t3, reset_uid] — exactly 7 bytes.
        // Older firmware was thought to omit the trailing byte, but every
        // build the SDK targets (1.5.0+) returns the full 7-byte payload.
        if (packet.size < 7) {
            throw MetaWearException.OperationFailed("Log time packet too short: ${packet.size} bytes")
        }
        val tick = PacketParser.parseUInt32LE(packet, 2)
        val msElapsed = tick.toDouble() * PacketParser.MS_PER_TICK
        val epoch = Clock.System.now() - msElapsed.milliseconds
        return Reading(epoch = epoch, resetUID = packet[6].toInt() and RESET_UID_MASK)
    }
}

/**
 * One-shot read of the board's MAC address.
 *
 * An alias for the canonical [Settings.ReadMacAddress] readable (settings
 * register 0x0B), kept so existing `device.read(MacAddress())` callers keep
 * compiling. Polling an identity read is a harmless no-op way to verify the
 * link is alive, so the canonical type is [Pollable].
 *
 * Request:  `[0x11, 0x8B]`
 * Response: `[0x11, 0x8B, mac[6 LE]]` (or 7 payload bytes with a leading
 * address-type byte on newer firmware) → canonical `"AA:BB:CC:DD:EE:FF"`.
 */
typealias MacAddress = Settings.ReadMacAddress
