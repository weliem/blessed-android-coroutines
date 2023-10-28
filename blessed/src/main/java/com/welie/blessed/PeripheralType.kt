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

import android.bluetooth.BluetoothDevice

/**
 * This class represents the possible peripheral types
 */
enum class PeripheralType(val value: Int) {
    /**
     * Unknown peripheral type, peripheral is not cached
     */
    UNKNOWN(BluetoothDevice.DEVICE_TYPE_UNKNOWN),

    /**
     * Classic - BR/EDR peripheral
     */
    CLASSIC(BluetoothDevice.DEVICE_TYPE_CLASSIC),

    /**
     * Bluetooth Low Energy peripheral
     */
    LE(BluetoothDevice.DEVICE_TYPE_LE),

    /**
     * Dual Mode - BR/EDR/LE
     */
    DUAL(BluetoothDevice.DEVICE_TYPE_DUAL);

    companion object {
        fun fromValue(value: Int): PeripheralType {
            for (type in values()) {
                if (type.value == value) {
                    return type
                }
            }
            return UNKNOWN
        }
    }
}