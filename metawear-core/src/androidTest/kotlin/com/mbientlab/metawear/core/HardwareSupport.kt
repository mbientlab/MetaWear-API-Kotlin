package com.mbientlab.metawear.core

import androidx.test.platform.app.InstrumentationRegistry
import com.mbientlab.metawear.DeviceState
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.MetaWearScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue

/**
 * Shared plumbing for the hardware smoke suite. Port of the Swift
 * `Tests/MetaWearHardwareTests/HardwareSupport.swift`.
 *
 * - Scans **once** per instrumentation run and caches the result, so the whole
 *   suite pays a single scan timeout.
 * - When no board answers, every test calls [assumeTrue] and the suite reports
 *   as *skipped*, not failed — safe to keep in CI without a bench board.
 * - With several boards in range, the scan gathers candidates for a few
 *   seconds and picks the lowest MAC — arbitrary but stable per bench, so
 *   every run exercises the same board (Swift parity).
 *
 * All timeouts are real wall-clock time (`runBlocking`, not `runTest`): these
 * tests drive an actual radio.
 */
object HardwareSupport {

    private const val SCAN_TIMEOUT_MS = 10_000L
    private const val GATHER_WINDOW_MS = 4_000L
    private const val CONNECT_TIMEOUT_MS = 30_000L

    private var scanAttempted = false
    private var cachedDevice: MetaWearDevice? = null

    /** Kept alive so advertisement RSSI/name caches survive across tests. */
    private var cachedScanner: MetaWearScanner? = null

    /**
     * Return the cached nearby MetaWear, scanning on first call. Skips the
     * calling test (JUnit assumption) when no board is in range.
     */
    fun nearbyDevice(): MetaWearDevice {
        if (!scanAttempted) {
            scanAttempted = true
            cachedDevice = runBlocking { scanForBoard() }
        }
        assumeTrue(
            "No MetaWear board found within ${SCAN_TIMEOUT_MS / 1000} s — is one charged and nearby? Skipping.",
            cachedDevice != null,
        )
        return cachedDevice!!
    }

    /** Last advertisement RSSI observed for [identifier] during the scan, in dBm. */
    fun advertisedRssi(identifier: String): Int? =
        cachedScanner?.advertisementRssi?.value?.get(identifier)

    private suspend fun scanForBoard(): MetaWearDevice? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scanner = AndroidMetaWear.scanner(context)
        cachedScanner = scanner
        scanner.startScan()
        try {
            withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                scanner.discoveredDevices.first { it.isNotEmpty() }
            } ?: return null
            // Let slower boards surface before choosing, so multi-board
            // benches make a stable pick instead of "first to answer".
            delay(GATHER_WINDOW_MS)
            return scanner.discoveredDevices.value.entries
                .minByOrNull { it.key }!!
                .value
        } finally {
            scanner.stopScan()
        }
    }

    /**
     * Find a board, connect, run [block], then always disconnect — even when
     * [block] throws. The receiver scope is the enclosing `runBlocking`, so
     * tests can `launch` collectors directly.
     */
    fun withConnectedDevice(block: suspend CoroutineScope.(MetaWearDevice) -> Unit) {
        val device = nearbyDevice()
        runBlocking {
            // A previously-failed test may have left the cached device connected.
            if (device.state.value != DeviceState.Disconnected) {
                runCatching { device.disconnect() }
            }
            withTimeout(CONNECT_TIMEOUT_MS) { device.connect() }
            try {
                block(device)
            } finally {
                // Give the radio a beat to flush trailing write-without-response
                // packets (e.g. LED stop) before tearing the link down (Swift parity).
                delay(100)
                runCatching { device.disconnect() }
            }
        }
    }
}
