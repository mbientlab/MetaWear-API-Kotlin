package com.mbientlab.metawear.firmware

// Public progress event emitted by the Flow
// returned from `MetaWearDevice.updateFirmware(...)`.

/**
 * One snapshot of firmware-update progress.
 *
 * The state machine moves forward through the phases below; [percentComplete]
 * is meaningful only during [State.UPLOADING]. Other phases emit with
 * `percentComplete == 0`.
 */
data class DFUProgress(
    /** Current phase of the update. */
    val state: State,

    /** 0…100. Only populated during [State.UPLOADING]; other phases report 0. */
    val percentComplete: Double = 0.0,

    /**
     * Which sub-image of a multi-part DFU is currently uploading. Most
     * MetaWear firmware ships as a single application image, so this is
     * usually `1`.
     */
    val currentPart: Int = 1,

    /**
     * Total parts in the DFU package. Usually `1` (application only); rises
     * to `2` when a softdevice or bootloader image is also present.
     */
    val totalParts: Int = 1,

    /**
     * Instantaneous transfer rate during [State.UPLOADING], in bytes/sec.
     * 0 outside the upload phase.
     */
    val bytesPerSecond: Double = 0.0,
) {

    enum class State {
        /**
         * Looking up the firmware build (catalog fetch + version selection).
         * Skipped when the caller supplies an explicit zip URL.
         */
        FETCHING_CATALOG,

        /**
         * Downloading the firmware file from MbientLab's CDN to a temporary
         * location. Skipped for `file://` URLs.
         */
        DOWNLOADING_FIRMWARE,

        /**
         * Sending `[0xFE, 0x02]` to the device, then waiting for the BLE link
         * to drop.
         */
        BOOTLOADER_HANDOFF,

        /**
         * Disconnected from app-mode peripheral; Nordic DFU is now scanning
         * for the bootloader-mode peripheral (same address, different
         * services).
         */
        SCANNING,

        /** Connecting to the bootloader-mode peripheral. */
        CONNECTING,

        /** DFU service set up; sending the init packet and SELECT command. */
        STARTING,

        /** Validating the firmware image type / device-type match. */
        VALIDATING,

        /**
         * Actively transferring firmware bytes. [percentComplete] is the
         * signal to drive a UI progress bar.
         */
        UPLOADING,

        /** Transfer finished, board is rebooting back into application mode. */
        DISCONNECTING,

        /** All done — board is back in app mode with new firmware. */
        COMPLETED,

        /**
         * Caller cancelled collection of the Flow, or the orchestrator caught
         * a fatal error mid-flight.
         */
        ABORTED,
    }
}
