package com.welie.blessedexample

import com.welie.blessed.BluetoothBytesParser
import com.welie.blessed.BluetoothBytesParser.Companion.FORMAT_SFLOAT
import com.welie.blessed.BluetoothBytesParser.Companion.FORMAT_UINT16
import com.welie.blessed.BluetoothBytesParser.Companion.FORMAT_UINT8
import java.nio.ByteOrder
import java.util.*

data class BloodPressureMeasurement(
    val systolic: Float,
    val diastolic: Float,
    val meanArterialPressure: Float,
    val unit: ObservationUnit,
    val timestamp: Date?,
    val pulseRate: Float?,
    val userID: Int?,
    val measurementStatus: BloodPressureMeasurementStatus?,
    val createdAt: Date = Calendar.getInstance().time
) {
    companion object {
        fun fromBytes(value: ByteArray): BloodPressureMeasurement {
            val parser = BluetoothBytesParser(value, ByteOrder.LITTLE_ENDIAN)
            val flags = parser.getIntValue(FORMAT_UINT8)
            val unit = if (flags and 0x01 > 0) ObservationUnit.KPA else ObservationUnit.MMHG
            val timestampPresent = flags and 0x02 > 0
            val pulseRatePresent = flags and 0x04 > 0
            val userIdPresent = flags and 0x08 > 0
            val measurementStatusPresent = flags and 0x10 > 0

            val systolic = parser.getFloatValue(FORMAT_SFLOAT)
            val diastolic = parser.getFloatValue(FORMAT_SFLOAT)
            val meanArterialPressure = parser.getFloatValue(FORMAT_SFLOAT)
            val timestamp = if (timestampPresent) parser.dateTime else null
            val pulseRate = if (pulseRatePresent) parser.getFloatValue(FORMAT_SFLOAT) else null
            val userID = if (userIdPresent) parser.getIntValue(FORMAT_UINT8) else null
            val status = if (measurementStatusPresent) BloodPressureMeasurementStatus(parser.getIntValue(FORMAT_UINT16)) else null

            return BloodPressureMeasurement(
                systolic = systolic,
                diastolic = diastolic,
                meanArterialPressure = meanArterialPressure,
                unit = unit,
                timestamp = timestamp,
                pulseRate = pulseRate,
                userID = userID,
                measurementStatus = status
            )
        }
    }
}