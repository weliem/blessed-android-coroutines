package com.welie.blessedexample

import com.welie.blessed.BluetoothBytesParser
import com.welie.blessed.BluetoothBytesParser.Companion.FORMAT_FLOAT
import com.welie.blessed.BluetoothBytesParser.Companion.FORMAT_UINT8
import java.nio.ByteOrder
import java.util.*

data class TemperatureMeasurement(
    val temperatureValue: Float,
    val unit: ObservationUnit,
    val timestamp: Date?,
    val type: TemperatureType,
    val createdAt: Date = Calendar.getInstance().time
) {
    companion object {
        fun fromBytes(value: ByteArray): TemperatureMeasurement {
            val parser = BluetoothBytesParser(value, ByteOrder.LITTLE_ENDIAN)
            val flags = parser.getIntValue(FORMAT_UINT8)
            val unit = if (flags and 0x01 > 0) ObservationUnit.Fahrenheit else ObservationUnit.Celsius
            val timestampPresent = flags and 0x02 > 0
            val typePresent = flags and 0x04 > 0

            val temperatureValue = parser.getFloatValue(FORMAT_FLOAT)
            val timestamp = if (timestampPresent) parser.dateTime else null
            val type = if (typePresent) TemperatureType.fromValue(parser.getIntValue(FORMAT_UINT8)) else TemperatureType.Unknown

            return TemperatureMeasurement(
                unit = unit,
                temperatureValue = temperatureValue,
                timestamp = timestamp,
                type = type
            )
        }
    }
}