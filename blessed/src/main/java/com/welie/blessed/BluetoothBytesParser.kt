/*
 *   Copyright (c) 2023 Martijn van Welie
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in all
 *   copies or substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *   SOFTWARE.
 *
 */
package com.welie.blessed

import java.nio.ByteOrder
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.math.pow

class BluetoothBytesParser (
    var value: ByteArray,
    var offset: Int = 0,
    var byteOrder: ByteOrder = LITTLE_ENDIAN
) {
    /**
     * Create a BluetoothBytesParser that does not contain a byte array and sets the byteOrder.
     */
    constructor(byteOrder: ByteOrder) : this(ByteArray(0), 0, byteOrder)

    /**
     * Create a BluetoothBytesParser and set the byte array and byteOrder.
     *
     * @param value     byte array
     * @param byteOrder the byte order to use (either LITTLE_ENDIAN or BIG_ENDIAN)
     */

    /**
     * Create a BluetoothBytesParser that does not contain a byte array and sets the byteOrder to LITTLE_ENDIAN.
     */
    constructor(value: ByteArray, byteOrder: ByteOrder) : this(value, 0, byteOrder)

    /**
     * Return an Integer value of the specified type. This operation will automatically advance the internal offset to the next position.
     *
     * @param formatType The format type used to interpret the byte(s) value
     * @return An Integer object or null in case the byte array was not valid
     */
    fun getIntValue(formatType: Int): Int {
        val result = getIntValue(formatType, offset, byteOrder)
        offset += getTypeLen(formatType)
        return result
    }

    /**
     * Return an Integer value of the specified type and specified byte order. This operation will automatically advance the internal offset to the next position.
     *
     * @param formatType the format type used to interpret the byte(s) value
     * @return an Integer object or null in case the byte array was not valid
     */
    fun getIntValue(formatType: Int, byteOrder: ByteOrder): Int {
        val result = getIntValue(formatType, offset, byteOrder)
        offset += getTypeLen(formatType)
        return result
    }

    /**
     * Return a Long value. This operation will automatically advance the internal offset to the next position.
     *
     * @return an Long object or null in case the byte array was not valid
     */
    val longValue: Long
        get() = getLongValue(byteOrder)

    /**
     * Return a Long value using the specified byte order. This operation will automatically advance the internal offset to the next position.
     *
     * @return an Long object or null in case the byte array was not valid
     */
    fun getLongValue(byteOrder: ByteOrder): Long {
        val result = getLongValue(offset, byteOrder)
        offset += 8
        return result
    }

    /**
     * Return a Long value using the specified byte order and offset position. This operation will not advance the internal offset to the next position.
     *
     * @return an Long object or null in case the byte array was not valid
     */
    fun getLongValue(offset: Int, byteOrder: ByteOrder): Long {
        var result = 0x00FFL
        if (byteOrder == LITTLE_ENDIAN) {
            result = result and value[offset + 7].toLong()
            for (i in 6 downTo 0) {
                result = result shl 8
                result += 0x00FFL and value[i + offset].toLong()
            }
        } else {
            result = result and value[offset].toLong()
            for (i in 1..7) {
                result = result shl 8
                result += 0x00FFL and value[i + offset].toLong()
            }
        }
        return result
    }

    /**
     * Return an Integer value of the specified type. This operation will not advance the internal offset to the next position.
     *
     *
     * The formatType parameter determines how the byte array
     * is to be interpreted. For example, settting formatType to
     * [.FORMAT_UINT16] specifies that the first two bytes of the
     * byte array at the given offset are interpreted to generate the
     * return value.
     *
     * @param formatType The format type used to interpret the byte array.
     * @param offset     Offset at which the integer value can be found.
     * @param byteOrder  the byte order, either LITTLE_ENDIAN or BIG_ENDIAN
     * @return Cached value of the byte array or null of offset exceeds value size.
     */
    fun getIntValue(formatType: Int, offset: Int, byteOrder: ByteOrder): Int {
        require(offset + getTypeLen(formatType) <= value.size)

        when (formatType) {
            FORMAT_UINT8 -> return unsignedByteToInt(value[offset])
            FORMAT_UINT16 -> return if (byteOrder == LITTLE_ENDIAN) unsignedBytesToInt(value[offset], value[offset + 1]) else unsignedBytesToInt(
                value[offset + 1], value[offset]
            )
            FORMAT_UINT24 -> return if (byteOrder == LITTLE_ENDIAN) unsignedBytesToInt(
                value[offset], value[offset + 1],
                value[offset + 2], 0.toByte()
            ) else unsignedBytesToInt(
                value[offset + 2], value[offset + 1], 
                value[offset], 0.toByte()
            )
            FORMAT_UINT32 -> return if (byteOrder == LITTLE_ENDIAN) unsignedBytesToInt(
                value[offset], value[offset + 1],
                value[offset + 2], value[offset + 3]
            ) else unsignedBytesToInt(
                value[offset + 3], value[offset + 2],
                value[offset + 1], value[offset]
            )
            FORMAT_SINT8 -> return unsignedToSigned(unsignedByteToInt(value[offset]), 8)
            FORMAT_SINT16 -> return if (byteOrder == LITTLE_ENDIAN) unsignedToSigned(
                unsignedBytesToInt(
                    value[offset],
                    value[offset + 1]
                ), 16
            ) else unsignedToSigned(
                unsignedBytesToInt(
                    value[offset + 1],
                    value[offset]
                ), 16
            )
            FORMAT_SINT24 -> return if (byteOrder == LITTLE_ENDIAN) unsignedToSigned(
                unsignedBytesToInt(
                    value[offset],
                    value[offset + 1], value[offset + 2], 0.toByte()
                ), 24
            ) else unsignedToSigned(
                unsignedBytesToInt(
                    value[offset + 2], 
                    value[offset + 1], value[offset], 0.toByte()
                ), 24
            )
            FORMAT_SINT32 -> return if (byteOrder == LITTLE_ENDIAN) unsignedToSigned(
                unsignedBytesToInt(
                    value[offset],
                    value[offset + 1], value[offset + 2], value[offset + 3]
                ), 32
            ) else unsignedToSigned(
                unsignedBytesToInt(
                    value[offset + 3],
                    value[offset + 2], value[offset + 1], value[offset]
                ), 32
            )
        }
        throw IllegalArgumentException()
    }

    /**
     * Return a float value of the specified format. This operation will automatically advance the internal offset to the next position.
     *
     * @param formatType The format type used to interpret the byte array
     * @return The float value at the position of the internal offset
     */
    fun getFloatValue(formatType: Int): Float {
        val result = getFloatValue(formatType, offset, byteOrder)
        offset += getTypeLen(formatType)
        return result
    }

    /**
     * Return a float value of the specified format and byte order. This operation will automatically advance the internal offset to the next position.
     *
     * @param formatType The format type used to interpret the byte array
     * @param byteOrder  the byte order, either LITTLE_ENDIAN or BIG_ENDIAN
     * @return The float value at the position of the internal offset
     */
    fun getFloatValue(formatType: Int, byteOrder: ByteOrder): Float {
        val result = getFloatValue(formatType, offset, byteOrder)
        offset += getTypeLen(formatType)
        return result
    }

    /**
     * Return a float value of the specified format, offset and byte order. This operation will not advance the internal offset to the next position.
     *
     * @param formatType The format type used to interpret the byte array
     * @param byteOrder  the byte order, either LITTLE_ENDIAN or BIG_ENDIAN
     * @return The float value at the position of the internal offset
     */
    fun getFloatValue(formatType: Int, offset: Int, byteOrder: ByteOrder): Float {
        require(offset + getTypeLen(formatType) <= value.size)

        when (formatType) {
            FORMAT_SFLOAT -> return if (byteOrder == LITTLE_ENDIAN) bytesToFloat(value[offset], value[offset + 1]) else bytesToFloat(
                value[offset + 1], value[offset]
            )
            FORMAT_FLOAT -> return if (byteOrder == LITTLE_ENDIAN) bytesToFloat(
                value[offset], value[offset + 1],
                value[offset + 2], value[offset + 3]
            ) else bytesToFloat(
                value[offset + 3], value[offset + 2],
                value[offset + 1], value[offset]
            )
        }
        throw IllegalArgumentException()
    }

    /**
     * Return a String from this byte array. This operation will not advance the internal offset to the next position.
     *
     * @return String value representated by the byte array
     */
    val stringValue: String
        get() = getStringValue(offset)

    /**
     * Return a String from this byte array. This operation will not advance the internal offset to the next position.
     *
     * @param offset Offset at which the string value can be found.
     * @return String value represented by the byte array
     */
    fun getStringValue(offset: Int): String {
        require(offset < value.size)

        // Copy all bytes
        val strBytes = ByteArray(value.size - offset)
        for (i in 0 until value.size - offset) strBytes[i] = value[offset + i]

        // Get rid of trailing zero/space bytes
        var j = strBytes.size
        while (j > 0 && (strBytes[j - 1].toInt() == 0 || strBytes[j - 1].toInt() == 0x20)) j--

        // Convert to string
        return String(strBytes, 0, j, StandardCharsets.ISO_8859_1)
    }

    /**
     * Return a the date represented by the byte array.
     *
     *
     * The byte array must conform to the DateTime specification (year, month, day, hour, min, sec)
     *
     * @return the Date represented by the byte array
     */
    val dateTime: Date
        get() {
            val result = getDateTime(offset)
            offset += 7
            return result
        }

    /**
     * Get Date from characteristic with offset
     *
     * @param offset Offset of value
     * @return Parsed date from value
     */
    fun getDateTime(offset: Int): Date {
        // DateTime is always in little endian
        var localOffset = offset
        val year = getIntValue(FORMAT_UINT16, localOffset, LITTLE_ENDIAN)
        localOffset += getTypeLen(FORMAT_UINT16)
        val month = getIntValue(FORMAT_UINT8, localOffset, LITTLE_ENDIAN)
        localOffset += getTypeLen(FORMAT_UINT8)
        val day = getIntValue(FORMAT_UINT8, localOffset, LITTLE_ENDIAN)
        localOffset += getTypeLen(FORMAT_UINT8)
        val hour = getIntValue(FORMAT_UINT8, localOffset, LITTLE_ENDIAN)
        localOffset += getTypeLen(FORMAT_UINT8)
        val min = getIntValue(FORMAT_UINT8, localOffset, LITTLE_ENDIAN)
        localOffset += getTypeLen(FORMAT_UINT8)
        val sec = getIntValue(FORMAT_UINT8, localOffset, LITTLE_ENDIAN)
        val calendar = GregorianCalendar(year, month - 1, day, hour, min, sec)
        return calendar.time
    }

    /*
     * Read bytes and return the ByteArray of the length passed in.  This will increment the offset
     *
     * @return The DateTime read from the bytes. This will cause an exception if bytes run past end. Will return 0 epoch if unparsable
     */
    fun getByteArray(length: Int): ByteArray {
        val array = Arrays.copyOfRange(value, offset, offset + length)
        offset += length
        return array
    }

    /**
     * Set the locally stored value of this byte array
     *
     * @param value      New value for this byte array
     * @param formatType Integer format type used to transform the value parameter
     * @param offset     Offset at which the value should be placed
     * @return true if the locally stored value has been set
     */
    fun setIntValue(value: Int, formatType: Int, offset: Int): Boolean {
        prepareArray(offset + getTypeLen(formatType))
        var index = offset
        var tempValue = value
        when (formatType) {
            FORMAT_SINT8 -> {
                this.value[index] = ((intToSignedBits(tempValue, 8)) and 0xFF).toByte()
            }
            FORMAT_UINT8 -> this.value[index] = (tempValue and 0xFF).toByte()
            FORMAT_SINT16 -> {
                tempValue = intToSignedBits(value, 16)
                if (byteOrder == LITTLE_ENDIAN) {
                    this.value[index++] = (tempValue and 0xFF).toByte()
                    this.value[index] = (tempValue shr 8 and 0xFF).toByte()
                } else {
                    this.value[index++] = (tempValue shr 8 and 0xFF).toByte()
                    this.value[index] = (tempValue and 0xFF).toByte()
                }
            }
            FORMAT_UINT16 -> if (byteOrder == LITTLE_ENDIAN) {
                this.value[index++] = (tempValue and 0xFF).toByte()
                this.value[index] = (tempValue shr 8 and 0xFF).toByte()
            } else {
                this.value[index++] = (tempValue shr 8 and 0xFF).toByte()
                this.value[index] = (tempValue and 0xFF).toByte()
            }
            FORMAT_SINT24 -> {
                tempValue = intToSignedBits(value, 24)
                if (byteOrder == LITTLE_ENDIAN) {
                    this.value[index++] = (tempValue and 0xFF).toByte()
                    this.value[index++] = (tempValue shr 8 and 0xFF).toByte()
                    this.value[index] = (tempValue shr 16 and 0xFF).toByte()
                } else {
                    this.value[index++] = (tempValue shr 16 and 0xFF).toByte()
                    this.value[index++] = (tempValue shr 8 and 0xFF).toByte()
                    this.value[index] = (tempValue and 0xFF).toByte()
                }
            }
            FORMAT_UINT24 -> if (byteOrder == LITTLE_ENDIAN) {
                this.value[index++] = (tempValue and 0xFF).toByte()
                this.value[index++] = (tempValue shr 8 and 0xFF).toByte()
                this.value[index] = (tempValue shr 16 and 0xFF).toByte()
            } else {
                this.value[index++] = (tempValue shr 16 and 0xFF).toByte()
                this.value[index++] = (tempValue shr 8 and 0xFF).toByte()
                this.value[index] = (tempValue and 0xFF).toByte()
            }
            FORMAT_SINT32 -> {
                tempValue = intToSignedBits(tempValue, 32)
                if (byteOrder == LITTLE_ENDIAN) {
                    this.value[index++] = (tempValue and 0xFF).toByte()
                    this.value[index++] = (tempValue shr 8 and 0xFF).toByte()
                    this.value[index++] = (tempValue shr 16 and 0xFF).toByte()
                    this.value[index] = (tempValue shr 24 and 0xFF).toByte()
                } else {
                    this.value[index++] = (tempValue shr 24 and 0xFF).toByte()
                    this.value[index++] = (tempValue shr 16 and 0xFF).toByte()
                    this.value[index++] = (tempValue shr 8 and 0xFF).toByte()
                    this.value[index] = (tempValue and 0xFF).toByte()
                }
            }
            FORMAT_UINT32 -> if (byteOrder == LITTLE_ENDIAN) {
                this.value[index++] = (tempValue and 0xFF).toByte()
                this.value[index++] = (tempValue shr 8 and 0xFF).toByte()
                this.value[index++] = (tempValue shr 16 and 0xFF).toByte()
                this.value[index] = (tempValue shr 24 and 0xFF).toByte()
            } else {
                this.value[index++] = (tempValue shr 24 and 0xFF).toByte()
                this.value[index++] = (tempValue shr 16 and 0xFF).toByte()
                this.value[index++] = (tempValue shr 8 and 0xFF).toByte()
                this.value[index] = (tempValue and 0xFF).toByte()
            }
            else -> return false
        }
        return true
    }

    /**
     * Set byte array to an Integer with specified format.
     *
     * @param value      New value for this byte array
     * @param formatType Integer format type used to transform the value parameter
     * @return true if the locally stored value has been set
     */
    fun setIntValue(value: Int, formatType: Int): Boolean {
        val result = setIntValue(value, formatType, offset)
        if (result) {
            offset += getTypeLen(formatType)
        }
        return result
    }

    /**
     * Set byte array to a long
     *
     * @param value New long value for this byte array
     * @return true if the locally stored value has been set
     */
    fun setLong(value: Long): Boolean {
        return setLong(value, offset)
    }

    /**
     * Set byte array to a long
     *
     * @param value  New long value for this byte array
     * @param offset Offset at which the value should be placed
     * @return true if the locally stored value has been set
     */
    fun setLong(value: Long, offset: Int): Boolean {
        var tempValue = value
        prepareArray(offset + 8)
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            for (i in 7 downTo 0) {
                this.value[i + offset] = (tempValue and 0xFF).toByte()
                tempValue = tempValue shr 8
            }
        } else {
            for (i in 0..7) {
                this.value[i + offset] = (tempValue and 0xFF).toByte()
                tempValue = tempValue shr 8
            }
        }
        return true
    }

    /**
     * Set byte array to a float of the specified type.
     *
     * @param mantissa   Mantissa for this float value
     * @param exponent   exponent value for this float value
     * @param formatType Float format type used to transform the value parameter
     * @param offset     Offset at which the value should be placed
     * @return true if the locally stored value has been set
     */
    fun setFloatValue(mantissa: Int, exponent: Int, formatType: Int, offset: Int): Boolean {
        var localMantissa = mantissa
        var localExponent = exponent
        var index = offset
        prepareArray(index + getTypeLen(formatType))
        when (formatType) {
            FORMAT_SFLOAT -> {
                localMantissa = intToSignedBits(localMantissa, 12)
                localExponent = intToSignedBits(localExponent, 4)
                if (byteOrder == LITTLE_ENDIAN) {
                    value[index++] = (localMantissa and 0xFF).toByte()
                    value[index] = (localMantissa shr 8 and 0x0F).toByte()
                    value[index] = (value[index] + (localExponent and 0x0F shl 4)).toByte()
                } else {
                    value[index] = (localMantissa shr 8 and 0x0F).toByte()
                    value[index++] = (value[index] + (localExponent and 0x0F shl 4)).toByte()
                    value[index] = (localMantissa and 0xFF).toByte()
                }
            }
            FORMAT_FLOAT -> {
                localMantissa = intToSignedBits(localMantissa, 24)
                localExponent = intToSignedBits(localExponent, 8)
                if (byteOrder == LITTLE_ENDIAN) {
                    value[index++] = (localMantissa and 0xFF).toByte()
                    value[index++] = (localMantissa shr 8 and 0xFF).toByte()
                    value[index++] = (localMantissa shr 16 and 0xFF).toByte()
                    value[index] = (value[index] + (localExponent and 0xFF).toByte()).toByte()
                } else {
                    value[index++] = (value[offset] + (localExponent and 0xFF)).toByte()
                    value[index++] = (localMantissa shr 16 and 0xFF).toByte()
                    value[index++] = (localMantissa shr 8 and 0xFF).toByte()
                    value[index] = (localMantissa and 0xFF).toByte()
                }
            }
            else -> return false
        }
        this.offset += getTypeLen(formatType)
        return true
    }

    /**
     * Create byte[] value from Float usingg a given precision, i.e. number of digits after the comma
     *
     * @param value     Float value to create byte[] from
     * @param precision number of digits after the comma to use
     * @return true if the locally stored value has been set
     */
    fun setFloatValue(value: Float, precision: Int): Boolean {
        val mantissa = (value * 10.toDouble().pow(precision.toDouble())).toFloat()
        return setFloatValue(mantissa.toInt(), -precision, FORMAT_FLOAT, offset)
    }

    /**
     * Set byte array to a string at current offset
     *
     * @param value String to be added to byte array
     * @return true if the locally stored value has been set
     */
    fun setString(value: String?): Boolean {
        if (value != null) {
            setString(value, offset)
            offset += value.toByteArray().size
            return true
        }
        return false
    }

    /**
     * Set byte array to a string at specified offset position
     *
     * @param value  String to be added to byte array
     * @param offset the offset to place the string at
     * @return true if the locally stored value has been set
     */
    fun setString(value: String?, offset: Int): Boolean {
        if (value != null) {
            prepareArray(offset + value.length)
            val valueBytes = value.toByteArray()
            System.arraycopy(valueBytes, 0, this.value, offset, valueBytes.size)
            return true
        }
        return false
    }

    /**
     * Sets the byte array to represent the current date in CurrentTime format
     *
     * @param calendar the calendar object representing the current date
     * @return flase if the calendar object was null, otherwise true
     */
    fun setCurrentTime(calendar: Calendar): Boolean {
        prepareArray(10)
        setDateTime(calendar)
        value[7] = ((calendar[Calendar.DAY_OF_WEEK] + 5) % 7 + 1).toByte()
        value[8] = (calendar[Calendar.MILLISECOND] * 256 / 1000).toByte()
        value[9] = 1
        return true
    }

    /**
     * Sets the byte array to represent the current date in CurrentTime format
     *
     * @param calendar the calendar object representing the current date
     * @return flase if the calendar object was null, otherwise true
     */
    fun setDateTime(calendar: Calendar): Boolean {
        prepareArray(7)
        value[0] = calendar[Calendar.YEAR].toByte()
        value[1] = (calendar[Calendar.YEAR] shr 8).toByte()
        value[2] = (calendar[Calendar.MONTH] + 1).toByte()
        value[3] = calendar[Calendar.DATE].toByte()
        value[4] = calendar[Calendar.HOUR_OF_DAY].toByte()
        value[5] = calendar[Calendar.MINUTE].toByte()
        value[6] = calendar[Calendar.SECOND].toByte()
        return true
    }

    /**
     * Returns the size of a give value type.
     */
    private fun getTypeLen(formatType: Int): Int {
        return formatType and 0xF
    }

    /**
     * Convert a signed byte to an unsigned int.
     */
    private fun unsignedByteToInt(b: Byte): Int {
        return b.toInt() and 0xFF
    }

    /**
     * Convert signed bytes to a 16-bit unsigned int.
     */
    private fun unsignedBytesToInt(b0: Byte, b1: Byte): Int {
        return unsignedByteToInt(b0) + (unsignedByteToInt(b1) shl 8)
    }

    /**
     * Convert signed bytes to a 32-bit unsigned int.
     */
    private fun unsignedBytesToInt(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Int {
        return (unsignedByteToInt(b0) + (unsignedByteToInt(b1) shl 8)
                + (unsignedByteToInt(b2) shl 16) + (unsignedByteToInt(b3) shl 24))
    }

    /**
     * Convert signed bytes to a 16-bit short float value.
     */
    private fun bytesToFloat(b0: Byte, b1: Byte): Float {
        val mantissa = unsignedToSigned(unsignedByteToInt(b0)
                    + (unsignedByteToInt(b1) and 0x0F shl 8), 12)
        val exponent = unsignedToSigned(unsignedByteToInt(b1) shr 4, 4)
        return (mantissa * 10.toDouble().pow( exponent.toDouble())).toFloat()
    }

    /**
     * Convert signed bytes to a 32-bit float value.
     */
    private fun bytesToFloat(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Float {
        val mantissa = unsignedToSigned(
            unsignedByteToInt(b0)
                    + (unsignedByteToInt(b1) shl 8)
                    + (unsignedByteToInt(b2) shl 16), 24
        )
        return (mantissa * 10.toDouble().pow(b3.toDouble())).toFloat()
    }

    /**
     * Convert an unsigned integer value to a two's-complement encoded
     * signed value.
     */
    private fun unsignedToSigned(unsignedValue: Int, size: Int): Int {
        var unsigned = unsignedValue
        if (unsigned and (1 shl size - 1) != 0) {
            unsigned = -1 * ((1 shl size - 1) - (unsigned and (1 shl size - 1) - 1))
        }
        return unsigned
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

    private fun prepareArray(neededLength: Int) {
        if (neededLength > value.size) {
            val largerByteArray = ByteArray(neededLength)
            System.arraycopy(value, 0, largerByteArray, 0, value.size)
            value = largerByteArray
        }
    }

    override fun toString(): String {
        return bytes2String(value)
    }

    companion object {
        /**
         * Characteristic value format type uint8
         */
        const val FORMAT_UINT8 = 0x11

        /**
         * Characteristic value format type uint16
         */
        const val FORMAT_UINT16 = 0x12

        /**
         * Characteristic value format type uint24
         */
        const val FORMAT_UINT24 = 0x13

        /**
         * Characteristic value format type uint32
         */
        const val FORMAT_UINT32 = 0x14

        /**
         * Characteristic value format type sint8
         */
        const val FORMAT_SINT8 = 0x21

        /**
         * Characteristic value format type sint16
         */
        const val FORMAT_SINT16 = 0x22

        /**
         * Characteristic value format type sint24
         */
        const val FORMAT_SINT24 = 0x23

        /**
         * Characteristic value format type sint32
         */
        const val FORMAT_SINT32 = 0x24

        /**
         * Characteristic value format type sfloat (16-bit float)
         */
        const val FORMAT_SFLOAT = 0x32

        /**
         * Characteristic value format type float (32-bit float)
         */
        const val FORMAT_FLOAT = 0x34

        /**
         * Convert a byte array to a string
         *
         * @param bytes the bytes to convert
         * @return String object that represents the byte array
         */
        fun bytes2String(bytes: ByteArray?): String {
            if (bytes == null) return ""
            val sb = StringBuilder()
            for (b in bytes) {
                sb.append(String.format("%02x", b.toInt() and 0xff))
            }
            return sb.toString()
        }

        /**
         * Convert a hex string to byte array
         *
         */
        fun string2bytes(hexString: String?): ByteArray {
            if (hexString == null) return ByteArray(0)
            val result = ByteArray(hexString.length / 2)
            for (i in result.indices) {
                val index = i * 2
                result[i] = hexString.substring(index, index + 2).toInt(16).toByte()
            }
            return result
        }

        /**
         * Merge multiple arrays intro one array
         *
         * @param arrays Arrays to merge
         * @return Merge array
         */
        fun mergeArrays(vararg arrays: ByteArray): ByteArray {
            var size = 0
            for (array in arrays) {
                size += array.size
            }
            val merged = ByteArray(size)
            var index = 0
            for (array in arrays) {
                System.arraycopy(array, 0, merged, index, array.size)
                index += array.size
            }
            return merged
        }
    }
}