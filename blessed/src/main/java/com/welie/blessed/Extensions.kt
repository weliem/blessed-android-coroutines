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

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.*

fun ByteArray.asString() : String {
    if(this.isEmpty()) return ""
    val parser = BluetoothBytesParser(this)
    return parser.stringValue
}

fun ByteArray.asHexString() : String {
    return BluetoothBytesParser.bytes2String(this)
}

fun ByteArray.asUInt8() : UInt? {
    if (this.isEmpty()) return null
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
        else -> 0
    }
    return properties and writeProperty > 0
}