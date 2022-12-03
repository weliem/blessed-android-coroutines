package com.welie.blessed

import org.junit.Assert.*
import org.junit.Test


class ByteArrayTests {

    @Test
    fun getUInt16_pos_LE_test() {
        val value = byteArrayOf(0x01,0x02)
        assertEquals(513u, value.getUInt16(order = ByteOrder.LITTLE_ENDIAN))
    }

    @Test
    fun getUInt16_pos_BE_test() {
        val value = byteArrayOf(0x01,0x02)
        assertEquals(258u, value.getUInt16(order = ByteOrder.BIG_ENDIAN))
    }

    @Test
    fun getUInt16_max_LE_test() {
        val value = byteArrayOf(0xFF.toByte(),0xFF.toByte())
        assertEquals(65535u, value.getUInt16(order = ByteOrder.LITTLE_ENDIAN))
    }

    @Test
    fun getUInt16_max_BE_test() {
        val value = byteArrayOf(0xFF.toByte(),0xFF.toByte())
        assertEquals(65535u, value.getUInt16(order = ByteOrder.BIG_ENDIAN))
    }

    @Test
    fun getInt16_pos_LE_test() {
        val value = byteArrayOf(0x01,0x02)
        assertEquals(513, value.getInt16(order = ByteOrder.LITTLE_ENDIAN))
    }

    @Test
    fun getInt16_pos_BE_test() {
        val value = byteArrayOf(0x01,0x02)
        assertEquals(258, value.getInt16(order = ByteOrder.BIG_ENDIAN))
    }

    @Test
    fun getInt16_neg_LE_test() {
        val value = byteArrayOf(3,-4)
        assertEquals(-1021, value.getInt16(order = ByteOrder.LITTLE_ENDIAN))
    }

    @Test
    fun getInt16_neg_BE_test() {
        val value = byteArrayOf(0x01,0x02)
        assertEquals(258, value.getInt16(order = ByteOrder.BIG_ENDIAN))
    }

    @Test
    fun getUInt32_test() {
        val value = byteArrayOf(0xFF.toByte(),0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(UInt.MAX_VALUE, value.getUInt32(order = ByteOrder.LITTLE_ENDIAN))
    }

    @Test
    fun getInt32_minus1_test() {
        val value = byteArrayOf(0xFF.toByte(),0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, value.getInt32(order = ByteOrder.LITTLE_ENDIAN))
    }

    @Test
    fun getInt32_minus_random_LE_test() {
        val value = byteArrayOf(1,-2,3,-4)
        assertEquals(-66847231, value.getInt32(order = ByteOrder.LITTLE_ENDIAN))
    }

}