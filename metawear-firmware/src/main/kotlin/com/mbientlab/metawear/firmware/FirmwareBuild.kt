package com.mbientlab.metawear.firmware

import java.net.URI

// Value type describing one firmware artifact on MbientLab's release CDN.
// URLs are held as `java.net.URI` (inert value type, supports file:// URIs,
// no network access on construction).

/** Last path segment of a URI ("…/1.7.3/firmware.zip" → "firmware.zip"). */
internal val URI.lastPathComponent: String
    get() = (path ?: toString()).substringAfterLast('/')

/** Whether this URI points at a local file (`file://` scheme). */
internal val URI.isFileUri: Boolean
    get() = scheme == "file"

/**
 * One firmware build: which board variant + flavor + version, plus the
 * catalog metadata needed to fetch + validate it.
 *
 * Construct via the catalog constructor (composes the CDN URL from the four
 * catalog keys + filename) or the custom-URL constructor (off-CDN builds).
 * The primary constructor is public only because Kotlin data classes require
 * all properties there; prefer the two secondary constructors.
 */
data class FirmwareBuild(
    /**
     * Hardware revision string the board reports via the standard BLE Device
     * Information service (e.g. `"0.4"`). First key in the catalog JSON.
     */
    val hardwareRev: String,

    /**
     * Model number string the board reports (e.g. `"5"` for MetaMotion R,
     * `"6"` for MetaMotion C, `"8"` for MetaMotion S). Second catalog key.
     */
    val modelNumber: String,

    /**
     * Firmware variant — `"vanilla"` is the standard release; MbientLab also
     * publishes `"bootloader"` builds (used internally for bootloader
     * upgrades) and occasional custom flavors. Third catalog key.
     */
    val buildFlavor: String,

    /** Firmware version this build represents (e.g. `"1.7.3"`). */
    val firmwareRev: String,

    /**
     * Filename on the CDN (typically `"firmware.zip"` for newer Nordic-signed
     * packages, `"firmware.bin"` for older raw application binaries).
     */
    val filename: String,

    /**
     * If non-null, the bootloader version that must already be running on the
     * device for this firmware to flash successfully. If the device's
     * bootloader is older, the orchestrator must DFU the bootloader first.
     */
    val requiredBootloader: String?,

    /**
     * HTTP URL to download the firmware file from MbientLab's CDN, composed
     * from the four catalog keys plus the filename:
     * ```
     * https://mbientlab.com/releases/metawear/<hwRev>/<model>/<flavor>/<fwRev>/<filename>
     * ```
     */
    val firmwareUrl: URI,
) {

    /**
     * Catalog constructor: composes [firmwareUrl] from the catalog keys. The
     * input strings come from the catalog JSON or hand-constructed test
     * values — they don't contain path-illegal characters under normal use.
     * Use the custom-URL constructor below for off-CDN builds.
     */
    constructor(
        hardwareRev: String,
        modelNumber: String,
        buildFlavor: String,
        firmwareRev: String,
        filename: String,
        requiredBootloader: String?,
    ) : this(
        hardwareRev = hardwareRev,
        modelNumber = modelNumber,
        buildFlavor = buildFlavor,
        firmwareRev = firmwareRev,
        filename = filename,
        requiredBootloader = requiredBootloader,
        firmwareUrl = URI(
            "https://mbientlab.com/releases/metawear/$hardwareRev/$modelNumber/$buildFlavor/$firmwareRev/$filename",
        ),
    )

    /**
     * Construct a build pointing at a custom firmware URL (`file://` for a
     * local zip, or a non-MbientLab CDN). Used when the caller has already
     * fetched a firmware file by other means and wants to feed it through the
     * same DFU orchestration as a CDN-sourced build.
     */
    constructor(
        hardwareRev: String,
        modelNumber: String,
        firmwareRev: String,
        customUrl: URI,
        filename: String? = null,
        buildFlavor: String = "vanilla",
        requiredBootloader: String? = null,
    ) : this(
        hardwareRev = hardwareRev,
        modelNumber = modelNumber,
        buildFlavor = buildFlavor,
        firmwareRev = firmwareRev,
        filename = filename ?: customUrl.lastPathComponent,
        requiredBootloader = requiredBootloader,
        firmwareUrl = customUrl,
    )
}
