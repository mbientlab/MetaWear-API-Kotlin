package com.mbientlab.metawear.firmware

import java.net.URI

// Port of MWFirmwareError.swift — error taxonomy for the firmware-update
// pipeline. Covers three layers:
//   • catalog/network    — HTTP fetch failures, malformed JSON, no matching build
//   • orchestration      — bootloader handoff, rediscovery, state-machine misuse
//   • DFU transfer       — errors propagated from the Nordic DFU library
//
// Swift's `enum MWFirmwareError: Error, Equatable` + `LocalizedError` maps to
// a sealed exception hierarchy: data classes/objects give the same
// payload-based equality, and the Swift `errorDescription` strings become the
// exception `message` verbatim.

sealed class FirmwareException(message: String) : Exception(message) {

    // ---- Catalog / network ----

    /** Catalog server returned a non-2xx HTTP status. */
    data class BadServerResponse(val status: Int) :
        FirmwareException("Firmware catalog server returned HTTP $status.")

    /**
     * JSON returned by the catalog server didn't deserialize into the expected
     * `[hwRev: [model: [flavor: [version: {…}]]]]` shape.
     */
    data class InvalidServerResponse(val details: String) :
        FirmwareException("Invalid firmware catalog response: $details")

    /**
     * No firmware on the catalog matches this device + this SDK version. The
     * associated message identifies the (hardwareRev, modelNumber, buildFlavor)
     * tuple that came back empty.
     */
    data class NoAvailableFirmware(val details: String) :
        FirmwareException("No firmware available: $details")

    /** Could not write the downloaded firmware to a temporary file. */
    data class CannotSaveFile(val details: String) :
        FirmwareException("Cannot save firmware file: $details")

    /**
     * The downloaded file isn't a recognised firmware container — extension
     * must be `.zip` (preferred — Nordic DFU distribution package) or
     * `.bin` / `.hex` (raw application binary, no init packet).
     */
    data class InvalidFirmwareFile(val url: URI) :
        FirmwareException("Unrecognised firmware file '${url.lastPathComponent}' — expected .zip, .bin, or .hex.")

    // ---- Bootloader interlock ----

    /**
     * The new firmware requires a specific bootloader version that isn't
     * available on the catalog, so the upgrade can't proceed.
     */
    data class BootloaderUpgradeUnavailable(val requiredVersion: String, val hardwareRev: String) :
        FirmwareException(
            "Firmware update needs bootloader $requiredVersion for hardware $hardwareRev, but it isn't on the catalog.",
        )

    /**
     * `updateFirmware(…)` was called but the device isn't in a state where the
     * bootloader handoff (`[0xFE, 0x02]`) is safe — typically because the
     * device is mid-stream / mid-log / mid-download.
     */
    data object DeviceNotIdle :
        FirmwareException("Cannot update firmware while the device is streaming, logging, or downloading.") {
        // data object + Exception: keep default serialization happy.
        private fun readResolve(): Any = DeviceNotIdle
    }

    // ---- DFU ----

    /**
     * The Nordic DFU library reported an error during transfer. [details] is
     * `"<code>: <library message>"`, which distinguishes transport drops, CRC
     * mismatches, signature failures, and "device disconnected unexpectedly"
     * cases. Propagated verbatim because the library reports errors as
     * (int code, string message) broadcasts.
     */
    data class DfuFailed(val details: String) :
        FirmwareException("DFU failed: $details")

    /** DFU was aborted by the caller (cancelling collection of the returned Flow). */
    data object Aborted : FirmwareException("Firmware update was aborted.") {
        private fun readResolve(): Any = Aborted
    }

    // ---- Catch-all ----

    /**
     * Anything else (transport errors during handoff, scanner timeouts while
     * waiting for MetaBoot rediscovery, etc.).
     */
    data class OperationFailed(val details: String) : FirmwareException(details)
}
