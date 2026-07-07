package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.model.BatteryState
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.CommandSequence
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser
import com.mbientlab.metawear.protocol.Pollable

// Settings module (0x11).

/**
 * Commands for the MetaWear settings module.
 * Controls device name, advertising, TX power, and connection parameters.
 */
object Settings {

    // ---- Device name ----

    /**
     * Maximum length of a BLE advertising name, in ASCII bytes.
     * Matches the limit the C++ reference SDK enforces at the validator.
     */
    const val MAX_DEVICE_NAME_LENGTH = 26

    /**
     * Characters allowed in a MetaWear BLE advertising name: ASCII letters,
     * digits, underscore, hyphen, and space. Matches the C++ reference SDK.
     */
    const val VALID_DEVICE_NAME_CHARACTERS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_- "

    /**
     * `true` when [proposed] is a valid BLE advertising name:
     * - non-empty
     * - ≤ [MAX_DEVICE_NAME_LENGTH] ASCII bytes
     * - only contains characters in [VALID_DEVICE_NAME_CHARACTERS]
     */
    fun isNameValid(proposed: String): Boolean {
        if (proposed.isEmpty()) return false
        if (proposed.any { it !in VALID_DEVICE_NAME_CHARACTERS }) return false
        return proposed.toByteArray(Charsets.US_ASCII).size <= MAX_DEVICE_NAME_LENGTH
    }

    /**
     * Set the BLE advertising name (max 26 ASCII bytes).
     * The new name takes effect after the next advertisement cycle.
     *
     * Use [validating] to reject invalid names up front; the plain constructor
     * truncates to [MAX_DEVICE_NAME_LENGTH] bytes and performs no character
     * filtering (mirrors the C++ SDK's low-level behaviour).
     */
    class SetDeviceName(val name: String) : Command {
        override val commandData: ByteArray
            get() {
                val utf8 = name.toByteArray(Charsets.UTF_8)
                val nameBytes = if (utf8.size > MAX_DEVICE_NAME_LENGTH) {
                    utf8.copyOf(MAX_DEVICE_NAME_LENGTH)
                } else {
                    utf8
                }
                return Packet.command(Module.SETTINGS, 0x01, nameBytes)
            }

        companion object {
            /**
             * Validating factory. Throws [MetaWearException.OperationFailed]
             * when [name] fails [Settings.isNameValid].
             */
            fun validating(name: String): SetDeviceName {
                if (!isNameValid(name)) {
                    throw MetaWearException.OperationFailed("Invalid device name: \"$name\"")
                }
                return SetDeviceName(name)
            }
        }
    }

    // ---- TX power ----

    /**
     * BLE transmit power level. [raw] is the radio output in dBm (signed) —
     * passed verbatim to the firmware.
     */
    enum class TxPower(val raw: Int) {
        /** +4 dBm — maximum strength, shortest battery life. */
        PLUS_4(4),
        /** 0 dBm — radio default. */
        ZERO(0),
        MINUS_4(-4),
        MINUS_8(-8),
        MINUS_12(-12),
        MINUS_16(-16),
        /** -20 dBm — long-range mode at the cost of throughput. */
        MINUS_20(-20),
        /** -40 dBm — proximity-only debug level. */
        MINUS_40(-40),
    }

    /** Set the BLE radio transmit power. Takes effect immediately. */
    class SetTxPower(val power: TxPower) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.SETTINGS, 0x03, power.raw and 0xFF)
    }

    // ---- Advertising interval ----

    /**
     * BLE advertising type. Only honored on whitelist-capable boards
     * (settings revision ≥ 6); older firmware silently ignores the extra byte.
     */
    enum class BleAdType(val raw: Int) {
        /** Connectable, undirected (scannable by anyone). Default. */
        CONNECTABLE_UNDIRECTED(0),
        /** Connectable, directed (only a specific central can connect — requires whitelist entry). */
        CONNECTABLE_DIRECTED(1),
    }

    /**
     * Set the BLE advertising interval.
     *
     * @param intervalMs Advertising interval in milliseconds (20–10240 ms). Encoded as units of 0.625 ms.
     * @param timeoutSec Advertising timeout in seconds (0 = advertise indefinitely).
     * @param adType Optional advertising type byte appended when targeting a settings
     *   revision ≥ 6 board (whitelist-capable). `null` preserves the legacy 4-byte command.
     */
    class SetAdvertisingInterval(
        val intervalMs: Int = 417,
        val timeoutSec: Int = 0,
        val adType: BleAdType? = null,
    ) : Command {
        override val commandData: ByteArray
            get() {
                // Encode interval in units of 0.625 ms.
                val units = (intervalMs / 0.625).toInt()
                var payload = byteArrayOf(
                    (units and 0xFF).toByte(),
                    ((units shr 8) and 0xFF).toByte(),
                    timeoutSec.toByte(),
                )
                adType?.let { payload += it.raw.toByte() }
                return Packet.command(Module.SETTINGS, 0x02, payload)
            }
    }

    /**
     * Force the board to begin BLE advertising immediately, rather than
     * waiting for the next idle window.
     */
    class StartAdvertising : Command {
        override val commandData: ByteArray = Packet.command(Module.SETTINGS, 0x05)
    }

    // ---- Connection parameters ----

    /**
     * Set BLE connection parameters.
     *
     * All interval values are in units of 1.25 ms (per Bluetooth spec).
     * Typical values: min=6 (7.5 ms), max=24 (30 ms), latency=0, timeout=500 (5 s).
     *
     * @param minInterval Minimum connection interval (units of 1.25 ms, range 6–3200).
     * @param maxInterval Maximum connection interval (units of 1.25 ms, range 6–3200).
     * @param latency Slave latency (number of connection events the peripheral may skip).
     * @param timeout Supervision timeout (units of 10 ms, range 10–3200).
     */
    class SetConnectionParameters(
        val minInterval: Int = 6,
        val maxInterval: Int = 24,
        val latency: Int = 0,
        val timeout: Int = 500,
    ) : Command {
        override val commandData: ByteArray
            get() = Packet.command(
                Module.SETTINGS, 0x09,
                le16(minInterval) + le16(maxInterval) + le16(latency) + le16(timeout),
            )

        companion object {
            /** Low-latency profile: 7.5 ms interval, suitable for high-frequency streaming. */
            val lowLatency: SetConnectionParameters
                get() = SetConnectionParameters(minInterval = 6, maxInterval = 6, latency = 0, timeout = 200)

            /** Balanced profile: 30 ms interval, good for most use cases. */
            val balanced: SetConnectionParameters
                get() = SetConnectionParameters(minInterval = 24, maxInterval = 24, latency = 0, timeout = 500)

            /** Power-saving profile: longer interval, less frequent connection events. */
            val powerSaving: SetConnectionParameters
                get() = SetConnectionParameters(minInterval = 80, maxInterval = 100, latency = 4, timeout = 600)
        }
    }

    // ---- Scan response ----
    //
    // Scan response payload per `SCAN_RESPONSE` (register 0x07) and
    // `PARTIAL_SCAN_RESPONSE` (register 0x08) in `settings_register.h`. The
    // full response may exceed a single BLE write; the firmware concatenates
    // it when the first 13 bytes go to register 0x08 and the remainder to
    // register 0x07.
    //
    // Python reference (`test_settings.py::test_set_scan_response`, 21-byte
    // payload):
    // ```
    // [0x11, 0x08, 0x03, 0x03, 0xd8, 0xfe, 0x10, 0x16, 0xd8, 0xfe, 0x00, 0x12, 0x00, 0x6d, 0x62],
    // [0x11, 0x07, 0x69, 0x65, 0x6e, 0x74, 0x6c, 0x61, 0x62, 0x00]
    // ```

    /**
     * Program the BLE scan-response payload that the board returns to
     * scanners. May expand into multiple BLE writes — see [commands].
     */
    class SetScanResponse(val payload: ByteArray) : CommandSequence {
        /**
         * Ordered list of commands to send. Short payloads (≤ 13 bytes) emit a
         * single write to register 0x07; larger payloads are split across
         * register 0x08 (first 13 bytes) and register 0x07 (remainder).
         */
        override val commands: List<ByteArray>
            get() = if (payload.size <= 13) {
                listOf(Packet.command(Module.SETTINGS, 0x07, payload))
            } else {
                listOf(
                    Packet.command(Module.SETTINGS, 0x08, payload.copyOfRange(0, 13)),
                    Packet.command(Module.SETTINGS, 0x07, payload.copyOfRange(13, payload.size)),
                )
            }
    }

    // ---- Battery state (settings revision ≥ 3) ----
    //
    // Register 0x0C (`BATTERY_STATE`) in `settings_register.h`. Wire request:
    // `[0x11, 0x8C]` (register 0x0C | READ 0x80). Response shape:
    // `[0x11, 0x8C, charge, volt_lo, volt_hi]`.
    //
    // The C++ `MblMwDataSignal` constructor enables the SILENT bit (0x40) on
    // readable signals at construction, but the reference C++ SDK's production
    // read path calls `mbl_mw_datasignal_subscribe` *before*
    // `mbl_mw_datasignal_read`, and `subscribe()` on a readable signal clears
    // the silent bit. So the actual wire byte that ships in production is
    // `0x8C`, not `0xCC` — the `[0x11, 0xcc]` in
    // `test_settings.py::test_read_battery_state` only appears because the
    // unit test calls `datasignal_read` on a freshly-constructed signal
    // without going through the subscribe-first wrapper.
    //
    // The MMS firmware we target (settings revision 10, fw 1.6.x) silently
    // ignores `[0x11, 0xCC]` reads: no notification ever comes back, and the
    // host times out. `[0x11, 0x8C]` produces the response immediately.

    /**
     * One-shot read of the battery charge percentage and voltage.
     * Requires settings module revision ≥ 3.
     */
    class ReadBatteryState : Pollable<BatteryState> {
        override val module: Module = Module.SETTINGS
        override val dataRegister: Int = 0x0C
        override val readCommand: ByteArray = Packet.read(Module.SETTINGS, 0x0C)

        override fun parseSample(packet: ByteArray): BatteryState =
            PacketParser.parseBatteryState(packet)
    }

    // ---- MAC address (settings revision ≥ 2) ----
    //
    // Register 0x0B (`MAC`) in `settings_register.h`. The readable fetch
    // pattern produces `[0x11, 0x8B]` (register 0x0B | READ). The firmware
    // response starts `[0x11, 0x8B, data_id, mac5, mac4, mac3, mac2, mac1,
    // mac0]`; the MAC is formatted in reverse-byte colon notation.
    //
    // Python reference (`test_settings.py::test_mac_address`): packet
    // `[0x11, 0x8b, 0x01, 0x07, 0x7b, 0x52, 0x8f, 0xc9, 0xe8]` → `"E8:C9:8F:52:7B:07"`.

    /**
     * One-shot read of the board's BLE MAC address, formatted as a
     * canonical colon-separated string (e.g. `"E8:C9:8F:52:7B:07"`).
     * Requires settings module revision ≥ 2.
     */
    class ReadMacAddress : Pollable<String> {
        override val module: Module = Module.SETTINGS
        override val dataRegister: Int = 0x0B
        override val readCommand: ByteArray = Packet.read(Module.SETTINGS, 0x0B)

        override fun parseSample(packet: ByteArray): String =
            PacketParser.parseMacAddress(packet)
    }

    // ---- Power / charge status (settings revision ≥ 5 with status flags) ----
    //
    // Registers 0x11 (`POWER_STATUS`) and 0x12 (`CHARGE_STATUS`). Both are
    // non-readable notification signals by default; `mark_readable()` is
    // applied in the C++ init only for the one-shot read path, so:
    //   - Streaming notification header: `[0x11, 0x11, value]` / `[0x11, 0x12, value]`
    //   - One-shot read command:          `[0x11, 0x91]`        / `[0x11, 0x92]`
    //
    // Python reference (`test_settings.py::test_read_current_power` /
    // `test_read_current_charge`): read emits `[0x11, 0x91]` and `[0x11, 0x92]`.

    /**
     * One-shot read of the current power-supply status (0 = not powered, 1 = powered).
     * Only valid on boards whose settings module advertises the power-status bit
     * (`moduleInfo.extra[0] and 0x01`, revision ≥ 5).
     */
    class ReadPowerStatus : Pollable<Int> {
        override val module: Module = Module.SETTINGS
        override val dataRegister: Int = 0x11
        override val readCommand: ByteArray = Packet.read(Module.SETTINGS, 0x11)

        override fun parseSample(packet: ByteArray): Int {
            if (packet.size < 3) {
                throw MetaWearException.OperationFailed(
                    "Power status packet too short: ${packet.size} bytes",
                )
            }
            return packet[2].toInt() and 0xFF
        }
    }

    /**
     * One-shot read of the current charging status (0 = not charging, 1 = charging).
     * Only valid on boards whose settings module advertises the charge-status bit
     * (`moduleInfo.extra[0] and 0x02`, revision ≥ 5).
     */
    class ReadChargeStatus : Pollable<Int> {
        override val module: Module = Module.SETTINGS
        override val dataRegister: Int = 0x12
        override val readCommand: ByteArray = Packet.read(Module.SETTINGS, 0x12)

        override fun parseSample(packet: ByteArray): Int {
            if (packet.size < 3) {
                throw MetaWearException.OperationFailed(
                    "Charge status packet too short: ${packet.size} bytes",
                )
            }
            return packet[2].toInt() and 0xFF
        }
    }

    // ---- Whitelist filter (settings revision ≥ 6) ----
    //
    // Register 0x13 (`WHITELIST_FILTER_MODE`). Mirrors C++
    // `mbl_mw_settings_set_whitelist_filter_mode` and `MblMwWhitelistFilter`.
    // Silently ignored by firmware < revision 6.

    /** Behavior of the BLE whitelist filter (`MblMwWhitelistFilter`). */
    enum class WhitelistFilterMode(val raw: Int) {
        /** Accept scan + connection requests from any central (whitelist disabled). Default. */
        ALLOW_FROM_ANY(0),
        /** Scan requests honored from any central; connection requests restricted to whitelist. */
        SCAN_REQUESTS_ONLY(1),
        /** Scan requests restricted to whitelist; connection requests honored from any central. */
        CONNECTION_REQUESTS_ONLY(2),
        /** Both scan and connection requests restricted to whitelist entries. */
        SCAN_AND_CONNECTION_REQUESTS(3),
    }

    /**
     * Configure how the board's whitelist restricts incoming BLE traffic.
     * Requires settings module revision ≥ 6.
     */
    class SetWhitelistFilterMode(val mode: WhitelistFilterMode) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.SETTINGS, 0x13, mode.raw)
    }

    // ---- Whitelist address table (settings revision ≥ 6) ----
    //
    // Register 0x14 (`WHITELIST_ADDRESSES`). Mirrors C++
    // `mbl_mw_settings_add_whitelist_address` and the `MblMwBtleAddress` struct:
    //   { uint8_t address_type; uint8_t address[6]; }   // address is LSB-first
    // Wire format: `[0x11, 0x14, index, address_type, b0, b1, b2, b3, b4, b5]`.

    /**
     * A BLE MAC address in the format accepted by the whitelist register.
     *
     * [bytesLsbFirst] is the raw on-wire order — the canonical `AA:BB:CC:DD:EE:FF`
     * display form is the reverse. Use [parse] to build from display form.
     */
    class BluetoothAddress(
        val type: AddressType,
        /** 6 raw MAC bytes, LSB first (matches `MblMwBtleAddress.address[6]`). */
        val bytesLsbFirst: ByteArray,
    ) {
        /** BLE address type (`MblMwBtleAddress.address_type`). */
        enum class AddressType(val raw: Int) {
            /** Globally unique public address assigned by the manufacturer. */
            PUBLIC(0),
            /** Random/resolvable address (Bluetooth privacy feature). */
            RANDOM(1),
        }

        init {
            if (bytesLsbFirst.size != 6) {
                throw MetaWearException.OperationFailed(
                    "BluetoothAddress requires exactly 6 bytes; got ${bytesLsbFirst.size}",
                )
            }
        }

        /** Canonical colon-separated display form (`"E8:C9:8F:52:7B:07"`). */
        val displayString: String
            get() = bytesLsbFirst.reversed()
                .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

        companion object {
            /**
             * Parse a colon- or hyphen-separated MAC string (canonical display order,
             * e.g. `"E8:C9:8F:52:7B:07"`). Bytes are reversed to match wire (LSB-first) order.
             */
            fun parse(mac: String, type: AddressType = AddressType.PUBLIC): BluetoothAddress {
                val parts = mac.split(':', '-')
                if (parts.size != 6) {
                    throw MetaWearException.OperationFailed("MAC must have 6 octets: \"$mac\"")
                }
                val displayBytes = parts.map { part ->
                    if (part.length != 2) {
                        throw MetaWearException.OperationFailed("Invalid MAC octet \"$part\" in \"$mac\"")
                    }
                    part.toIntOrNull(16)
                        ?: throw MetaWearException.OperationFailed("Invalid MAC octet \"$part\" in \"$mac\"")
                }
                // Display order is byte5:byte4:…:byte0; wire is byte0:byte1:…:byte5.
                return BluetoothAddress(type, ByteArray(6) { displayBytes[5 - it].toByte() })
            }
        }
    }

    /**
     * Add one MAC address to the board's whitelist table.
     *
     * [index] must be in `1…8` and entries must be written in increasing order
     * starting at 1 (firmware constraint — the same as the C++ SDK).
     */
    class AddWhitelistAddress(val index: Int, val address: BluetoothAddress) : Command {
        override val commandData: ByteArray
            get() = Packet.command(
                Module.SETTINGS, 0x14,
                byteArrayOf(index.toByte(), address.type.raw.toByte()) + address.bytesLsbFirst,
            )
    }

    // ---- 3V regulator (MMS only, settings revision ≥ 9) ----
    //
    // Register 0x1C (`THREE_VOLT_POWER`). Mirrors C++
    // `mbl_mw_settings_enable_3V_regulator`. Silently ignored by non-MMS boards.

    /**
     * Enable or disable the 3.3 V regulator on MetaMotion S.
     * No-op on non-MMS boards. Requires settings module revision ≥ 9.
     */
    class SetThreeVoltPower(val enabled: Boolean) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.SETTINGS, 0x1C, if (enabled) 0x01 else 0x00)
    }

    // ---- Force 1M PHY (MMS only, settings revision ≥ 10) ----
    //
    // Register 0x1D (`FORCE_1M_PHY`). Mirrors C++ `mbl_mw_settings_force_1M_phy`.
    // When enabled the board pins its PHY to 1 Mbps — useful for diagnosing
    // 2 M PHY interoperability issues on MetaMotion S. No-op on older firmware.

    /**
     * Force the BLE radio to use the 1 Mbps PHY. Useful when debugging
     * 2 M PHY interoperability on MetaMotion S. Requires settings module
     * revision ≥ 10; no-op on older firmware.
     */
    class SetForce1MPhy(val enabled: Boolean) : Command {
        override val commandData: ByteArray
            get() = Packet.command(Module.SETTINGS, 0x1D, if (enabled) 0x01 else 0x00)
    }
}

/** 16-bit little-endian encoding for settings command fields. */
private fun le16(value: Int): ByteArray =
    byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

// ---- MetaWearDevice settings convenience ----

/**
 * Write a scan-response payload to the board. Payloads ≤ 13 bytes emit a
 * single write to `SCAN_RESPONSE` (0x07); longer payloads are split across
 * `PARTIAL_SCAN_RESPONSE` (0x08) + `SCAN_RESPONSE` (0x07).
 */
suspend fun MetaWearDevice.setScanResponse(payload: ByteArray) {
    for (cmd in Settings.SetScanResponse(payload).commands) {
        writeRaw(cmd)
    }
}
