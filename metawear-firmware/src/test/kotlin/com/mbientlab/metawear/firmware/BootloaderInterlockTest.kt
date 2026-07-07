package com.mbientlab.metawear.firmware

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

// Coverage for the pure decision
// table behind the bootloader interlock: flash the application directly,
// stage a bootloader flash first, or refuse because the catalog can't satisfy
// the requirement.

/** Tests for [BootloaderInterlock]. */
class BootloaderInterlockTest {

    private fun bootloaderBuild(version: String, requires: String? = null) = FirmwareBuild(
        hardwareRev = "0.1",
        modelNumber = "8",
        buildFlavor = "bootloader",
        firmwareRev = version,
        filename = "temp.zip",
        requiredBootloader = requires,
    )

    @Test
    fun `upToDateBootloaderFlashesApplicationOnly`() {
        val plan = BootloaderInterlock.plan(
            installedBootloader = "0.4.0",
            requiredBootloader = "0.4.0",
            availableBootloaders = listOf(bootloaderBuild("0.4.0")),
            hardwareRev = "0.1",
        )
        assertEquals(BootloaderInterlock.Plan.FlashApplicationOnly, plan)
    }

    @Test
    fun `newerThanRequiredFlashesApplicationOnly`() {
        val plan = BootloaderInterlock.plan(
            installedBootloader = "0.5.1",
            requiredBootloader = "0.4.0",
            availableBootloaders = listOf(bootloaderBuild("0.4.0")),
            hardwareRev = "0.1",
        )
        assertEquals(BootloaderInterlock.Plan.FlashApplicationOnly, plan)
    }

    @Test
    fun `noRequirementFlashesApplicationOnly`() {
        val plan = BootloaderInterlock.plan(
            installedBootloader = "0.1.0",
            requiredBootloader = null,
            availableBootloaders = emptyList(),
            hardwareRev = "0.1",
        )
        assertEquals(BootloaderInterlock.Plan.FlashApplicationOnly, plan)
    }

    @Test
    fun `outdatedBootloaderStagesUpgradeFirst`() {
        val plan = BootloaderInterlock.plan(
            installedBootloader = "0.3.2",
            requiredBootloader = "0.4.0",
            availableBootloaders = listOf(bootloaderBuild("0.3.0"), bootloaderBuild("0.4.0")),
            hardwareRev = "0.1",
        )
        assertEquals(
            BootloaderInterlock.Plan.FlashBootloadersFirst(listOf(bootloaderBuild("0.4.0"))),
            plan,
        )
    }

    @Test
    fun `picksNewestSatisfyingBootloader`() {
        // Ascending catalog order; the newest flashable build wins, not the
        // minimum one — a single hop when nothing gates it.
        val plan = BootloaderInterlock.plan(
            installedBootloader = "0.2.0",
            requiredBootloader = "0.4.0",
            availableBootloaders = listOf(
                bootloaderBuild("0.3.0"),
                bootloaderBuild("0.4.0"),
                bootloaderBuild("0.5.0"),
            ),
            hardwareRev = "0.1",
        )
        assertEquals(
            BootloaderInterlock.Plan.FlashBootloadersFirst(listOf(bootloaderBuild("0.5.0"))),
            plan,
        )
    }

    @Test
    fun `selfReferentialRequirementIsFlashable`() {
        // The live catalog lists bootloader 0.4.0 as requiring 0.4.0 —
        // metadata noise, not a gate; taking it literally would make the
        // build unflashable from every older bootloader.
        val plan = BootloaderInterlock.plan(
            installedBootloader = "0.3.0",
            requiredBootloader = "0.4.0",
            availableBootloaders = listOf(bootloaderBuild("0.4.0", requires = "0.4.0")),
            hardwareRev = "0.1",
        )
        assertEquals(
            BootloaderInterlock.Plan.FlashBootloadersFirst(
                listOf(bootloaderBuild("0.4.0", requires = "0.4.0")),
            ),
            plan,
        )
    }

    @Test
    fun `chainsThroughIntermediateBootloader`() {
        // 0.5 can't flash from 0.2 (it requires 0.3), so the plan steps
        // through 0.3 first: two hops, oldest first.
        val plan = BootloaderInterlock.plan(
            installedBootloader = "0.2.0",
            requiredBootloader = "0.5.0",
            availableBootloaders = listOf(
                bootloaderBuild("0.3.0"),
                bootloaderBuild("0.5.0", requires = "0.3.0"),
            ),
            hardwareRev = "0.1",
        )
        assertEquals(
            BootloaderInterlock.Plan.FlashBootloadersFirst(
                listOf(
                    bootloaderBuild("0.3.0"),
                    bootloaderBuild("0.5.0", requires = "0.3.0"),
                ),
            ),
            plan,
        )
    }

    @Test
    fun `unreachableChainThrows`() {
        // The only adequate bootloader needs a stepping stone the catalog
        // doesn't carry — refusing beats stranding the board mid-flash.
        val e = assertThrows(FirmwareException.BootloaderUpgradeUnavailable::class.java) {
            BootloaderInterlock.plan(
                installedBootloader = "0.2.0",
                requiredBootloader = "0.5.0",
                availableBootloaders = listOf(bootloaderBuild("0.5.0", requires = "0.3.0")),
                hardwareRev = "0.1",
            )
        }
        assertEquals(
            FirmwareException.BootloaderUpgradeUnavailable(requiredVersion = "0.5.0", hardwareRev = "0.1"),
            e,
        )
    }

    @Test
    fun `missingCatalogBootloaderThrows`() {
        val e = assertThrows(FirmwareException.BootloaderUpgradeUnavailable::class.java) {
            BootloaderInterlock.plan(
                installedBootloader = "0.3.2",
                requiredBootloader = "0.4.0",
                availableBootloaders = listOf(bootloaderBuild("0.3.0")),
                hardwareRev = "0.1",
            )
        }
        assertEquals(
            FirmwareException.BootloaderUpgradeUnavailable(requiredVersion = "0.4.0", hardwareRev = "0.1"),
            e,
        )
    }

    @Test
    fun `emptyCatalogThrowsWhenUpgradeNeeded`() {
        val e = assertThrows(FirmwareException.BootloaderUpgradeUnavailable::class.java) {
            BootloaderInterlock.plan(
                installedBootloader = "0.2.0",
                requiredBootloader = "0.4.0",
                availableBootloaders = emptyList(),
                hardwareRev = "0.1",
            )
        }
        assertEquals(
            FirmwareException.BootloaderUpgradeUnavailable(requiredVersion = "0.4.0", hardwareRev = "0.1"),
            e,
        )
    }

    @Test
    fun `versionComparisonIsNumericNotLexicographic`() {
        // "0.10" must sort ABOVE "0.9" — a lexicographic comparison would
        // wrongly stage an upgrade here.
        val plan = BootloaderInterlock.plan(
            installedBootloader = "0.10",
            requiredBootloader = "0.9",
            availableBootloaders = listOf(bootloaderBuild("0.9")),
            hardwareRev = "0.1",
        )
        assertEquals(BootloaderInterlock.Plan.FlashApplicationOnly, plan)
    }

    @Test
    fun `paddedVersionsCompareEqual`() {
        // "0.4" and "0.4.0" are the same version in MetaWear's loose form.
        val plan = BootloaderInterlock.plan(
            installedBootloader = "0.4",
            requiredBootloader = "0.4.0",
            availableBootloaders = emptyList(),
            hardwareRev = "0.1",
        )
        assertEquals(BootloaderInterlock.Plan.FlashApplicationOnly, plan)
    }
}
