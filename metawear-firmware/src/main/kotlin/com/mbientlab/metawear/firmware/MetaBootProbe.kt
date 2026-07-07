package com.mbientlab.metawear.firmware

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

// Reads the bootloader version from a MetaWear that has already rebooted
// into MetaBoot (bootloader) mode.
//
// In MetaBoot mode the standard Device Information Service's Firmware
// Revision characteristic (0x2A26) reports the BOOTLOADER version rather than
// the application firmware version — and MetaBoot is the only place the
// bootloader version is readable at all; application-mode firmware doesn't
// expose it. The bootloader interlock uses this to decide whether a
// bootloader-flavor flash must precede the application flash.
//
// This is a deliberately tiny, single-shot BluetoothGatt client: the core
// SDK's transport can't be reused because its connect sequence requires the
// MetaWear command service, which MetaBoot doesn't advertise. The probe
// bridges BluetoothGattCallback into suspendCancellableCoroutine with the
// one-shot guarantee enforced by an AtomicBoolean. Like DfuSession, it has
// no unit-test coverage — it can only be exercised on real hardware.

internal object MetaBootProbe {

    private val DEVICE_INFORMATION_SERVICE: UUID =
        UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
    private val FIRMWARE_REVISION: UUID =
        UUID.fromString("00002A26-0000-1000-8000-00805F9B34FB")

    /**
     * Connect to the MetaBoot-mode peripheral at [address], read the Firmware
     * Revision string (= bootloader version), and disconnect.
     *
     * The board must already be in MetaBoot mode and advertising — call only
     * after the jump-to-bootloader handoff has completed. Requires the
     * `BLUETOOTH_CONNECT` runtime permission (API 31+), like the rest of the
     * SDK.
     */
    @SuppressLint("MissingPermission")
    suspend fun readBootloaderVersion(
        context: Context,
        address: String,
        timeout: Duration = 10.seconds,
    ): String {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
        if (adapter == null || !adapter.isEnabled) {
            throw FirmwareException.OperationFailed(
                "Bluetooth unavailable while reading the bootloader version.",
            )
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            throw FirmwareException.OperationFailed(
                "MetaBoot peripheral not found for bootloader version read.",
            )
        }
        return withTimeoutOrNull(timeout) { readOnce(context, device) }
            ?: throw FirmwareException.OperationFailed(
                "Timed out reading the bootloader version from the device.",
            )
    }

    @SuppressLint("MissingPermission")
    private suspend fun readOnce(context: Context, device: BluetoothDevice): String =
        suspendCancellableCoroutine { continuation ->
            // One-shot completion: resume the continuation exactly once and
            // tear down the GATT client, whichever path (value, error,
            // timeout, cancellation) gets here first.
            val finished = AtomicBoolean(false)
            val gattRef = AtomicReference<BluetoothGatt?>(null)

            fun finish(result: Result<String>) {
                if (!finished.compareAndSet(false, true)) return
                gattRef.getAndSet(null)?.let { gatt ->
                    runCatching { gatt.disconnect() }
                    runCatching { gatt.close() }
                }
                result.fold(
                    onSuccess = { if (continuation.isActive) continuation.resume(it) },
                    onFailure = { if (continuation.isActive) continuation.resumeWithException(it) },
                )
            }

            fun fail(message: String) =
                finish(Result.failure(FirmwareException.OperationFailed(message)))

            val callback = object : BluetoothGattCallback() {

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    when {
                        finished.get() -> Unit
                        newState == BluetoothProfile.STATE_CONNECTED &&
                            status == BluetoothGatt.GATT_SUCCESS -> {
                            if (!gatt.discoverServices()) {
                                fail("Couldn't connect to MetaBoot: service discovery would not start.")
                            }
                        }
                        newState == BluetoothProfile.STATE_DISCONNECTED -> {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                fail("Couldn't connect to MetaBoot: GATT status $status.")
                            } else {
                                fail("MetaBoot disconnected during bootloader version read.")
                            }
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (finished.get()) return
                    val service = gatt.getService(DEVICE_INFORMATION_SERVICE)
                    if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                        fail("MetaBoot has no Device Information service.")
                        return
                    }
                    val characteristic = service.getCharacteristic(FIRMWARE_REVISION)
                    if (characteristic == null || !gatt.readCharacteristic(characteristic)) {
                        fail("MetaBoot exposes no Firmware Revision characteristic.")
                    }
                }

                // API 33+ delivery path.
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) = deliver(value, status)

                // Pre-33 delivery path.
                @Deprecated("Deprecated in Java")
                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) = deliver(characteristic.value, status)

                private fun deliver(value: ByteArray?, status: Int) {
                    if (finished.get()) return
                    val version = if (status == BluetoothGatt.GATT_SUCCESS) {
                        value?.toString(Charsets.UTF_8)?.trim()
                    } else {
                        null
                    }
                    if (version.isNullOrEmpty()) {
                        fail("Unreadable bootloader version value.")
                    } else {
                        finish(Result.success(version))
                    }
                }
            }

            // The stack already knows this address from the pre-handoff
            // connection; a direct (non-autoConnect) GATT connect works
            // without a fresh scan.
            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                fail("Couldn't connect to MetaBoot: GATT client unavailable.")
            } else {
                gattRef.set(gatt)
                if (finished.get()) {
                    // finish() raced the assignment; it already resumed, so
                    // just tear down the client it couldn't see.
                    gattRef.getAndSet(null)?.let { raced ->
                        runCatching { raced.disconnect() }
                        runCatching { raced.close() }
                    }
                }
            }

            continuation.invokeOnCancellation {
                // Mark finished so late GATT callbacks bail; the continuation
                // needs no resume — cancellation already completed it.
                if (finished.compareAndSet(false, true)) {
                    gattRef.getAndSet(null)?.let { gatt ->
                        runCatching { gatt.disconnect() }
                        runCatching { gatt.close() }
                    }
                }
            }
        }
}
