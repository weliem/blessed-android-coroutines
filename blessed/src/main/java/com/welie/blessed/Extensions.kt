package com.welie.blessed

import com.welie.blessed.BluetoothBytesParser.FORMAT_UINT8

fun ByteArray.asString() : String {
    val parser = BluetoothBytesParser(this)
    return parser.stringValue
}

fun ByteArray.asUInt8() : UInt {
    val parser = BluetoothBytesParser(this)
    return parser.getIntValue(FORMAT_UINT8).toUInt()
}