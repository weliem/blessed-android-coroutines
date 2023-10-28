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

/**
 * WriteType describes the type of write that can be done
 */
enum class WriteType(val writeType: Int, val property: Int) {
    /**
     * Write characteristic and requesting acknowledgement by the remote peripheral
     */
    WITH_RESPONSE(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, BluetoothGattCharacteristic.PROPERTY_WRITE),

    /**
     * Write characteristic without requiring a response by the remote peripheral
     */
    WITHOUT_RESPONSE(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE),

    /**
     * Write characteristic including authentication signature
     */
    SIGNED(BluetoothGattCharacteristic.WRITE_TYPE_SIGNED, BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE);
}