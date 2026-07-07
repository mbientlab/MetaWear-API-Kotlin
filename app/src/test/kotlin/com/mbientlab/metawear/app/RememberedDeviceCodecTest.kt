package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.data.RememberedDevice
import com.mbientlab.metawear.app.data.RememberedDeviceCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Codec + upsert semantics for the remembered-device store. */
class RememberedDeviceCodecTest {

    private val a = RememberedDevice(
        mac = "AA:BB:CC:DD:EE:FF",
        name = "MetaWear A",
        lastConnectedEpochMs = 1_000,
        serialNumber = "S1",
        firmwareRevision = "1.7.3",
        modelNumber = "8",
    )
    private val b = RememberedDevice(
        mac = "11:22:33:44:55:66",
        name = "MetaWear B",
        lastConnectedEpochMs = 2_000,
    )

    @Test
    fun `encode-decode round trip preserves fields`() {
        val decoded = RememberedDeviceCodec.decode(RememberedDeviceCodec.encode(listOf(a, b)))
        assertEquals(listOf(a, b), decoded)
    }

    @Test
    fun `empty string decodes to empty list`() {
        assertEquals(emptyList<RememberedDevice>(), RememberedDeviceCodec.decode(""))
    }

    @Test
    fun `upsert replaces by MAC, keeps known fields, sorts newest first`() {
        val reconnected = RememberedDevice(
            mac = a.mac,
            name = "Renamed",
            lastConnectedEpochMs = 5_000,
            // serial/firmware not read this time: nulls must not clobber.
            serialNumber = null,
            firmwareRevision = null,
            modelNumber = null,
        )
        val result = RememberedDeviceCodec.upsert(listOf(a, b), reconnected)
        assertEquals(2, result.size)
        val keeper = result.first()
        assertEquals("Renamed", keeper.name)
        assertEquals("S1", keeper.serialNumber)
        assertEquals("1.7.3", keeper.firmwareRevision)
        assertEquals(5_000, keeper.lastConnectedEpochMs)
        assertEquals(b.mac, result[1].mac)
    }

    @Test
    fun `upsert of unknown MAC appends`() {
        val c = RememberedDevice(mac = "77:88:99:AA:BB:CC", name = "C", lastConnectedEpochMs = 9_000)
        val result = RememberedDeviceCodec.upsert(listOf(a, b), c)
        assertEquals(listOf(c.mac, b.mac, a.mac), result.map { it.mac })
    }
}
