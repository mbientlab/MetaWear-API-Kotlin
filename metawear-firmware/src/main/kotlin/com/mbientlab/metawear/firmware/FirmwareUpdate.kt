package com.mbientlab.metawear.firmware

import android.content.Context
import android.util.Log
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.model.DeviceInformation
import com.mbientlab.metawear.sensor.Debug
import java.io.File
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import no.nordicsemi.android.dfu.DfuBaseService

// Port of MetaWearDevice+DFU.swift — public DFU API extensions on
// `MetaWearDevice`. Three entry points:
//
//   • checkForFirmwareUpdate(...)  — does the catalog have something newer
//                                    than what's on the board?
//   • updateFirmware(zipUrl:)      — flash an explicit firmware file.
//   • updateFirmwareToLatest(...)  — fetch latest from MbientLab CDN and
//                                    flash it.
//
// All update entry points return a cold Flow<DFUProgress> so callers can
// drive a progress bar from the same collection that catches failure.
// Cancelling the collecting coroutine aborts the in-flight DFU — Nordic's
// library handles that cleanly. (Swift returns AsyncThrowingStream and
// cancels an inner Task on termination; a cold Flow gives the identical
// contract for free.)
//
// Orchestration shape:
//   1. Verify the device is Idle (no in-flight stream/log/download).
//   2. Emit BOOTLOADER_HANDOFF, send [0xFE, 0x02], disconnect cleanly.
//   3. Validate the firmware artifact (zip / bin / hex).
//   4. Hand off to DfuSession, forwarding every DFUProgress event up.
//   5. When DfuSession finishes, the device is back in app mode but our
//      in-memory device-info / module map is stale. The caller is responsible
//      for connect()-ing again.
//
// Android adaptation: the iOS wrapper constructs (and thereby fully parses)
// a DFUFirmware object before tearing down BLE; the Android library only
// parses the archive inside the DFU service, after the handoff. The
// pre-flight validation here is therefore extension-based (.zip/.bin/.hex) —
// a corrupt zip still surfaces as DfuFailed from the session, just later.

private const val TAG = "MetaWearFirmware"

// ---- Update check ----

/**
 * Check whether the MbientLab catalog has a firmware build newer than what's
 * currently on the device. Returns `null` if the device is already up to
 * date. The device must be connected (so `deviceInfo` is populated) before
 * this call.
 */
suspend fun MetaWearDevice.checkForFirmwareUpdate(
    server: FirmwareServer = FirmwareServer(),
): FirmwareBuild? {
    val info = deviceInfo ?: throw FirmwareException.OperationFailed(
        "Device info not populated — call connect() before checkForFirmwareUpdate().",
    )
    return server.updateAvailable(
        currentRev = info.firmwareRevision,
        hardwareRev = info.hardwareRevision,
        modelNumber = info.modelNumber,
    )
}

// ---- Update from explicit zip URL (scope `a`) ----

/**
 * Flash the firmware at [zipUrl] onto the connected device.
 *
 * The URL can be a remote `https://` URL (the orchestrator downloads it via
 * the supplied [fetcher]), or a `file://` URI pointing at a firmware file
 * already on disk.
 *
 * Accepted file extensions:
 *  • `.zip` — Nordic DFU distribution package (preferred). Contains the
 *             firmware binary plus a signed init packet.
 *  • `.bin` / `.hex` — raw application image, no init packet. Older MetaWear
 *             firmware shipped this way.
 *
 * The device transitions to [DeviceState.Disconnected] mid-flight (board
 * reboots into bootloader). When the returned Flow completes normally, the
 * board is back in app mode but the local `state` / `deviceInfo` / `modules`
 * cache is stale — call `connect()` again to refresh.
 *
 * @param context Used to start the DFU service ([MetaWearDfuService]) and
 *   register for its progress broadcasts.
 */
fun MetaWearDevice.updateFirmware(
    context: Context,
    zipUrl: URI,
    fetcher: FirmwareFetcher = HttpUrlConnectionFetcher(),
): Flow<DFUProgress> = flow {
    runFirmwareUpdate(context, zipUrl, fetcher, this)
}

// ---- Update to catalog-latest (scope `b`) ----

/**
 * Fetch the latest firmware from MbientLab's release catalog and flash it.
 * If the device is already on the latest, the Flow completes after the
 * initial [DFUProgress.State.FETCHING_CATALOG] event — callers can
 * distinguish "nothing to do" from "update completed" by observing whether
 * [DFUProgress.State.COMPLETED] was emitted.
 *
 * Adds the bootloader interlock the explicit-URL path can't have: after the
 * MetaBoot handoff, the on-board bootloader version is read from the
 * bootloader's Device Information service and compared against the target
 * build's `required-bootloader`. An outdated bootloader turns the update into
 * a multi-stage flash (catalog "bootloader" flavor first, then the
 * application), surfaced through `currentPart` / `totalParts`.
 */
fun MetaWearDevice.updateFirmwareToLatest(
    context: Context,
    server: FirmwareServer = FirmwareServer(),
): Flow<DFUProgress> = flow {
    runUpdateToLatest(context, server, this)
}

// ---- Private orchestration ----

/**
 * Driver for `updateFirmware(zipUrl:)`. Suspends inside the collector's
 * coroutine, so every step stays serialised against the collection — the
 * Kotlin analogue of the Swift actor-isolated driver.
 */
private suspend fun MetaWearDevice.runFirmwareUpdate(
    context: Context,
    zipUrl: URI,
    fetcher: FirmwareFetcher,
    collector: FlowCollector<DFUProgress>,
) {
    // 1. State check.
    ensureFlashableState()

    // 2. Resolve the firmware URL to a local file. Remote URLs go through
    //    the fetcher; file:// URIs are used as-is.
    val localFile: File
    if (zipUrl.isFileUri) {
        localFile = File(zipUrl)
    } else {
        collector.emit(DFUProgress(DFUProgress.State.DOWNLOADING_FIRMWARE))
        localFile = downloadToLocal(zipUrl, fetcher)
    }

    // 3. Validate the artifact type (zip vs bin/hex) before tearing down BLE
    //    so an unusable file bails out cleanly while we're still connected.
    validateFirmwareFile(localFile)

    // 4. Capture the address — we'll use it to address the bootloader-mode
    //    peripheral once BLE drops.
    val targetAddress = identifier

    // 5. Bootloader handoff, then a single-stage flash. The explicit-URL path
    //    has no catalog metadata, so no bootloader interlock applies here —
    //    callers flashing custom firmware are expected to know their board's
    //    bootloader.
    handoffToBootloader(collector)
    flashStages(context, listOf(localFile), targetAddress, collector)
}

/**
 * Driver for `updateFirmwareToLatest(server:)`. See the public KDoc for the
 * bootloader-interlock behavior.
 */
private suspend fun MetaWearDevice.runUpdateToLatest(
    context: Context,
    server: FirmwareServer,
    collector: FlowCollector<DFUProgress>,
) {
    val info = deviceInfo ?: throw FirmwareException.OperationFailed(
        "Device info not populated — call connect() before updateFirmwareToLatest().",
    )

    collector.emit(DFUProgress(DFUProgress.State.FETCHING_CATALOG))
    val build = server.updateAvailable(
        currentRev = info.firmwareRevision,
        hardwareRev = info.hardwareRevision,
        modelNumber = info.modelNumber,
    ) ?: return // Already up to date. Complete with no further events.

    collector.emit(DFUProgress(DFUProgress.State.DOWNLOADING_FIRMWARE))
    val applicationFile = server.downloadFirmware(build)
    // Validate before tearing down BLE so a bad artifact bails out while the
    // board is still in application mode.
    validateFirmwareFile(applicationFile)

    ensureFlashableState()
    val targetAddress = identifier
    handoffToBootloader(collector)

    val stages = mutableListOf<File>()
    if (build.requiredBootloader != null) {
        stages += bootloaderStagesIfNeeded(context, build, info, server, targetAddress, collector)
    }
    stages += applicationFile
    flashStages(context, stages, targetAddress, collector)
}

/**
 * Mid-stream / mid-log / mid-download is unsafe (the user should stop those
 * first) — but "not connected" is a different situation deserving a different
 * error: BLE links drop silently (supervision timeouts), and telling the user
 * to "stop streaming" when the board simply disconnected sends them debugging
 * the wrong thing.
 */
private fun MetaWearDevice.ensureFlashableState() {
    when (state.value) {
        DeviceState.Idle -> Unit
        DeviceState.Disconnected, DeviceState.Connecting ->
            throw FirmwareException.OperationFailed(
                "The board is not connected. Reconnect and try again.",
            )
        else -> throw FirmwareException.DeviceNotIdle
    }
}

/**
 * Send jump-to-bootloader and wait for the BOARD to drop the link as it
 * reboots into MetaBoot. Cancelling the connection from our side raced the
 * reboot on iOS: Nordic would reconnect to a board still running app-mode
 * firmware and fail service discovery with "DFU Service not found".
 * `sendExpectingDisconnect` already waits for the drop and converges local
 * state on Disconnected.
 */
private suspend fun MetaWearDevice.handoffToBootloader(collector: FlowCollector<DFUProgress>) {
    collector.emit(DFUProgress(DFUProgress.State.BOOTLOADER_HANDOFF))
    sendExpectingDisconnect(Debug.JumpToBootloader())
    // Give MetaBoot time to finish booting and start advertising before
    // anything connects to it.
    delay(1500)
}

/**
 * Flash one or more firmware images in sequence — bootloader first when the
 * interlock demands it, then the application. Each stage is one Nordic DFU
 * run; after a bootloader stage the board resets back into MetaBoot (there's
 * no valid application to boot into yet).
 *
 * Progress from every stage is renumbered so observers see stage-level
 * `currentPart` / `totalParts`, and COMPLETED is suppressed for all but the
 * final stage — only the whole sequence finishing means "done".
 */
private suspend fun flashStages(
    context: Context,
    stages: List<File>,
    targetAddress: String,
    collector: FlowCollector<DFUProgress>,
) {
    val total = stages.size
    for ((index, firmware) in stages.withIndex()) {
        // A cancelled collector must never start (or continue past) a Nordic
        // DFU pass.
        currentCoroutineContext().ensureActive()
        val stage = index + 1
        val isLast = stage == total
        collector.emit(DFUProgress(DFUProgress.State.SCANNING, currentPart = stage, totalParts = total))
        try {
            runDfuPass(context, firmware, targetAddress, stage, total, isLast, collector)
        } catch (e: FirmwareException.DfuFailed) {
            if (!e.isDfuServiceNotFound) throw e
            // Nordic lost the reconnect race (pre-reboot window / stale
            // GATT). MetaBoot sits waiting after boot, so one delayed retry
            // per stage is safe.
            Log.d(TAG, "[DFU] service not found — retrying once after settle delay")
            delay(2000)
            collector.emit(DFUProgress(DFUProgress.State.SCANNING, currentPart = stage, totalParts = total))
            runDfuPass(context, firmware, targetAddress, stage, total, isLast, collector)
        }
        if (!isLast) {
            Log.d(TAG, "[DFU] stage $stage/$total flashed — waiting for reboot into MetaBoot")
            // Cancellation between stages must abort the sequence, not fall
            // through into the next flash (delay is cancellation-transparent).
            delay(2500)
        }
    }
}

/**
 * Whether this DFU failure is the Android library's ERROR_SERVICE_NOT_FOUND
 * (the bootloader wasn't advertising the DFU service yet). [DfuSession]
 * formats details as `"<code>: <message>"`, so the code prefix is checked
 * first; the message text is a fallback in case the code ever changes.
 */
private val FirmwareException.DfuFailed.isDfuServiceNotFound: Boolean
    get() = details.startsWith("${DfuBaseService.ERROR_SERVICE_NOT_FOUND}:") ||
        details.contains("service not found", ignoreCase = true)

/**
 * One Nordic DFU attempt. [DfuSession] is single-use, so each pass gets a
 * fresh session.
 *
 * Unlike Swift's AsyncThrowingStream (whose `next()` returns nil on task
 * cancellation instead of throwing), a cancelled Flow collection throws
 * CancellationException — so a cancelled pass can never read as stage
 * success. The trailing ensureActive is belt-and-braces parity.
 */
private suspend fun runDfuPass(
    context: Context,
    firmware: File,
    targetAddress: String,
    stage: Int,
    total: Int,
    isLast: Boolean,
    collector: FlowCollector<DFUProgress>,
) {
    val session = DfuSession(context)
    session.run(firmware, targetAddress).collect { progress ->
        // A non-final stage finishing is progress, not completion.
        if (progress.state == DFUProgress.State.COMPLETED && !isLast) return@collect
        if (total > 1) {
            collector.emit(progress.copy(currentPart = stage, totalParts = total))
        } else {
            collector.emit(progress)
        }
    }
    currentCoroutineContext().ensureActive()
}

/**
 * Read the installed bootloader from MetaBoot and, when it's older than the
 * build's requirement, download the chain of catalog bootloaders that fixes
 * it (bootloader builds declare requirements of their own, so one upgrade may
 * need stepping stones).
 *
 * A failed PROBE degrades to a single-stage flash (pre-interlock behavior)
 * rather than blocking the update: near-all boards in the field already run
 * an adequate bootloader, and a flaky characteristic read shouldn't strand
 * them. Cancellation is NOT a probe failure and is rethrown — a cancelled
 * update must never proceed to flash. A confirmed-outdated bootloader with no
 * catalog remedy throws [FirmwareException.BootloaderUpgradeUnavailable];
 * catalog fetch errors propagate as themselves so a transient network blip
 * doesn't masquerade as that terminal verdict.
 */
private suspend fun bootloaderStagesIfNeeded(
    context: Context,
    build: FirmwareBuild,
    info: DeviceInformation,
    server: FirmwareServer,
    targetAddress: String,
    collector: FlowCollector<DFUProgress>,
): List<File> {
    val installed = try {
        MetaBootProbe.readBootloaderVersion(context, targetAddress).also {
            Log.d(TAG, "[DFU] MetaBoot reports bootloader $it")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: FirmwareException.Aborted) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "[DFU] bootloader probe failed (${e.message}) — flashing application only")
        return emptyList()
    }
    val bootloaders = server.availableBuilds(
        hardwareRev = info.hardwareRevision,
        modelNumber = info.modelNumber,
        buildFlavor = "bootloader",
    )
    val plan = BootloaderInterlock.plan(
        installedBootloader = installed,
        requiredBootloader = build.requiredBootloader,
        availableBootloaders = bootloaders,
        hardwareRev = info.hardwareRevision,
    )
    val chain = (plan as? BootloaderInterlock.Plan.FlashBootloadersFirst)?.chain
        ?: return emptyList()
    Log.d(
        TAG,
        "[DFU] bootloader $installed < required ${build.requiredBootloader} — " +
            "staging ${chain.joinToString(" → ") { it.firmwareRev }}",
    )
    val total = chain.size + 1
    val files = mutableListOf<File>()
    for ((index, bootloaderBuild) in chain.withIndex()) {
        collector.emit(
            DFUProgress(
                DFUProgress.State.DOWNLOADING_FIRMWARE,
                currentPart = index + 1,
                totalParts = total,
            ),
        )
        val file = server.downloadFirmware(bootloaderBuild)
        validateFirmwareFile(file)
        files += file
    }
    return files
}

// ---- Helpers ----

private suspend fun downloadToLocal(url: URI, fetcher: FirmwareFetcher): File {
    val fetched = fetcher.downloadFile(url)
    if (fetched.statusCode !in 200..299) {
        fetched.file.delete()
        throw FirmwareException.BadServerResponse(fetched.statusCode)
    }
    // Re-stage under the source's filename: validation and Nordic's initiator
    // dispatch on the extension, and the fetcher's temp file ends in ".tmp",
    // which would be rejected as an invalid firmware container.
    return FirmwareServer.stageDownload(tempFile = fetched.file, filename = url.lastPathComponent)
}

/**
 * The extension gate Swift performs by constructing DFUFirmware: `.zip` goes
 * to the Nordic distribution-package path, `.bin`/`.hex` to the raw-image
 * path, anything else is rejected. Content-level parse failures surface later
 * as DfuFailed from the service (see the file-header note).
 */
private fun validateFirmwareFile(file: File) {
    when (file.extension.lowercase()) {
        "zip", "bin", "hex" -> Unit
        else -> throw FirmwareException.InvalidFirmwareFile(file.toURI())
    }
}
