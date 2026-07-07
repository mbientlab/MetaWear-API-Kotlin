package com.mbientlab.metawear

import com.mbientlab.metawear.transport.BleTransport
import com.mbientlab.metawear.transport.MockBleTransport
import com.mbientlab.metawear.transport.ScanResult
import com.mbientlab.metawear.transport.Uuids
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Shared helpers for the device-level test suites.

/** Modules the stub board reports present: accel, gyro, baro, magnetometer, fusion. */
val DEFAULT_PRESENT_MODULES = setOf(0x03, 0x13, 0x12, 0x15, 0x19)

/**
 * Build a MockBleTransport pre-loaded with the DIS characteristic responses
 * `MetaWearDevice.initialize()` reads during connect.
 */
fun makeConnectableTransport(): MockBleTransport = MockBleTransport().apply {
    setReadResponse(Uuids.manufacturerName, "MbientLab".toByteArray())
    setReadResponse(Uuids.modelNumber, "MetaMotionS".toByteArray())
    setReadResponse(Uuids.serialNumber, "A0B1C2".toByteArray())
    setReadResponse(Uuids.firmwareRevision, "1.5.0".toByteArray())
    setReadResponse(Uuids.hardwareRevision, "0.4".toByteArray())
}

/**
 * Poll [MockBleTransport.writtenData] and answer each **new** command via
 * [reply] (return `null` to ignore). Index-based, so repeated identical
 * commands (e.g. a battery poll) each get a response rather than being
 * deduplicated by byte content.
 */
fun CoroutineScope.autoReply(
    transport: MockBleTransport,
    reply: (ByteArray) -> ByteArray?,
): Job = launch {
    var processed = 0
    while (isActive) {
        delay(3)
        val written = transport.writtenCommands
        while (processed < written.size) {
            val cmd = written[processed]
            processed++
            reply(cmd)?.let { transport.inject(it, Uuids.notify) }
        }
    }
}

/**
 * Auto-respond to everything `connect()` sends: module-info reads get
 * `[module, 0x80, impl, rev]` stubs (impl per [presentModules]), and the log
 * time-reference read (`[0x0B, 0x84]`) gets a zero-tick reply so connect
 * completes without eating the read timeout.
 */
fun CoroutineScope.autoReplyModuleDiscovery(
    transport: MockBleTransport,
    presentModules: Set<Int> = DEFAULT_PRESENT_MODULES,
): Job = autoReply(transport) { cmd ->
    if (cmd.size < 2) return@autoReply null
    val regByte = cmd[1].toInt() and 0xFF
    if (regByte and 0x80 == 0) return@autoReply null // only reads
    val moduleId = cmd[0].toInt() and 0xFF
    if (regByte == 0x80) {
        // Module-info read [module, 0x80] → [module, 0x80, impl, rev]
        val impl = if (moduleId in presentModules) 0x01 else 0xFF
        bytes(moduleId, 0x80, impl, 0x00)
    } else {
        // Any other read (e.g. logging tick [0x0B, 0x84]) — echo the header
        // with a 4-byte zero payload.
        bytes(moduleId, regByte, 0x00, 0x00, 0x00, 0x00)
    }
}

/** Same shape, but every module reports absent (impl = 0xFF). */
fun CoroutineScope.autoReplyAllAbsent(transport: MockBleTransport): Job =
    autoReplyModuleDiscovery(transport, presentModules = emptySet())

/** A transport whose scan flow is test-controlled; everything else delegates to a mock. */
class FakeScanTransport(
    private val scanFlow: Flow<ScanResult>,
    delegate: BleTransport = MockBleTransport(),
) : BleTransport by delegate {
    override fun scan(services: List<UUID>?): Flow<ScanResult> = scanFlow
}
