package com.welie.blessedexample

import com.welie.blessed.BluetoothBytesParser
import com.welie.blessed.BluetoothBytesParser.Companion.FORMAT_SFLOAT
import com.welie.blessed.BluetoothBytesParser.Companion.FORMAT_UINT16
import com.welie.blessed.BluetoothBytesParser.Companion.FORMAT_UINT8
import java.util.*

data class PulseOximeterSpotMeasurement(
    val spO2: Float,
    val pulseRate: Float,
    val pulseAmplitudeIndex: Float?,
    val timestamp: Date?,
    val isDeviceClockSet: Boolean,
    val measurementStatus: Int?,
    val sensorStatus: Int?,
    val createdAt: Date = Calendar.getInstance().time
) {
    companion object {
        fun fromBytes(value: ByteArray): PulseOximeterSpotMeasurement {
            val parser = BluetoothBytesParser(value)
            val flags = parser.getIntValue(FORMAT_UINT8)
            val timestampPresent = flags and 0x01 > 0
            val measurementStatusPresent = flags and 0x02 > 0
            val sensorStatusPresent = flags and 0x04 > 0
            val pulseAmplitudeIndexPresent = flags and 0x08 > 0
            val isDeviceClockSet = flags and 0x10 == 0

            val spO2 = parser.getFloatValue(FORMAT_SFLOAT)
            val pulseRate = parser.getFloatValue(FORMAT_SFLOAT)
            val timestamp = if (timestampPresent) parser.dateTime else null
            val measurementStatus = if (measurementStatusPresent) parser.getIntValue(FORMAT_UINT16) else null
            val sensorStatus = if (sensorStatusPresent) parser.getIntValue(FORMAT_UINT16) else null
            if (sensorStatusPresent) parser.getIntValue(FORMAT_UINT8) // Reserved byte
            val pulseAmplitudeIndex = if (pulseAmplitudeIndexPresent) parser.getFloatValue(FORMAT_SFLOAT) else null

            return PulseOximeterSpotMeasurement(
                spO2 = spO2,
                pulseRate = pulseRate,
                measurementStatus = measurementStatus,
                sensorStatus = sensorStatus,
                pulseAmplitudeIndex = pulseAmplitudeIndex,
                timestamp = timestamp,
                isDeviceClockSet = isDeviceClockSet
            )
        }
    }
}