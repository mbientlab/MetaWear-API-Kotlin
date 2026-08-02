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

    // ---- Advertisement admission ----
    //
    // The original rule was name-prefix only, which silently dropped RENAMED
    // boards from discovery — a board called "bob" never appeared in the
    // nearby list on hosts that hadn't remembered it, even though it
    // advertised the MetaWear service UUID.

    private val metaWearService = "326A9000-85CB-9195-D9DD-464CFBBAE75A"

    @Test
    fun `admission accepts the default name`() {
        assertTrue(MetaWearScanner.isMetaWearAdvertisement("MetaWear", emptyList()))
    }

    @Test
    fun `admission accepts a renamed board by service UUID`() {
        // The quirk this rule exists to fix.
        assertTrue(MetaWearScanner.isMetaWearAdvertisement("bob", listOf(metaWearService)))
    }

    @Test
    fun `admission service UUID comparison is case-insensitive`() {
        assertTrue(MetaWearScanner.isMetaWearAdvertisement("bob", listOf(metaWearService.lowercase())))
    }

    @Test
    fun `admission rejects foreign peripherals`() {
        assertFalse(MetaWearScanner.isMetaWearAdvertisement("AirPods Pro", listOf("FE59", "180F")))
        assertFalse(MetaWearScanner.isMetaWearAdvertisement("", emptyList()))
    }

    @Test
    fun `admission rejects bootloader-mode boards`() {
        // Bootloader boards advertise the Nordic DFU service and the
        // "MetaBoot" name — the normal connect flow can't talk to them.
        assertFalse(
            MetaWearScanner.isMetaWearAdvertisement(
                "MetaBoot",
                listOf("00001530-1212-EFDE-1523-785FEABCD123"),
            ),
        )
    }

    @Test
    fun `renamed board advertising the service UUID is discovered`() = runTest {
        val scanner = makeScanner()
        scanner.startScan()
        runCurrent()

        scanFlow.tryEmit(
            ScanResult(
                identifier = "CC:DD",
                name = "bob",
                rssi = -55,
                serviceUUIDs = listOf(metaWearService),
            ),
        )
        runCurrent()

        assertEquals(setOf("CC:DD"), scanner.discoveredDevices.value.keys)
        assertEquals("bob", scanner.advertisedNames.value["CC:DD"])
    }

    // ---- Known-device promotion ----

    @Test
    fun `known device is promoted on discovery instead of minting a twin`() = runTest {
        val scanner = makeScanner()
        // Remembered board vended before it re-advertises…
        val known = scanner.deviceForKnownIdentifier("AA:BB")
        scanner.startScan()
        runCurrent()

        // …then seen on air: the SAME instance must be promoted. Two device
        // instances for one identifier means two transports racing over one
        // peripheral — the loser's connection state goes dark.
        scanFlow.tryEmit(advertisement("AA:BB", "MetaWear ABC"))
        runCurrent()

        assertSame(known, scanner.discoveredDevices.value["AA:BB"])
        assertEquals(1, devicesCreated)
    }

    // ---- noteAdvertisedName ----

    @Test
    fun `noteAdvertisedName overrides the cache until the next advertisement`() = runTest {
        val scanner = makeScanner()
        scanner.startScan()
        runCurrent()
        scanFlow.tryEmit(advertisement("AA:BB", "MetaWear ABC"))
        runCurrent()

        // Rename flow: a connected board doesn't advertise, so the app
        // injects the expected name for immediate UI feedback…
        scanner.noteAdvertisedName("AA:BB", "bob")
        assertEquals("bob", scanner.advertisedNames.value["AA:BB"])

        // …and the next real advertisement reconciles it with the truth.
        scanFlow.tryEmit(advertisement("AA:BB", "MetaWear ABC"))
        runCurrent()
        assertEquals("MetaWear ABC", scanner.advertisedNames.value["AA:BB"])
    }
}
