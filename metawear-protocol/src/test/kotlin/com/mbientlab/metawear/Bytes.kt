package com.mbientlab.metawear

/**
 * Build a [ByteArray] from `Int` literals 0..255.
 *
 * `byteArrayOf(0x80)` does not compile because `0x80` (128) is out of signed
 * `Byte` range; the wire vectors in these tests are full of such bytes, so this
 * helper narrows each `Int` for us.
 */
internal fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

/** Little-endian IEEE-754 float bytes, for building sensor-fusion test packets. */
internal fun floatLE(value: Float): ByteArray {
    val bits = value.toRawBits()
    return bytes(bits and 0xFF, (bits shr 8) and 0xFF, (bits shr 16) and 0xFF, (bits shr 24) and 0xFF)
}
