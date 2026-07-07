package com.mbientlab.metawear

import com.mbientlab.metawear.transport.MockBleTransport
import com.mbientlab.metawear.transport.ScanResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Scanner logic tests. Scan input is decoupled behind
 * [FakeScanTransport], so the filtering/caching rules are unit-testable
 * without a real BLE stack.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent
class MetaWearScannerTest {

    private val scanFlow = MutableSharedFlow<ScanResult>(extraBufferCapacity = 16)
    private var devicesCreated = 0

    private fun TestScope.makeScanner(): MetaWearScanner = MetaWearScanner(
        scanTransport = FakeScanTransport(scanFlow),
        scope = backgroundScope,
    ) { id ->
        devicesCreated++
        MetaWearDevice(id, MockBleTransport(), backgroundScope)
    }

    private fun advertisement(id: String, name: String?, rssi: Int = -60) =
        ScanResult(identifier = id, name = name, rssi = rssi)

    @Test
    fun `discovers MetaWear-prefixed devices`() = runTest {
        val scanner = makeScanner()
        scanner.startScan()
        runCurrent()

        scanFlow.tryEmit(advertisement("AA:BB", "MetaWear ABC", rssi = -48))
        runCurrent()

        assertTrue(scanner.isScanning.value)
        assertEquals(setOf("AA:BB"), scanner.discoveredDevices.value.keys)
        assertEquals("MetaWear ABC", scanner.advertisedNames.value["AA:BB"])
        assertEquals(-48, scanner.advertisementRssi.value["AA:BB"])
        assertEquals(1, devicesCreated)
    }

    @Test
    fun `non-MetaWear names are cached but not vended as devices`() = runTest {
        val scanner = makeScanner()
        scanner.startScan()
        runCurrent()

        // A renamed board no longer advertising the MetaWear prefix.
        scanFlow.tryEmit(advertisement("CC:DD", "Laura's Sensor"))
        runCurrent()

        assertTrue(scanner.discoveredDevices.value.isEmpty())
        assertEquals("Laura's Sensor", scanner.advertisedNames.value["CC:DD"])
        assertEquals(0, devicesCreated)
    }

    @Test
    fun `duplicate advertisements do not create duplicate devices`() = runTest {
        val scanner = makeScanner()
        scanner.startScan()
        runCurrent()

        scanFlow.tryEmit(advertisement("AA:BB", "MetaWear ABC"))
        scanFlow.tryEmit(advertisement("AA:BB", "MetaWear ABC", rssi = -70))
        runCurrent()

        assertEquals(1, scanner.discoveredDevices.value.size)
        assertEquals(1, devicesCreated)
        // RSSI still refreshes on every advertisement.
        assertEquals(-70, scanner.advertisementRssi.value["AA:BB"])
    }

    @Test
    fun `stopScan halts collection and preserves discoveries`() = runTest {
        val scanner = makeScanner()
        scanner.startScan()
        runCurrent()
        scanFlow.tryEmit(advertisement("AA:BB", "MetaWear ABC"))
        runCurrent()

        scanner.stopScan()
        assertFalse(scanner.isScanning.value)

        // Advertisements after stop are ignored…
        scanFlow.tryEmit(advertisement("EE:FF", "MetaWear DEF"))
        runCurrent()
        assertEquals(setOf("AA:BB"), scanner.discoveredDevices.value.keys)
        // …and already-discovered devices stay cached.
        assertEquals(1, devicesCreated)
    }

    @Test
    fun `clearAdvertisedName forgets the cached name`() = runTest {
        val scanner = makeScanner()
        scanner.startScan()
        runCurrent()
        scanFlow.tryEmit(advertisement("AA:BB", "MetaWear ABC"))
        runCurrent()

        scanner.clearAdvertisedName("AA:BB")
        assertNull(scanner.advertisedNames.value["AA:BB"])
    }

    @Test
    fun `deviceForKnownIdentifier returns a stable instance`() = runTest {
        val scanner = makeScanner()

        val remembered = scanner.deviceForKnownIdentifier("11:22")
        assertSame(remembered, scanner.deviceForKnownIdentifier("11:22"))
        assertEquals(1, devicesCreated)
        // Known-but-unseen devices don't pollute the on-air discovery set.
        assertTrue(scanner.discoveredDevices.value.isEmpty())
    }

    @Test
    fun `discovered device takes precedence over known device`() = runTest {
        val scanner = makeScanner()
        scanner.startScan()
        runCurrent()
        scanFlow.tryEmit(advertisement("AA:BB", "MetaWear ABC"))
        runCurrent()

        val discovered = scanner.discoveredDevices.value["AA:BB"]!!
        assertSame(discovered, scanner.deviceForKnownIdentifier("AA:BB"))
    }
}
