package com.mbientlab.metawear.firmware

// Decides whether a firmware flash must be preceded by bootloader flashes.
//
// Every catalog build declares the minimum bootloader it needs
// (`required-bootloader` -> `FirmwareBuild.requiredBootloader`). Flashing an
// application image onto a board whose bootloader is older fails inside
// Nordic DFU validation and leaves the board sitting in MetaBoot — so the
// orchestrator reads the on-board bootloader version (MetaBoot's Device
// Information service) and consults this pure decision table first.
//
// Bootloader builds carry `required-bootloader` themselves, so one upgrade
// may need stepping stones: the plan is a CHAIN of bootloader flashes, each
// flashable from the version the previous one leaves behind.
//
// Kept free of device/network dependencies so the outcomes are directly
// unit-testable.

internal object BootloaderInterlock {

    sealed class Plan {
        /**
         * The installed bootloader satisfies the target build — flash the
         * application image directly.
         */
        data object FlashApplicationOnly : Plan()

        /**
         * The installed bootloader is too old — flash these bootloader builds
         * in order (oldest hop first), then the application image.
         */
        data class FlashBootloadersFirst(val chain: List<FirmwareBuild>) : Plan()
    }

    /**
     * Decide the flash plan for a target build.
     *
     * When an upgrade is needed, greedily picks the newest catalog bootloader
     * that is flashable from the currently installed version, repeating until
     * the requirement is met — each hop strictly increases the version, so
     * the walk terminates.
     *
     * @param installedBootloader Version read from MetaBoot's Firmware
     *   Revision characteristic (e.g. `"0.4.0"`).
     * @param requiredBootloader The target build's requirement; `null` means
     *   the build declares none and the application flashes directly.
     * @param availableBootloaders Catalog builds of the `"bootloader"` flavor
     *   for this (hardwareRev, modelNumber), ascending by version — the order
     *   [FirmwareServer.availableBuilds] returns.
     * @param hardwareRev For the error, so the user-facing message names the
     *   hardware whose catalog is missing the bootloader.
     * @throws FirmwareException.BootloaderUpgradeUnavailable when an upgrade
     *   is needed but no reachable chain of catalog bootloaders satisfies the
     *   requirement — flashing the application anyway would strand the board
     *   in MetaBoot.
     */
    fun plan(
        installedBootloader: String,
        requiredBootloader: String?,
        availableBootloaders: List<FirmwareBuild>,
        hardwareRev: String,
    ): Plan {
        if (requiredBootloader == null ||
            !installedBootloader.isMetaWearVersionLessThan(requiredBootloader)
        ) {
            return Plan.FlashApplicationOnly
        }
        val chain = mutableListOf<FirmwareBuild>()
        var current = installedBootloader
        while (current.isMetaWearVersionLessThan(requiredBootloader)) {
            // Ascending input + order-preserving filter: `lastOrNull` is the
            // newest hop flashable from where the chain currently stands.
            val next = availableBootloaders.lastOrNull { build ->
                build.firmwareRev.isMetaWearVersionGreaterThan(current) &&
                    isFlashable(build, current)
            } ?: throw FirmwareException.BootloaderUpgradeUnavailable(
                requiredVersion = requiredBootloader,
                hardwareRev = hardwareRev,
            )
            chain.add(next)
            current = next.firmwareRev
        }
        return Plan.FlashBootloadersFirst(chain)
    }

    /**
     * Whether [build] can be flashed onto a board whose bootloader is
     * currently [installed].
     *
     * No declared requirement means always flashable. A SELF-referential
     * requirement (the live catalog lists bootloader 0.4.0 as requiring
     * 0.4.0) is metadata noise, not a real gate — taken literally it would
     * make the build unflashable from every older bootloader, i.e. useless
     * for the only purpose a bootloader build has.
     */
    private fun isFlashable(build: FirmwareBuild, installed: String): Boolean {
        val required = build.requiredBootloader ?: return true
        if (required.isMetaWearVersionEqualTo(build.firmwareRev)) return true
        return installed.isMetaWearVersionGreaterThanOrEqualTo(required)
    }
}
