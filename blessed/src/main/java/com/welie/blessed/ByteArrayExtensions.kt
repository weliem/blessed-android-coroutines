package com.welie.blessed

import com.welie.blessed.ByteOrder.LITTLE_ENDIAN

enum class ByteOrder {
    LITTLE_ENDIAN,
    BIG_ENDIAN
}

fun Byte.asHexString(): String {
    var hexString = this.toUInt().toString(16).uppercase()
    if (this.toUInt() < 16u) hexString = "0$hexString"
    return hexString
}

fun ByteArray.formatHexBytes(seperator: String?): String {
    var resultString = ""
    for ((index, value) in this.iterator().withIndex()) {
        resultString += value.asHexString()
        if (seperator != null && index < (this.size - 1)) resultString += seperator
    }
    return resultString
}

fun ByteArray.asHexString() : String {
    return this.formatHexBytes(null)
}

/**
 * Convert an unsigned integer value to a two's-complement encoded
 * signed value.
 */
private fun unsignedToSigned(unsigned: UInt, size: UInt): Int {
    if (size > 24u) throw IllegalArgumentException("size too large")

    val signBit : UInt = (1u shl ((size - 1u).toInt()))
    if (unsigned and signBit != 0u) {
        // Convert to a negative value
        val nonsignedPart = (unsigned and (signBit - 1u))
        return  -1 * (signBit - nonsignedPart).toInt()
    }
    return unsigned.toInt()
}

fun ByteArray.getUInt(offset: UInt = 0u, length: UInt, order: ByteOrder) : UInt {
    val start = offset.toInt()
    val end = start + length.toInt() - 1
    val range : IntProgression = if (order == LITTLE_ENDIAN) IntProgression.fromClosedRange (end, start, -1) else start..end
    var result : UInt = 0u
    for (i in range) {
        if (i != range.first) {
            result = result shl 8
        }
        result += this[i].toUByte().toUInt()
    }
    return result
}

fun ByteArray.getUInt16(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : UInt {
    return getUInt(offset = offset, length = 2u, order = order)
}

fun ByteArray.getInt16(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : Int {
    return unsignedToSigned(getUInt(offset = offset, length = 2u, order = order), 16u)
}

fun ByteArray.getUInt24(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : UInt {
    return getUInt(offset = offset, length = 3u, order = order)
}

fun ByteArray.getInt24(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : Int {
    return unsignedToSigned(getUInt(offset = offset, length = 3u, order = order), 24u)
}

fun ByteArray.getUInt32(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : UInt {
    return getUInt(offset = offset, length = 4u, order = order)
}

fun ByteArray.getInt32(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : Int {
    return getUInt(offset = offset, length = 4u, order = order).toInt()
}
