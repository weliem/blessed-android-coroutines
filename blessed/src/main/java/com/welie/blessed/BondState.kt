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
 * The class represents the various possible bond states
 */
enum class BondState(val value: Int) {
    /**
     * Indicates the remote peripheral is not bonded.
     * There is no shared link key with the remote peripheral, so communication
     * (if it is allowed at all) will be unauthenticated and unencrypted.
     */
    NONE(BluetoothDevice.BOND_NONE),

    /**
     * Indicates bonding is in progress with the remote peripheral.
     */
    BONDING(BluetoothDevice.BOND_BONDING),

    /**
     * Indicates the remote peripheral is bonded.
     * A shared link keys exists locally for the remote peripheral, so
     * communication can be authenticated and encrypted.
     */
    BONDED(BluetoothDevice.BOND_BONDED),

    BOND_LOST(13),
    BONDING_FAILED(14);

    companion object {
        fun fromValue(value: Int): BondState {
            for (type in values()) {
                if (type.value == value) {
                    return type
                }
            }
            return NONE
        }
    }
}