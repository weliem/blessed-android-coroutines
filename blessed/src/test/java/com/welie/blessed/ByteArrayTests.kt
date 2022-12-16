package com.welie.blessed

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteOrder.BIG_ENDIAN
import java.nio.ByteOrder.LITTLE_ENDIAN


class ByteArrayTests {

    @Test
    fun getUInt16_pos_LE_test() {
        val value = byteArrayOf(0x01u,0x02u)
        assertEquals(513u, value.getUInt16(order = LITTLE_ENDIAN))
    }

    @Test
    fun getUInt16_pos_BE_test() {
        val value = byteArrayOf(0x01u,0x02u)
        assertEquals(258u, value.getUInt16(order = BIG_ENDIAN))
    }

    @Test
    fun getUInt16_max_LE_test() {
        val value = byteArrayOf(0xFF.toByte(),0xFF.toByte())
        assertEquals(65535u, value.getUInt16(order = LITTLE_ENDIAN))
    }

    @Test
    fun getUInt16_max_BE_test() {
        val value = byteArrayOf(0xFF.toByte(),0xFF.toByte())
        assertEquals(65535u, value.getUInt16(order = BIG_ENDIAN))
    }

    @Test
    fun getInt16_pos_LE_test() {
        val value = byteArrayOf(0x01u,0x02u)
        assertEquals(513, value.getInt16(order = LITTLE_ENDIAN))
    }

    @Test
    fun getInt16_pos_BE_test() {
        val value = byteArrayOf(0x01u,0x02u)
        assertEquals(258, value.getInt16(order = BIG_ENDIAN))
    }

    @Test
    fun getInt16_neg_LE_test() {
        val value = byteArrayOf(3,-4)
        assertEquals(-1021, value.getInt16(order = LITTLE_ENDIAN))
    }

    @Test
    fun getInt16_neg_BE_test() {
        val value = byteArrayOf(-4, 3)
        assertEquals(-1021, value.getInt16(order = BIG_ENDIAN))
    }

    @Test
    fun getUInt32_max_LE_test() {
        val value = byteArrayOf(0xFF.toByte(),0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(UInt.MAX_VALUE, value.getUInt32(order = LITTLE_ENDIAN))
    }

    @Test
    fun getInt32_minus1_LE_test() {
        val value = byteArrayOf(0xFF.toByte(),0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, value.getInt32(order = LITTLE_ENDIAN))
    }

    @Test
    fun getInt32_minus_random_LE_test() {
        val value = byteArrayOf(1,-2,3,-4)
        assertEquals(-66847231, value.getInt32(order = LITTLE_ENDIAN))
    }

    @Test
    fun getFloat32_random_LE_test() {
        val value = byteArrayOf("6C0100FF")
        assertEquals(36.4, value.getFloat(order = LITTLE_ENDIAN), 0.1)
    }

    @Test
    fun getFloat32_random_BE_test() {
        // 6C0100FF
        val value = byteArrayOf("FF000170")
        assertEquals(36.8, value.getFloat(order = BIG_ENDIAN), 0.1)
    }

    @Test
    fun getFloat16_pos_random_BE_test() {
        val value = byteArrayOf("F070")
        assertEquals(11.2, value.getSFloat(order = BIG_ENDIAN), 0.1)
    }

    @Test
    fun getFloat16_neg_random_LE_test() {
        val value = byteArrayOf("70F8")
        assertEquals(-193.6, value.getSFloat(order = LITTLE_ENDIAN), 0.1)
    }

    @Test
    fun getFloat16_neg_random_BE_test() {
        val value = byteArrayOf("F870")
        assertEquals(-193.6, value.getSFloat(order = BIG_ENDIAN), 0.1)
    }

    @Test
    fun byteArrayOf_UInt16_LE_test() {
        val value = byteArrayOf(256u, 2u, LITTLE_ENDIAN)
        assertEquals("0001", value.asHexString())
    }

    @Test
    fun hexString_test() {
        val value = byteArrayOf("F870")
        assertEquals("F870", value.asHexString())
    }

    @Test
    fun byteArrayOf_SFloat_LE_test() {
        val value = byteArrayOf(11.2, 2u, 1, LITTLE_ENDIAN)
        assertEquals("70F0", value.asHexString())
    }

    @Test
    fun byteArrayOf_SFloat_BE_test() {
        val value = byteArrayOf(11.2, 2u, 1, BIG_ENDIAN)
        assertEquals("F070", value.asHexString())
    }

    @Test
    fun byteArrayOf_Float_LE_test() {
        val value = byteArrayOf(36.4, 4u, 1, LITTLE_ENDIAN)
        assertEquals("6C0100FF", value.asHexString())
    }

    @Test
    fun byteArrayOf_Float_BE_test() {
        val value = byteArrayOf(36.4, 4u, 1, BIG_ENDIAN)
        assertEquals("FF00016C", value.asHexString())
    }

    @Test
    fun merge_test() {
        val value = byteArrayOf("F870")
        val value2 = byteArrayOf("A387")
        val value3 = byteArrayOf("5638")
        val merged = mergeArrays(value,value2,value3)

        assertEquals("F870A3875638", merged.asHexString())
    }
}