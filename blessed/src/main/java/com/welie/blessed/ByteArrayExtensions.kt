package com.welie.blessed


import java.nio.ByteOrder
import java.nio.ByteOrder.LITTLE_ENDIAN
import kotlin.math.pow

fun Byte.asHexString(): String {
    var hexString = this.toUByte().toString(16).uppercase()
    if (this.toUInt() < 16u) hexString = "0$hexString"
    return hexString
}

fun ByteArray.formatHexBytes(separator: String?): String {
    var resultString = ""
    for ((index, value) in this.iterator().withIndex()) {
        resultString += value.asHexString()
        if (separator != null && index < (this.size - 1)) resultString += separator
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

/**
 * Convert an unsigned long value to a two's-complement encoded
 * signed value.
 */
private fun unsignedToSigned(unsigned: ULong, size: ULong): Long {
    if (size > 56u) throw IllegalArgumentException("size too large")

    val signBit : ULong = (1uL shl ((size - 1uL).toInt()))
    if (unsigned and signBit != 0uL) {
        // Convert to a negative value
        val nonsignedPart = (unsigned and (signBit - 1u))
        return  -1 * (signBit - nonsignedPart).toLong()
    }
    return unsigned.toLong()
}

/**
 * Convert an integer into the signed bits of a given length.
 */
private fun intToSignedBits(value: Int, size: Int): Int {
    var i = value
    if (i < 0) {
        i = (1 shl size - 1) + (i and (1 shl size - 1) - 1)
    }
    return i
}

fun ByteArray.getULong(offset: UInt = 0u, length: UInt, order: ByteOrder) : ULong {
    if (length == 0u) throw IllegalArgumentException("length must not be zero")

    val start = offset.toInt()
    val end = start + length.toInt() - 1
    val range : IntProgression = if (order == LITTLE_ENDIAN) IntProgression.fromClosedRange (end, start, -1) else start..end
    var result : ULong = 0u
    for (i in range) {
        if (i != range.first) {
            result = result shl 8
        }
        result += this[i].toUByte().toUInt()
    }
    return result
}

fun ByteArray.getUInt16(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : UInt {
    return getULong(offset = offset, length = 2u, order = order).toUInt()
}

fun ByteArray.getInt16(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : Int {
    return unsignedToSigned(getULong(offset = offset, length = 2u, order = order).toUInt(), 16u)
}

fun ByteArray.getUInt24(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : UInt {
    return getULong(offset = offset, length = 3u, order = order).toUInt()
}

fun ByteArray.getInt24(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : Int {
    return unsignedToSigned(getULong(offset = offset, length = 3u, order = order).toUInt(), 24u)
}

fun ByteArray.getUInt32(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : UInt {
    return getULong(offset = offset, length = 4u, order = order).toUInt()
}

fun ByteArray.getInt32(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : Int {
    return getULong(offset = offset, length = 4u, order = order).toInt()
}

fun ByteArray.getUInt48(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : ULong {
    return getULong(offset = offset, length = 6u, order = order)
}

fun ByteArray.getInt48(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : Long {
    return unsignedToSigned(getULong(offset = offset, length = 6u, order = order), 48uL)
}

fun ByteArray.geUInt64(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : ULong {
    return getULong(offset = offset, length = 8u, order = order)
}

fun ByteArray.geInt64(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : Long {
    return getULong(offset = offset, length = 8u, order = order).toLong()
}

fun ByteArray.getSFloat(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : Double {
    val uint16 = getUInt16(offset = offset, order = order)
    val mantissa = unsignedToSigned( uint16 and 0x0FFFu, 12u)
    val exponent = unsignedToSigned(uint16 shr 12, 4u)

    return mantissa.toDouble() * 10.0.pow(exponent)
}

fun ByteArray.getFloat(offset : UInt = 0u, order: ByteOrder = LITTLE_ENDIAN) : Double {
    val uint32 = getUInt32(offset = offset, order = order)
    val mantissa = unsignedToSigned( uint32 and 0x00FFFFFFu, 24u)
    val exponent = unsignedToSigned(uint32 shr 24, 8u)

    return mantissa.toDouble() * 10.0.pow(exponent)
}

fun byteArrayOf(value: UInt, length: UInt, order : ByteOrder = LITTLE_ENDIAN) : ByteArray {
    val result = ByteArray(size =  length.toInt())
    val end = length.toInt() - 1
    val range : IntProgression = if (order == LITTLE_ENDIAN) 0..end else IntProgression.fromClosedRange (end, 0, -1)
    for (i in range) {
        if (i == range.first) {
            result[i] = value.toByte()
        } else {
            result[i] = (value shr (i * 8)).toByte()
        }
    }
    return result
}

fun byteArrayOf(value: Int, length: UInt, order : ByteOrder) : ByteArray {
    return byteArrayOf(value.toUInt(), length, order)
}

fun byteArrayOf(value: Double, length: UInt, precision: Int, order : ByteOrder = LITTLE_ENDIAN) : ByteArray {
    val result = ByteArray(size =  length.toInt())
    val mantissa = (value * 10.0.pow(precision)).toInt()
    val exponent = -precision

    if (length == 2u) {
        val localMantissa = intToSignedBits(mantissa, 12)
        val localExponent = intToSignedBits(exponent, 4)
        var index = 0
        if (order == LITTLE_ENDIAN) {
            result[index++] = (localMantissa and 0xFF).toByte()
            result[index] = (localMantissa shr 8 and 0x0F).toByte()
            result[index] = (result[index] + (localExponent and 0x0F shl 4)).toByte()
        } else {
            result[index] = (localMantissa shr 8 and 0x0F).toByte()
            result[index++] = (result[index] + (localExponent and 0x0F shl 4)).toByte()
            result[index] = (localMantissa and 0xFF).toByte()
        }
    } else if(length == 4u) {
        val localMantissa = intToSignedBits(mantissa, 24)
        val localExponent = intToSignedBits(exponent, 8)
        var index = 0
        if (order == LITTLE_ENDIAN) {
            result[index++] = (localMantissa and 0xFF).toByte()
            result[index++] = (localMantissa shr 8 and 0xFF).toByte()
            result[index++] = (localMantissa shr 16 and 0xFF).toByte()
            result[index] = (result[index] + (localExponent and 0xFF)).toByte()
        } else {
            result[index++] = (result[index] + (localExponent and 0xFF)).toByte()
            result[index++] = (localMantissa shr 16 and 0xFF).toByte()
            result[index++] = (localMantissa shr 8 and 0xFF).toByte()
            result[index] = (localMantissa and 0xFF).toByte()
        }
    }
    return result
}

fun byteArrayOf(hexString: String): ByteArray {
    val result = ByteArray(hexString.length / 2)
    for (i in result.indices) {
        val index = i * 2
        result[i] = hexString.substring(index, index + 2).toInt(16).toByte()
    }
    return result
}

fun mergeArrays(vararg arrays: ByteArray): ByteArray {
    var size = 0
    for (array in arrays) {
        size += array.size
    }
    val merged = ByteArray(size)
    var index = 0
    for (array in arrays) {
        array.copyInto(merged, index, 0, array.size)
        index += array.size
    }
    return merged
}