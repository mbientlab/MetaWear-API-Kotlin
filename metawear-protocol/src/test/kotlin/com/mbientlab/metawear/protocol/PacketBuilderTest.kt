package com.mbientlab.metawear.protocol

import com.mbientlab.metawear.bytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Ported from MWModuleCommandTests.swift — PacketBuilderTests suite. */
class PacketBuilderTest {

    @Test fun commandPacket() =
        assertArrayEquals(bytes(0x03, 0x01, 0x01), Packet.command(Module.ACCELEROMETER, 0x01, 0x01))

    @Test fun readPacket_setsReadBit() =
        assertEquals(0x81, Packet.read(Module.ACCELEROMETER, 0x01)[1].toInt() and 0xFF) // 0x01 | 0x80

    @Test fun parseModuleId() =
        assertEquals(Module.ACCELEROMETER, Packet.module(bytes(0x03, 0x04, 0xAA, 0xBB)))

    @Test fun parseRegister_stripsReadBit() =
        assertEquals(0x04, Packet.register(bytes(0x03, 0x84, 0x00))) // register 0x04 with read bit
}
