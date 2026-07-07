package com.mbientlab.metawear.transport

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** UUID constants must match MWUUIDs.swift byte-for-byte. */
class UuidsTest {

    @Test
    fun metaWearServiceUuids() {
        assertEquals(UUID.fromString("326A9000-85CB-9195-D9DD-464CFBBAE75A"), Uuids.service)
        assertEquals(UUID.fromString("326A9001-85CB-9195-D9DD-464CFBBAE75A"), Uuids.command)
        assertEquals(UUID.fromString("326A9006-85CB-9195-D9DD-464CFBBAE75A"), Uuids.notify)
    }

    @Test
    fun shortUuidExpansion() {
        // 16-bit SIG ids expand onto the Bluetooth base UUID.
        assertEquals(UUID.fromString("00002A26-0000-1000-8000-00805F9B34FB"), Uuids.fromShort(0x2A26))
        assertEquals(UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB"), Uuids.disService)
        assertEquals(UUID.fromString("00002A24-0000-1000-8000-00805F9B34FB"), Uuids.modelNumber)
        assertEquals(UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB"), Uuids.batteryLevel)
    }

    @Test
    fun deviceInformationCharacteristics() {
        assertEquals(Uuids.fromShort(0x2A25), Uuids.serialNumber)
        assertEquals(Uuids.fromShort(0x2A27), Uuids.hardwareRevision)
        assertEquals(Uuids.fromShort(0x2A29), Uuids.manufacturerName)
        assertEquals(Uuids.fromShort(0x180F), Uuids.batteryService)
    }
}
