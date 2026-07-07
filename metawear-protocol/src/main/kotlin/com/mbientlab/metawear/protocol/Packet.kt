package com.mbientlab.metawear.protocol

/**
 * Builds and inspects raw MetaWear command / notification packets.
 * Port of `MWPacket` (Swift). All multi-byte payloads are little-endian.
 *
 * Payload bytes are passed as `Int` (0..255) and narrowed internally, which
 * avoids the `byteArrayOf(0x80)` "literal does not fit in Byte" friction at
 * every call site.
 */
object Packet {

    /** Standard write/notify command: `[module, register, payload…]`. */
    fun command(module: Module, register: Int, vararg payload: Int): ByteArray {
        val out = ByteArray(2 + payload.size)
        out[0] = module.value.toByte()
        out[1] = register.toByte()
        for (i in payload.indices) out[2 + i] = payload[i].toByte()
        return out
    }

    /** Command with a pre-built payload byte array. */
    fun command(module: Module, register: Int, payload: ByteArray): ByteArray =
        byteArrayOf(module.value.toByte(), register.toByte()) + payload

    /** One-shot read: sets bit 7 of the register byte. */
    fun read(module: Module, register: Int, vararg payload: Int): ByteArray {
        val out = ByteArray(2 + payload.size)
        out[0] = module.value.toByte()
        out[1] = (register or 0x80).toByte()
        for (i in payload.indices) out[2 + i] = payload[i].toByte()
        return out
    }

    /** Parse the module id from an incoming notification. */
    fun module(data: ByteArray): Module? =
        data.firstOrNull()?.let { Module.from(it.toInt() and 0xFF) }

    /** Parse the register byte, stripping the read bit if set. */
    fun register(data: ByteArray): Int? =
        if (data.size >= 2) data[1].toInt() and 0x3F else null
}
