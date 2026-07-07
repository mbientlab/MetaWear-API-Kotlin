package com.mbientlab.metawear.firmware

import java.io.File
import java.net.URI
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Ported from MWFirmwareServerTests.swift — coverage for FirmwareServer, the
// layer above FirmwareCatalog that coordinates HTTP fetches, build selection,
// and update detection.
//
// All tests use MockFirmwareFetcher so nothing actually hits the network. The
// mock can be configured to return canned data, simulate HTTP errors, and
// observe which URLs were requested.

/** Port of the "MWFirmwareServer" suite. */
class FirmwareServerTest {

    // ---- Catalog reads ----

    @Test
    fun `availableBuilds returnsFilteredAndSorted`() = runTest {
        val server = FirmwareServer(
            fetcher = MockFirmwareFetcher(catalog = Fixtures.catalogJSON),
            catalogUrl = Fixtures.catalogUrl,
            sdkVersion = "5.0.0",
        )
        val builds = server.availableBuilds(
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
        )
        // Three matching builds, ascending by version.
        assertEquals(listOf("1.5.0", "1.7.3", "2.0.0-beta"), builds.map { it.firmwareRev })
    }

    @Test
    fun `latestBuild returnsHighestVersion`() = runTest {
        val server = FirmwareServer(
            fetcher = MockFirmwareFetcher(catalog = Fixtures.catalogJSON),
            catalogUrl = Fixtures.catalogUrl,
            sdkVersion = "5.0.0",
        )
        val latest = server.latestBuild(
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
        )
        assertEquals("2.0.0-beta", latest.firmwareRev)
    }

    @Test
    fun `latestBuild filteredBySDK`() = runTest {
        // SDK 3.2 can't see the 4.0-gated 2.0.0-beta — latest is 1.7.3.
        val server = FirmwareServer(
            fetcher = MockFirmwareFetcher(catalog = Fixtures.catalogJSON),
            catalogUrl = Fixtures.catalogUrl,
            sdkVersion = "3.2.0",
        )
        val latest = server.latestBuild(
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
        )
        assertEquals("1.7.3", latest.firmwareRev)
    }

    @Test
    fun `latestBuild throwsWhenNoneMatch`() = runTest {
        val server = FirmwareServer(
            fetcher = MockFirmwareFetcher(catalog = Fixtures.catalogJSON),
            catalogUrl = Fixtures.catalogUrl,
            sdkVersion = "5.0.0",
        )
        val error = runCatching {
            server.latestBuild(hardwareRev = "9.9", modelNumber = "5", buildFlavor = "vanilla")
        }.exceptionOrNull()
        assertTrue(error is FirmwareException.NoAvailableFirmware, "unexpected error: $error")
    }

    // ---- Specific-version lookup ----

    @Test
    fun `build returnsRequestedVersion`() = runTest {
        val server = FirmwareServer(
            fetcher = MockFirmwareFetcher(catalog = Fixtures.catalogJSON),
            catalogUrl = Fixtures.catalogUrl,
            sdkVersion = "5.0.0",
        )
        val build = server.build(
            hardwareRev = "0.4",
            modelNumber = "5",
            firmwareRev = "1.5.0",
            buildFlavor = "vanilla",
        )
        assertEquals("1.5.0", build?.firmwareRev)
        assertEquals("0.4", build?.requiredBootloader)
    }

    @Test
    fun `build returnsNullForUnknownVersion`() = runTest {
        val server = FirmwareServer(
            fetcher = MockFirmwareFetcher(catalog = Fixtures.catalogJSON),
            catalogUrl = Fixtures.catalogUrl,
            sdkVersion = "5.0.0",
        )
        val build = server.build(
            hardwareRev = "0.4",
            modelNumber = "5",
            firmwareRev = "99.99.99",
            buildFlavor = "vanilla",
        )
        assertNull(build)
    }

    // ---- updateAvailable ----

    @Test
    fun `updateAvailable returnsNullWhenAlreadyLatest`() = runTest {
        val server = FirmwareServer(
            fetcher = MockFirmwareFetcher(catalog = Fixtures.catalogJSON),
            catalogUrl = Fixtures.catalogUrl,
            sdkVersion = "5.0.0",
        )
        // Device is already on the latest visible version.
        val update = server.updateAvailable(
            currentRev = "2.0.0-beta",
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
        )
        assertNull(update)
    }

    @Test
    fun `updateAvailable returnsLatestWhenOlder`() = runTest {
        val server = FirmwareServer(
            fetcher = MockFirmwareFetcher(catalog = Fixtures.catalogJSON),
            catalogUrl = Fixtures.catalogUrl,
            sdkVersion = "5.0.0",
        )
        val update = server.updateAvailable(
            currentRev = "1.5.0",
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
        )
        assertEquals("2.0.0-beta", update?.firmwareRev)
    }

    @Test
    fun `updateAvailable returnsLatestWhenAheadOfSDK`() = runTest {
        // Device ahead of what THIS SDK can flash — return the latest the SDK
        // can support, even if it's a downgrade. This matches the legacy
        // Combine SDK's behaviour and lets users pin a known-good version
        // when the SDK isn't ready for the latest beta.
        val server = FirmwareServer(
            fetcher = MockFirmwareFetcher(catalog = Fixtures.catalogJSON),
            catalogUrl = Fixtures.catalogUrl,
            sdkVersion = "3.2.0",
        )
        val update = server.updateAvailable(
            currentRev = "0.9.0",
            hardwareRev = "0.4",
            modelNumber = "5",
            buildFlavor = "vanilla",
        )
        // SDK 3.2 caps at 1.7.3; the device on 0.9.0 should see 1.7.3.
        assertEquals("1.7.3", update?.firmwareRev)
    }

    // ---- HTTP errors ----

    @Test
    fun `availableBuilds throwsBadServerResponseOn500`() = runTest {
        val mock = MockFirmwareFetcher(catalog = Fixtures.catalogJSON, statusCode = 500)
        val server = FirmwareServer(
            fetcher = mock,
            catalogUrl = Fixtures.catalogUrl,
            sdkVersion = "5.0.0",
        )
        val error = runCatching {
            server.availableBuilds(hardwareRev = "0.4", modelNumber = "5", buildFlavor = "vanilla")
        }.exceptionOrNull()
        assertEquals(FirmwareException.BadServerResponse(status = 500), error)
    }

    @Test
    fun `availableBuilds throwsInvalidServerResponseOnBadJSON`() = runTest {
        val mock = MockFirmwareFetcher(catalog = "not json".toByteArray())
        val server = FirmwareServer(
            fetcher = mock,
            catalogUrl = Fixtures.catalogUrl,
            sdkVersion = "5.0.0",
        )
        val error = runCatching {
            server.availableBuilds(hardwareRev = "0.4", modelNumber = "5", buildFlavor = "vanilla")
        }.exceptionOrNull()
        assertTrue(error is FirmwareException.InvalidServerResponse, "unexpected error: $error")
    }

    // ---- Firmware download / staging ----

    // These exercise the REAL file staging path — the one place in the server
    // that touches the filesystem. A regression here shipped once in the
    // Swift SDK: staging resolved against the REMOTE firmware URL, which
    // threw `The file "firmware.zip" doesn't exist` after every successful
    // download on device.

    @Test
    fun `downloadFirmware stagesUnderBuildFilename`() = runTest {
        val payload = "MOCK-FIRMWARE-BYTES".toByteArray()
        val server = FirmwareServer(
            fetcher = MockFirmwareFetcher(catalog = Fixtures.catalogJSON, firmware = payload),
            catalogUrl = Fixtures.catalogUrl,
            sdkVersion = "5.0.0",
        )
        val build = FirmwareBuild(
            hardwareRev = "0.1",
            modelNumber = "8",
            buildFlavor = "vanilla",
            firmwareRev = "1.7.2",
            filename = "firmware.zip",
            requiredBootloader = "0.4.0",
        )
        val staged = server.downloadFirmware(build)
        try {
            // The staged artifact must exist, carry the catalog filename (the
            // DFU initiator dispatches on the ".zip" extension — the mock's
            // source file ends in ".bin"), and hold the downloaded bytes.
            assertTrue(staged.exists())
            assertEquals("firmware.zip", staged.name)
            assertArrayEquals(payload, staged.readBytes())
        } finally {
            staged.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `downloadFirmware returnsFileURLsUnchanged`() = runTest {
        val server = FirmwareServer(
            fetcher = MockFirmwareFetcher(catalog = Fixtures.catalogJSON),
            catalogUrl = Fixtures.catalogUrl,
            sdkVersion = "5.0.0",
        )
        val local = File(System.getProperty("java.io.tmpdir"), "already-local-firmware.zip")
        val build = FirmwareBuild(
            hardwareRev = "0.1",
            modelNumber = "8",
            firmwareRev = "1.7.2",
            customUrl = local.toURI(),
        )
        // file:// builds skip the network and staging entirely.
        val result = server.downloadFirmware(build)
        assertEquals(local.absoluteFile, result.absoluteFile)
    }

    @Test
    fun `downloadFirmware throwsBadServerResponseOn404`() = runTest {
        val server = FirmwareServer(
            fetcher = MockFirmwareFetcher(catalog = Fixtures.catalogJSON, statusCode = 404),
            catalogUrl = Fixtures.catalogUrl,
            sdkVersion = "5.0.0",
        )
        val build = FirmwareBuild(
            hardwareRev = "0.1",
            modelNumber = "8",
            buildFlavor = "vanilla",
            firmwareRev = "1.7.2",
            filename = "firmware.zip",
            requiredBootloader = null,
        )
        val error = runCatching { server.downloadFirmware(build) }.exceptionOrNull()
        assertEquals(FirmwareException.BadServerResponse(status = 404), error)
    }

    // ---- Catalog URL is honoured ----

    @Test
    fun `server fetchesCatalogFromConfiguredURL`() = runTest {
        val mock = MockFirmwareFetcher(catalog = Fixtures.catalogJSON)
        val custom = URI("https://staging.example.com/info2.json")
        val server = FirmwareServer(
            fetcher = mock,
            catalogUrl = custom,
            sdkVersion = "5.0.0",
        )
        server.availableBuilds(hardwareRev = "0.4", modelNumber = "5", buildFlavor = "vanilla")
        assertEquals(listOf(custom), mock.requestedDataUrls)
    }
}

// ---- Mock fetcher ----

/**
 * In-memory implementation of [FirmwareFetcher] for unit tests. Returns
 * canned bytes for [fetchData] and a synthesized temp file for
 * [downloadFile]. Records every URL requested so tests can assert on call
 * shape.
 */
private class MockFirmwareFetcher(
    private val catalog: ByteArray,
    private val firmware: ByteArray = "MOCK-FIRMWARE-BYTES".toByteArray(),
    private val statusCode: Int = 200,
) : FirmwareFetcher {

    val requestedDataUrls = mutableListOf<URI>()
    val requestedDownloadUrls = mutableListOf<URI>()

    override suspend fun fetchData(url: URI): FetchedData {
        requestedDataUrls += url
        return FetchedData(body = catalog, statusCode = statusCode)
    }

    override suspend fun downloadFile(url: URI): FetchedFile {
        requestedDownloadUrls += url
        val tempFile = File.createTempFile("mock-", ".bin")
        tempFile.writeBytes(firmware)
        return FetchedFile(file = tempFile, statusCode = statusCode)
    }
}

// ---- Fixtures ----

private object Fixtures {

    val catalogUrl = URI("https://test.example.com/info2.json")

    /**
     * Same shape as FirmwareCatalogTest.catalogJSON — duplicated so each test
     * file is self-contained (mirroring the Swift fixtures).
     */
    val catalogJSON: ByteArray = """
    {
      "0.4": {
        "5": {
          "vanilla": {
            "1.5.0": {
              "filename": "firmware.zip",
              "required-bootloader": "0.4",
              "min-ios-version": "3.0.0"
            },
            "1.7.3": {
              "filename": "firmware.zip",
              "required-bootloader": "0.5",
              "min-ios-version": "3.2.0"
            },
            "2.0.0-beta": {
              "filename": "firmware.zip",
              "required-bootloader": "0.5",
              "min-ios-version": "4.0.0"
            }
          }
        }
      }
    }
    """.trimIndent().toByteArray()
}
