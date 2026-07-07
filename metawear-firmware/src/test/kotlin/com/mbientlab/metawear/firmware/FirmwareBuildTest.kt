package com.mbientlab.metawear.firmware

import java.io.File
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Coverage for FirmwareBuild URL
// composition and equality. The catalog constructor composes a CDN URL from
// the four catalog keys + filename; the custom-URL constructor accepts an
// off-CDN URL as-is.

/** Tests for [FirmwareBuild]. */
class FirmwareBuildTest {

    // ---- CDN URL composition ----

    @Test
    fun `cdnURL composedFromCatalogKeys`() {
        val build = FirmwareBuild(
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
            firmwareRev = "1.7.3",
            filename = "firmware.zip",
            requiredBootloader = "0.5",
        )
        assertEquals(
            "https://mbientlab.com/releases/metawear/0.4/5/vanilla/1.7.3/firmware.zip",
            build.firmwareUrl.toString(),
        )
    }

    @Test
    fun `cdnURL handlesAllStandardModelNumbers`() {
        // MetaMotion R = 5, C = 6, S = 8 in MbientLab's catalog
        for (model in listOf("5", "6", "8")) {
            val build = FirmwareBuild(
                hardwareRev = "0.4",
                modelNumber = model,
                buildFlavor = "vanilla",
                firmwareRev = "1.7.3",
                filename = "firmware.zip",
                requiredBootloader = null,
            )
            val suffix = "/metawear/0.4/$model/vanilla/1.7.3/firmware.zip"
            assertTrue(build.firmwareUrl.toString().endsWith(suffix))
        }
    }

    @Test
    fun `cdnURL handlesAllStandardFlavors`() {
        for (flavor in listOf("vanilla", "bootloader")) {
            val build = FirmwareBuild(
                hardwareRev = "0.4",
                modelNumber = "5",
                buildFlavor = flavor,
                firmwareRev = "1.7.3",
                filename = "firmware.zip",
                requiredBootloader = null,
            )
            assertTrue(build.firmwareUrl.toString().contains("/$flavor/"))
        }
    }

    // ---- Custom URL constructor ----

    @Test
    fun `customURL usedAsIs`() {
        val url = URI("https://example.com/test/firmware.zip")
        val build = FirmwareBuild(
            hardwareRev = "0.4",
            modelNumber = "5",
            firmwareRev = "1.7.3",
            customUrl = url,
        )
        assertEquals(url, build.firmwareUrl)
        assertEquals("vanilla", build.buildFlavor)      // default
        assertEquals("firmware.zip", build.filename)    // derived from URL
        assertNull(build.requiredBootloader)
    }

    @Test
    fun `customURL acceptsFileURL`() {
        val fileUrl = File("/tmp/firmware.zip").toURI()
        val build = FirmwareBuild(
            hardwareRev = "0.4",
            modelNumber = "5",
            firmwareRev = "1.7.3",
            customUrl = fileUrl,
        )
        assertTrue(build.firmwareUrl.isFileUri)
        assertEquals("firmware.zip", build.filename)
    }

    @Test
    fun `customURL overrideFilenameAndFlavor`() {
        val url = URI("https://example.com/anything")
        val build = FirmwareBuild(
            hardwareRev = "0.4",
            modelNumber = "5",
            firmwareRev = "0.4.0",
            customUrl = url,
            filename = "bootloader.zip",
            buildFlavor = "bootloader",
            requiredBootloader = null,
        )
        assertEquals("bootloader.zip", build.filename)
        assertEquals("bootloader", build.buildFlavor)
    }

    // ---- Equality ----

    @Test
    fun `builds equalWhenAllFieldsMatch`() {
        val a = FirmwareBuild(
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
            firmwareRev = "1.7.3",
            filename = "firmware.zip",
            requiredBootloader = "0.5",
        )
        val b = FirmwareBuild(
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
            firmwareRev = "1.7.3",
            filename = "firmware.zip",
            requiredBootloader = "0.5",
        )
        assertEquals(a, b)
    }

    @Test
    fun `builds differOnAnyField`() {
        val base = FirmwareBuild(
            hardwareRev = "0.4", modelNumber = "5", buildFlavor = "vanilla",
            firmwareRev = "1.7.3", filename = "firmware.zip",
            requiredBootloader = "0.5",
        )
        val differentRev = FirmwareBuild(
            hardwareRev = "0.4", modelNumber = "5", buildFlavor = "vanilla",
            firmwareRev = "1.8.0", filename = "firmware.zip",
            requiredBootloader = "0.5",
        )
        assertNotEquals(base, differentRev)
    }
}
