package com.welie.blessedexample

import com.welie.blessed.BluetoothBytesParser
import com.welie.blessed.BluetoothBytesParser.Companion.FORMAT_SFLOAT
import com.welie.blessed.BluetoothBytesParser.Companion.FORMAT_UINT16
import com.welie.blessed.BluetoothBytesParser.Companion.FORMAT_UINT8
import java.util.*

data class PulseOximeterContinuousMeasurement(
    val spO2: Float,
    val pulseRate: Float,
    val spO2Fast: Float?,
    val pulseRateFast: Float?,
    val spO2Slow: Float?,
    val pulseRateSlow: Float?,
    val pulseAmplitudeIndex: Float?,
    val measurementStatus: Int?,
    val sensorStatus: Int?,
    val createdAt: Date = Calendar.getInstance().time
) {
    companion object {
        fun fromBytes(value: ByteArray): PulseOximeterContinuousMeasurement {
            val parser = BluetoothBytesParser(value)
            val flags = parser.getIntValue(FORMAT_UINT8)
            val spo2FastPresent = flags and 0x01 > 0
            val spo2SlowPresent = flags and 0x02 > 0
            val measurementStatusPresent = flags and 0x04 > 0
            val sensorStatusPresent = flags and 0x08 > 0
            val pulseAmplitudeIndexPresent = flags and 0x10 > 0

            val spO2 = parser.getFloatValue(FORMAT_SFLOAT)
            val pulseRate = parser.getFloatValue(FORMAT_SFLOAT)
            val spO2Fast = if (spo2FastPresent) parser.getFloatValue(FORMAT_SFLOAT) else null
            val pulseRateFast = if (spo2FastPresent) parser.getFloatValue(FORMAT_SFLOAT) else null
            val spO2Slow = if (spo2SlowPresent) parser.getFloatValue(FORMAT_SFLOAT) else null
            val pulseRateSlow = if (spo2SlowPresent) parser.getFloatValue(FORMAT_SFLOAT) else null
            val measurementStatus = if (measurementStatusPresent) parser.getIntValue(FORMAT_UINT16) else null
            val sensorStatus = if (sensorStatusPresent) parser.getIntValue(FORMAT_UINT16) else null
            if (sensorStatusPresent) parser.getIntValue(FORMAT_UINT8) // Reserved byte
            val pulseAmplitudeIndex = if (pulseAmplitudeIndexPresent) parser.getFloatValue(FORMAT_SFLOAT) else null

            return PulseOximeterContinuousMeasurement(
                spO2 = spO2,
                pulseRate = pulseRate,
                spO2Fast = spO2Fast,
                pulseRateFast = pulseRateFast,
                spO2Slow = spO2Slow,
                pulseRateSlow = pulseRateSlow,
                measurementStatus = measurementStatus,
                sensorStatus = sensorStatus,
                pulseAmplitudeIndex = pulseAmplitudeIndex
            )
        }
    }
}