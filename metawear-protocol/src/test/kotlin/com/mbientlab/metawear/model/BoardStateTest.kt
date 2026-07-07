package com.mbientlab.metawear.model

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.autoReply
import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.ProtocolRouter
import com.mbientlab.metawear.transport.MockBleTransport
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Board-state tests.
//
// Mirrors MetaWear-SDK-Cpp/test/test_metawearboard.py:
//   TestMetaWearBoard.test_module_info       — module.extra byte preservation
//   TestMetaWearBoardInitialize.test_reinitialize  — discovery-command set coverage
//   TestMetaWearBoardSerialize.test_serialize_motion_r (and deserialize)
//                                            — round-trip of post-init board state
//
// The C++ binary blob format is intentionally NOT reproduced: it's tied to
// C++ struct layout and never was a stable on-disk format. Instead we verify
// a JSON round-trip, which is what this SDK exposes. (ModuleInfo has no
// standalone codec, so the test round-trips it through a containing
// BoardState.)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BoardStateTest {

    // ---- ModuleInfo extra bytes ----

    // Default extra is empty and isPresent is based on implementation != 0xFF.
    @Test
    fun `default extra is empty`() {
        val info = ModuleInfo(module = Module.SWITCH, implementation = 0, revision = 0)
        assertTrue(info.extra.isEmpty())
        assertTrue(info.isPresent)
    }

    @Test
    fun `implementation FF is absent`() {
        val info = ModuleInfo(module = Module.HUMIDITY, implementation = 0xFF, revision = 0xFF)
        assertFalse(info.isPresent)
    }

    // Python test_module_info reference vectors for a MotionR board:
    //   DataProcessor: extra=[0x1c], revision=0
    //   Event:         extra=[0x1c], revision=0
    //   Logging:       extra=[0x08, 0x80, 0x2b, 0x00, 0x00], revision=2
    //   SensorFusion:  extra=[0x03, 0x00, 0x06, 0x00, 0x02, 0x00, 0x01, 0x00]
    @Test
    fun `known extra bytes motionR`() {
        val logging = ModuleInfo(
            module = Module.LOGGING,
            implementation = 0,
            revision = 2,
            extra = listOf(0x08, 0x80, 0x2B, 0x00, 0x00),
        )
        assertEquals(listOf(0x08, 0x80, 0x2B, 0x00, 0x00), logging.extra)
        assertEquals(2, logging.revision)

        val fusion = ModuleInfo(
            module = Module.SENSOR_FUSION,
            implementation = 0,
            revision = 0,
            extra = listOf(0x03, 0x00, 0x06, 0x00, 0x02, 0x00, 0x01, 0x00),
        )
        assertEquals(8, fusion.extra.size)
    }

    @Test
    fun `codable round trip preserves extra`() {
        val original = ModuleInfo(
            module = Module.DATA_PROCESSOR,
            implementation = 0,
            revision = 0,
            extra = listOf(0x1C),
        )
        // ModuleInfo has no standalone codec — round-trip through BoardState.
        val state = BoardState(
            deviceInformation = makeInfo(),
            modules = listOf(original),
            logReferenceDate = null,
        )
        val decoded = BoardState.decode(state.encode())
        assertEquals(original, decoded.modules.single())
    }

    // ---- Protocol-layer module discovery ----

    /**
     * Stand-in for each Python test_module_info case: inject a full response
     * `[module, 0x80, impl, rev, extra...]` and confirm [ModuleInfo] captures
     * every byte.
     */
    @Test
    fun `discoverModules captures extra bytes`() = runTest {
        val transport = MockBleTransport()
        val router = ProtocolRouter(transport, backgroundScope)
        router.start()
        runCurrent() // let the routing collector subscribe

        // Sparse — just the modules whose extra bytes we want to verify.
        // Everything else falls through to a minimal [module, 0x80, 0xFF, 0xFF].
        val scripted = mapOf(
            0x09 to bytes(0x09, 0x80, 0x00, 0x00, 0x1C),                                     // DataProcessor
            0x0B to bytes(0x0B, 0x80, 0x00, 0x02, 0x08, 0x80, 0x2B, 0x00, 0x00),             // Logging
            0x19 to bytes(0x19, 0x80, 0x00, 0x00, 0x03, 0x00, 0x06, 0x00, 0x02, 0x00, 0x01, 0x00), // Fusion
        )
        val replies = backgroundScope.autoReply(transport) { cmd ->
            if (cmd.size < 2 || (cmd[1].toInt() and 0xFF) != 0x80) return@autoReply null
            val moduleId = cmd[0].toInt() and 0xFF
            scripted[moduleId] ?: bytes(moduleId, 0x80, 0xFF, 0xFF)
        }

        val modules = router.discoverModules()
        replies.cancel()

        assertEquals(listOf(0x1C), modules[Module.DATA_PROCESSOR]?.extra)
        assertEquals(listOf(0x08, 0x80, 0x2B, 0x00, 0x00), modules[Module.LOGGING]?.extra)
        assertEquals(2, modules[Module.LOGGING]?.revision)
        assertEquals(8, modules[Module.SENSOR_FUSION]?.extra?.size)
        assertFalse(modules[Module.HUMIDITY]!!.isPresent) // 0xFF response
    }

    // ---- BoardState round-trip ----

    private fun makeInfo() = DeviceInformation(
        manufacturer = "MbientLab",
        modelNumber = "8", // MetaMotion S
        serialNumber = "CAFEBABE",
        firmwareRevision = "1.5.0",
        hardwareRevision = "r0.1",
    )

    private fun makeSampleState() = BoardState(
        deviceInformation = makeInfo(),
        modules = listOf(
            ModuleInfo(module = Module.SWITCH, implementation = 0, revision = 0),
            ModuleInfo(module = Module.ACCELEROMETER, implementation = 1, revision = 1),
            ModuleInfo(
                module = Module.LOGGING, implementation = 0, revision = 2,
                extra = listOf(0x08, 0x80, 0x2B, 0x00, 0x00),
            ),
            ModuleInfo(
                module = Module.SENSOR_FUSION, implementation = 0, revision = 0,
                extra = listOf(0x03, 0x00, 0x06, 0x00, 0x02, 0x00, 0x01, 0x00),
            ),
            ModuleInfo(module = Module.HUMIDITY, implementation = 0xFF, revision = 0xFF),
        ),
        logReferenceDate = Instant.fromEpochSeconds(1_700_000_000),
    )

    @Test
    fun `encode decode preserves all fields`() {
        val original = makeSampleState()
        val decoded = BoardState.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `encode produces deterministic JSON`() {
        val state = makeSampleState()
        assertEquals(state.encode(), state.encode())
    }

    @Test
    fun `schemaVersion is current`() {
        val json = makeSampleState().encode()
        assertTrue(json.contains("\"schemaVersion\":${BoardState.CURRENT_SCHEMA_VERSION}"))
    }

    @Test
    fun `decode rejects future schema version`() {
        // Hand-craft a JSON doc with schemaVersion = currentVersion + 1.
        val future = BoardState.CURRENT_SCHEMA_VERSION + 1
        val json = """
            {"deviceInformation":{"firmwareRevision":"x","hardwareRevision":"x",
            "manufacturer":"x","modelNumber":"x","serialNumber":"x"},
            "modules":[],"schemaVersion":$future}
        """.trimIndent()
        val error = runCatching { BoardState.decode(json) }.exceptionOrNull()
        assertTrue(error is MetaWearException.OperationFailed)
    }

    @Test
    fun `decode garbage data throws`() {
        val error = runCatching { BoardState.decode("DEADBEEF") }.exceptionOrNull()
        assertTrue(error is MetaWearException.OperationFailed)
    }

    // ---- Compatibility check ----

    @Test
    fun `isCompatible same firmware returns true`() {
        val state = makeSampleState()
        assertTrue(state.isCompatible(state.deviceInformation))
    }

    @Test
    fun `isCompatible different firmware returns false`() {
        val state = makeSampleState()
        val newer = state.deviceInformation.copy(firmwareRevision = "1.6.0")
        assertFalse(state.isCompatible(newer))
    }

    @Test
    fun `isCompatible different hardware returns false`() {
        val state = makeSampleState()
        val swapped = state.deviceInformation.copy(hardwareRevision = "0.5")
        assertFalse(state.isCompatible(swapped))
    }

    @Test
    fun `modulesByOpcode provides lookup`() {
        val state = makeSampleState()
        assertEquals(2, state.modulesByOpcode[Module.LOGGING]?.revision)
        assertFalse(state.modulesByOpcode[Module.HUMIDITY]!!.isPresent)
        assertNull(state.modulesByOpcode[Module.GYRO]) // not included in sample
    }

    // ---- MetaWearDevice capture / restore ----

    private fun makeModules() = listOf(
        ModuleInfo(module = Module.SWITCH, implementation = 0, revision = 0),
        ModuleInfo(module = Module.ACCELEROMETER, implementation = 1, revision = 1),
        ModuleInfo(
            module = Module.LOGGING, implementation = 0, revision = 2,
            extra = listOf(0x08, 0x80, 0x2B, 0x00, 0x00),
        ),
    )

    @Test
    fun `captureBoardState before connect returns null`() {
        val device = MetaWearDevice("AA:BB:CC:DD:EE:FF", MockBleTransport())
        assertNull(device.captureBoardState())
    }

    @Test
    fun `restoreBoardState populates fields`() {
        val device = MetaWearDevice("AA:BB:CC:DD:EE:FF", MockBleTransport())
        val state = BoardState(
            deviceInformation = makeInfo(),
            modules = makeModules(),
            logReferenceDate = Instant.fromEpochSeconds(1_700_000_000),
        )
        device.restoreBoardState(state)

        assertEquals(makeInfo(), device.deviceInfo)
        assertEquals(listOf(0x08, 0x80, 0x2B, 0x00, 0x00), device.modules[Module.LOGGING]?.extra)
        assertEquals(1, device.modules[Module.ACCELEROMETER]?.revision)
    }

    @Test
    fun `capture after restore round trips state`() {
        val device = MetaWearDevice("AA:BB:CC:DD:EE:FF", MockBleTransport())
        val state = BoardState(
            deviceInformation = makeInfo(),
            modules = makeModules(),
            logReferenceDate = null,
        )
        device.restoreBoardState(state)
        val captured = device.captureBoardState()

        assertEquals(state.deviceInformation, captured?.deviceInformation)
        assertEquals(listOf(0x08, 0x80, 0x2B, 0x00, 0x00), captured?.modulesByOpcode?.get(Module.LOGGING)?.extra)
        assertNull(captured?.logReferenceDate)
    }
}
