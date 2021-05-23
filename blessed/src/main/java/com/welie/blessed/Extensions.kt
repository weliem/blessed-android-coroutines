package com.welie.blessed

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.*
import com.welie.blessed.BluetoothBytesParser.FORMAT_UINT8


fun ByteArray.asString() : String {
    val parser = BluetoothBytesParser(this)
    return parser.stringValue
}

fun ByteArray.asUInt8() : UInt {
    val parser = BluetoothBytesParser(this)
    return parser.getIntValue(FORMAT_UINT8).toUInt()
}

fun BluetoothGattCharacteristic.supportsReading(): Boolean {
    return properties and PROPERTY_READ > 0
}

fun BluetoothGattCharacteristic.supportsWritingWithResponse(): Boolean {
    return properties and PROPERTY_WRITE > 0
}

fun BluetoothGattCharacteristic.supportsWritingWithoutResponse(): Boolean {
    return properties and PROPERTY_WRITE_NO_RESPONSE > 0
}

fun BluetoothGattCharacteristic.supportsNotifying(): Boolean {
    return properties and PROPERTY_NOTIFY > 0 || properties and PROPERTY_INDICATE > 0
}

fun BluetoothGattCharacteristic.supportsWriteType(writeType: WriteType): Boolean {
    val writeProperty: Int = when (writeType) {
        WriteType.WITH_RESPONSE -> PROPERTY_WRITE
        WriteType.WITHOUT_RESPONSE -> PROPERTY_WRITE_NO_RESPONSE
        else -> -1
    }
    return properties and writeProperty > 0
}