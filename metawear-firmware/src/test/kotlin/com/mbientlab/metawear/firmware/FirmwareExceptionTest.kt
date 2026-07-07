package com.mbientlab.metawear.firmware

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Smoke coverage for
// FirmwareException. The values are simple but the messages ship in
// user-facing UI, so it's worth catching copy regressions.

/** Tests for [FirmwareException]. */
class FirmwareExceptionTest {

    // ---- Message content (smoke) ----

    @Test
    fun `descriptions includeVariantDetails`() {
        assertTrue(FirmwareException.BadServerResponse(status = 500).message!!.contains("500"))
        assertTrue(
            FirmwareException.InvalidServerResponse("shape mismatch")
                .message!!.contains("shape mismatch"),
        )
        assertTrue(
            FirmwareException.NoAvailableFirmware("no MMR builds")
                .message!!.contains("no MMR builds"),
        )
        assertTrue(FirmwareException.CannotSaveFile("disk full").message!!.contains("disk full"))
        val url = File("/tmp/oddball.tar").toURI()
        assertTrue(FirmwareException.InvalidFirmwareFile(url).message!!.contains("oddball.tar"))
        assertTrue(
            FirmwareException.BootloaderUpgradeUnavailable(requiredVersion = "0.5", hardwareRev = "0.4")
                .message!!.contains("0.5"),
        )
        assertTrue(FirmwareException.DeviceNotIdle.message!!.contains("streaming"))
        assertTrue(FirmwareException.DfuFailed("CRC mismatch").message!!.contains("CRC mismatch"))
        assertTrue(FirmwareException.Aborted.message!!.contains("aborted"))
        assertTrue(
            FirmwareException.OperationFailed("unexpected drop")
                .message!!.contains("unexpected drop"),
        )
    }

    // ---- Equality ----

    @Test
    fun `errors equalWhenSameVariantAndPayload`() {
        assertEquals(
            FirmwareException.BadServerResponse(status = 404),
            FirmwareException.BadServerResponse(status = 404),
        )
        assertEquals(FirmwareException.Aborted, FirmwareException.Aborted)
    }

    @Test
    fun `errors differOnPayload`() {
        assertNotEquals(
            FirmwareException.BadServerResponse(status = 404),
            FirmwareException.BadServerResponse(status = 500),
        )
        assertNotEquals(
            FirmwareException.DfuFailed("a"),
            FirmwareException.DfuFailed("b"),
        )
    }
}
