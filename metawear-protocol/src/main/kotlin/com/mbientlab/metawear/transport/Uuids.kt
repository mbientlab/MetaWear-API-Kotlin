package com.mbientlab.metawear.transport

import java.util.UUID

/**
 * GATT service / characteristic UUIDs used by the SDK.
 *
 * 16-bit Bluetooth SIG identifiers (e.g. `0x2A26`) are expanded onto the
 * standard Bluetooth base UUID via [fromShort], matching how every platform
 * stack resolves them.
 */
object Uuids {

    /** Expand a 16-bit SIG-assigned identifier onto the Bluetooth base UUID. */
    fun fromShort(short: Int): UUID =
        UUID.fromString("%08X-0000-1000-8000-00805F9B34FB".format(short))

    // ---- MetaWear custom service ----
    val service: UUID = UUID.fromString("326A9000-85CB-9195-D9DD-464CFBBAE75A")
    val command: UUID = UUID.fromString("326A9001-85CB-9195-D9DD-464CFBBAE75A")
    val notify: UUID = UUID.fromString("326A9006-85CB-9195-D9DD-464CFBBAE75A")

    // ---- Standard BLE Device Information Service (0x180A) ----
    val disService: UUID = fromShort(0x180A)
    val modelNumber: UUID = fromShort(0x2A24)
    val serialNumber: UUID = fromShort(0x2A25)
    val firmwareRevision: UUID = fromShort(0x2A26)
    val hardwareRevision: UUID = fromShort(0x2A27)
    val manufacturerName: UUID = fromShort(0x2A29)

    // ---- Standard Battery Service (0x180F) ----
    val batteryService: UUID = fromShort(0x180F)
    val batteryLevel: UUID = fromShort(0x2A19)
}
