package com.mbientlab.metawear.model

/**
 * Errors raised by the MetaWear SDK. Port of `MWError` (Swift), expressed as a
 * sealed exception hierarchy so call sites can `catch` by type and the SDK
 * throws a single family rather than mixing platform error types.
 *
 * The message strings match the Swift `errorDescription` values verbatim so
 * any tests that assert on the user-visible message keep parity.
 */
sealed class MetaWearException(message: String) : Exception(message) {

    /** Bluetooth is unsupported on this platform (no BLE radio / simulator). */
    object BluetoothUnsupported : MetaWearException("Bluetooth unsupported on this platform")

    /** Bluetooth permission has not been granted (or was revoked). */
    object BluetoothUnauthorized : MetaWearException("Bluetooth unauthorized in this App")

    /** Bluetooth is supported and authorized but the radio is currently off. */
    object BluetoothPoweredOff : MetaWearException("Bluetooth powered off")

    /** The operation reached the SDK / firmware but did not succeed. */
    class OperationFailed(val detail: String) : MetaWearException("Operation failed: $detail")

    /** The operation is illegal in the device's current state-machine position. */
    class InvalidState(val state: String) : MetaWearException("Invalid state: $state")

    /** The operation did not receive a response within the read timeout. */
    object Timeout : MetaWearException("Operation timed out")
}
