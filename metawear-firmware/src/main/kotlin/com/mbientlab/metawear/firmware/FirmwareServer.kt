package com.mbientlab.metawear.firmware

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Port of MWFirmwareServer.swift — HTTP client for MbientLab's firmware
// catalog + downloads. Designed so the network layer is mockable — callers
// (and tests) can supply any conforming FirmwareFetcher. The default
// implementation wraps HttpURLConnection (no new HTTP dependency).

// ---- Fetcher seam ----

/** Raw bytes + HTTP status from a catalog fetch. */
class FetchedData(val body: ByteArray, val statusCode: Int)

/** Downloaded temp file + HTTP status from a firmware fetch. */
class FetchedFile(val file: File, val statusCode: Int)

/**
 * Minimal HTTP surface the firmware server needs. Implemented by
 * [HttpUrlConnectionFetcher] (production) and any test double.
 *
 * Two methods, both `suspend`:
 *  • [fetchData]    — fetch raw bytes, used for the catalog JSON
 *  • [downloadFile] — fetch and save to a temporary file, used for the
 *                     firmware artifact (avoids loading the whole image into
 *                     memory and lets us hand a local file to Nordic DFU).
 */
interface FirmwareFetcher {
    /** Fetch raw bytes from a URL. Used for the firmware catalog JSON. */
    suspend fun fetchData(url: URI): FetchedData

    /**
     * Download a URL to a temporary file. Used for firmware artifacts so
     * Nordic DFU can consume a local file path.
     */
    suspend fun downloadFile(url: URI): FetchedFile
}

// ---- HttpURLConnection-backed fetcher (production default) ----

/**
 * Production [FirmwareFetcher] backed by [HttpURLConnection] on
 * [Dispatchers.IO]. Follows redirects; non-HTTP URLs (Swift: "non-HTTP
 * response") are rejected with [FirmwareException.InvalidServerResponse].
 */
class HttpUrlConnectionFetcher(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
) : FirmwareFetcher {

    override suspend fun fetchData(url: URI): FetchedData = withContext(Dispatchers.IO) {
        val connection = open(url)
        try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            FetchedData(body, status)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun downloadFile(url: URI): FetchedFile = withContext(Dispatchers.IO) {
        val connection = open(url)
        try {
            val status = connection.responseCode
            val tempFile = File.createTempFile("metawear-firmware-", ".tmp")
            if (status in 200..299) {
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                connection.errorStream?.close()
            }
            FetchedFile(tempFile, status)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: URI): HttpURLConnection {
        val connection = url.toURL().openConnection() as? HttpURLConnection
            ?: throw FirmwareException.InvalidServerResponse("Non-HTTP response for $url")
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.instanceFollowRedirects = true
        return connection
    }
}

// ---- Server ----

/**
 * High-level firmware-catalog client.
 *
 * Three responsibilities:
 *  1. Fetch the catalog JSON from `info2.json` and parse it.
 *  2. Filter the catalog by (hardwareRev, modelNumber, buildFlavor) and pick
 *     the right build (latest, specific version, etc.).
 *  3. Download the chosen build's firmware file to a local temp path.
 *
 * All three are pure of [com.mbientlab.metawear.MetaWearDevice] — they take
 * values, not the device — so tests can drive them with synthetic fixtures.
 */
class FirmwareServer(
    private val fetcher: FirmwareFetcher = HttpUrlConnectionFetcher(),
    private val catalogUrl: URI = DEFAULT_CATALOG_URL,
    private val sdkVersion: String = SDK_VERSION,
) {

    companion object {
        /** Default catalog URL. Mirrors the legacy SDK's hard-coded value. */
        val DEFAULT_CATALOG_URL: URI = URI("https://mbientlab.com/releases/metawear/info2.json")

        /**
         * SDK version string used to filter out catalog entries whose
         * `min-ios-version` exceeds this value. Updated when the SDK is
         * versioned for release; for now we mirror the legacy "3.2.0" floor.
         */
        const val SDK_VERSION: String = "3.2.0"

        /**
         * Move a fetcher temp download into a private staging directory,
         * giving it a stable, correctly-extensioned filename. Nordic's DFU
         * initiator dispatches on the file extension, and fetcher temp files
         * end in ".tmp" — the artifact must be renamed before hand-off. On
         * Android `java.io.tmpdir` is the app's cache directory, matching the
         * Swift staging under `FileManager.temporaryDirectory`.
         */
        internal fun stageDownload(tempFile: File, filename: String): File {
            try {
                val stagingDir = File(
                    System.getProperty("java.io.tmpdir"),
                    "com.mbientlab.metawear.firmware/${UUID.randomUUID()}",
                )
                if (!stagingDir.mkdirs() && !stagingDir.isDirectory) {
                    throw java.io.IOException("Could not create staging directory $stagingDir")
                }
                val dest = File(stagingDir, filename)
                if (!tempFile.renameTo(dest)) {
                    // rename can fail across filesystems; fall back to a copy.
                    tempFile.copyTo(dest, overwrite = true)
                    tempFile.delete()
                }
                return dest
            } catch (e: FirmwareException) {
                throw e
            } catch (e: Exception) {
                throw FirmwareException.CannotSaveFile(e.message ?: e.toString())
            }
        }
    }

    // ---- Catalog reads ----

    /**
     * Fetch every firmware on the catalog matching the given device
     * (hardwareRev, modelNumber) at the specified [buildFlavor]. Sorted
     * ascending by firmware version, filtered by SDK floor.
     */
    suspend fun availableBuilds(
        hardwareRev: String,
        modelNumber: String,
        buildFlavor: String = "vanilla",
    ): List<FirmwareBuild> {
        val json = fetchCatalog()
        return FirmwareCatalog.matchingBuilds(
            json = json,
            hardwareRev = hardwareRev,
            modelNumber = modelNumber,
            buildFlavor = buildFlavor,
            sdkVersion = sdkVersion,
        )
    }

    /**
     * Fetch the latest firmware on the catalog for this device, or throw
     * [FirmwareException.NoAvailableFirmware] if none match.
     */
    suspend fun latestBuild(
        hardwareRev: String,
        modelNumber: String,
        buildFlavor: String = "vanilla",
    ): FirmwareBuild {
        val builds = availableBuilds(hardwareRev, modelNumber, buildFlavor)
        return builds.lastOrNull() ?: throw FirmwareException.NoAvailableFirmware(
            "Catalog has no $buildFlavor build for hardware $hardwareRev model $modelNumber.",
        )
    }

    /**
     * Look up a specific firmware version. Returns `null` if the version
     * isn't on the catalog (rather than throwing — callers often want to
     * distinguish "not found" from "server unreachable").
     */
    suspend fun build(
        hardwareRev: String,
        modelNumber: String,
        firmwareRev: String,
        buildFlavor: String = "vanilla",
    ): FirmwareBuild? {
        val builds = availableBuilds(hardwareRev, modelNumber, buildFlavor)
        return builds.firstOrNull { it.firmwareRev.isMetaWearVersionEqualTo(firmwareRev) }
    }

    /**
     * Compare the device's current firmware against the latest build on the
     * catalog. Returns the latest build if it's newer than [currentRev], or
     * `null` if the device is already up to date.
     */
    suspend fun updateAvailable(
        currentRev: String,
        hardwareRev: String,
        modelNumber: String,
        buildFlavor: String = "vanilla",
    ): FirmwareBuild? {
        val latest = latestBuild(hardwareRev, modelNumber, buildFlavor)
        return if (currentRev.isMetaWearVersionLessThan(latest.firmwareRev)) latest else null
    }

    // ---- Firmware download ----

    /**
     * Download the firmware artifact for [build] to a temporary file and
     * return the local file. The caller (orchestrator) is responsible for
     * deleting the temp file after the DFU completes or fails.
     */
    suspend fun downloadFirmware(build: FirmwareBuild): File {
        // file:// builds can be used as-is; saves a copy.
        if (build.firmwareUrl.isFileUri) {
            return File(build.firmwareUrl)
        }
        val fetched = fetcher.downloadFile(build.firmwareUrl)
        if (fetched.statusCode !in 200..299) {
            fetched.file.delete()
            throw FirmwareException.BadServerResponse(fetched.statusCode)
        }
        return stageDownload(tempFile = fetched.file, filename = build.filename)
    }

    // ---- Internal ----

    private suspend fun fetchCatalog(): FirmwareCatalogJson {
        val fetched = fetcher.fetchData(catalogUrl)
        if (fetched.statusCode !in 200..299) {
            throw FirmwareException.BadServerResponse(fetched.statusCode)
        }
        return FirmwareCatalog.parse(fetched.body)
    }
}
