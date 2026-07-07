package com.mbientlab.metawear.firmware

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import no.nordicsemi.android.dfu.DfuBaseService
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter
import no.nordicsemi.android.dfu.DfuServiceInitiator
import no.nordicsemi.android.dfu.DfuServiceListenerHelper

// Adapts the Nordic DFU library's broadcast callbacks into a cold
// Flow<DFUProgress>.
//
// The Nordic library runs the transfer inside a DfuBaseService and reports
// progress through LocalBroadcastManager broadcasts, surfaced here via
// DfuProgressListenerAdapter. callbackFlow supplies the concurrency
// guarantees: trySend/close are thread-safe, and cancellation of the
// collecting coroutine runs awaitClose, which maps to Nordic's abort() — so
// cancelling the collector cancels the update.
//
// No unit-test coverage — this file is purely a wrapper around the Nordic
// library, which itself can only be exercised on real hardware.

/**
 * Owns one Nordic DFU run from start to finish.
 *
 * Lifecycle: create → call [run] → collect the returned Flow until it
 * completes or throws → discard. Reusing the same instance for a second run
 * is undefined; create a fresh [DfuSession] each time.
 *
 * @param context Any context; held only for service start + broadcast
 *   registration.
 * @param serviceClass The manifest-registered [DfuBaseService] subclass that
 *   hosts the transfer. Defaults to [MetaWearDfuService], which this library
 *   registers for you.
 */
internal class DfuSession(
    private val context: Context,
    private val serviceClass: Class<out DfuBaseService> = MetaWearDfuService::class.java,
) {

    /**
     * Kick off the DFU and stream [DFUProgress] events.
     *
     * @param firmware Local firmware artifact — `.zip` (Nordic DFU
     *   distribution package, preferred) or `.bin`/`.hex` (raw application
     *   image, no init packet), already staged by the orchestrator.
     * @param targetAddress The bootloader-mode peripheral's MAC address. The
     *   MetaWear's address persists across the bootloader handoff, so the
     *   same address the device used in application mode also addresses it
     *   post-handoff (MetaBoot does not use the address+1 convention).
     * @return A cold Flow that emits [DFUProgress] until completion, then
     *   completes; or throws on transport / validation errors. Cancelling the
     *   collector aborts the in-flight DFU.
     */
    fun run(firmware: File, targetAddress: String): Flow<DFUProgress> = callbackFlow {
        // Parts bookkeeping: non-upload phases report the part counters most
        // recently seen from the library.
        var currentPart = 1
        var totalParts = 1
        var finished = false

        val listener = object : DfuProgressListenerAdapter() {

            private fun send(state: DFUProgress.State, percentComplete: Double = 0.0) {
                trySendBlocking(
                    DFUProgress(
                        state = state,
                        percentComplete = percentComplete,
                        currentPart = currentPart,
                        totalParts = totalParts,
                    ),
                )
            }

            override fun onDeviceConnecting(deviceAddress: String) = send(DFUProgress.State.CONNECTING)

            override fun onDfuProcessStarting(deviceAddress: String) = send(DFUProgress.State.STARTING)

            // The buttonless DFU service is being asked to reset into
            // bootloader. We've already done the MetaWear-specific handoff
            // before reaching this callback; map to BOOTLOADER_HANDOFF for
            // visibility.
            override fun onEnablingDfuMode(deviceAddress: String) = send(DFUProgress.State.BOOTLOADER_HANDOFF)

            // Real progress comes through onProgressChanged — this fires once
            // as the upload phase begins. Emit a 0% snapshot so observers see
            // the state transition immediately.
            override fun onDfuProcessStarted(deviceAddress: String) = send(DFUProgress.State.UPLOADING)

            override fun onProgressChanged(
                deviceAddress: String,
                percent: Int,
                speed: Float,
                avgSpeed: Float,
                currentPartNumber: Int,
                partsTotal: Int,
            ) {
                currentPart = currentPartNumber
                totalParts = partsTotal
                trySendBlocking(
                    DFUProgress(
                        state = DFUProgress.State.UPLOADING,
                        percentComplete = percent.toDouble(),
                        currentPart = currentPartNumber,
                        totalParts = partsTotal,
                        bytesPerSecond = speed.toDouble(),
                    ),
                )
            }

            override fun onFirmwareValidating(deviceAddress: String) =
                send(DFUProgress.State.VALIDATING, percentComplete = 100.0)

            override fun onDeviceDisconnecting(deviceAddress: String?) =
                send(DFUProgress.State.DISCONNECTING, percentComplete = 100.0)

            override fun onDfuCompleted(deviceAddress: String) {
                send(DFUProgress.State.COMPLETED, percentComplete = 100.0)
                finished = true
                close()
            }

            override fun onDfuAborted(deviceAddress: String) {
                send(DFUProgress.State.ABORTED)
                finished = true
                close(FirmwareException.Aborted)
            }

            override fun onError(deviceAddress: String, error: Int, errorType: Int, message: String?) {
                finished = true
                close(FirmwareException.DfuFailed("$error: ${message ?: "unknown DFU error"}"))
            }
        }

        DfuServiceListenerHelper.registerProgressListener(context, listener, targetAddress)

        val initiator = DfuServiceInitiator(targetAddress)
            // Background service + no notification: the SDK surfaces progress
            // through this Flow instead. Callers wanting Nordic's foreground
            // notification should drive DfuServiceInitiator themselves.
            .setForeground(false)
            .setDisableNotification(true)
            // The MetaWear bootloader exposes the standard (legacy) Nordic DFU
            // service. forceDfu skips the library's jump-to-bootloader check
            // for the app-mode DFU service that MetaWear doesn't expose — the
            // MetaWear-specific handoff ([0xFE, 0x02]) already happened.
            .setForceDfu(true)
            .setKeepBond(false)

        when (firmware.extension.lowercase()) {
            "zip" -> initiator.setZip(firmware.absolutePath)
            "bin", "hex" -> initiator.setBinOrHex(DfuBaseService.TYPE_APPLICATION, firmware.absolutePath)
            else -> {
                DfuServiceListenerHelper.unregisterProgressListener(context, listener)
                throw FirmwareException.InvalidFirmwareFile(firmware.toURI())
            }
        }

        val controller = initiator.start(context, serviceClass)

        awaitClose {
            DfuServiceListenerHelper.unregisterProgressListener(context, listener)
            // Consumer-side termination (collector cancelled) maps to Nordic's
            // abort(). Terminal states already tore the service down; aborting
            // then would be a no-op broadcast, but skip it for cleanliness.
            if (!finished && !controller.isAborted) {
                runCatching { controller.abort() }
                    .onFailure { Log.w(TAG, "DFU abort on cancellation failed", it) }
            }
        }
    }

    private companion object {
        const val TAG = "MetaWearFirmware"
    }
}
