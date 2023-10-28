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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import java.util.*

/**
 * This class represent a remote Central
 */
@SuppressLint("MissingPermission")
@Suppress("unused")
class BluetoothCentral internal constructor(val device: BluetoothDevice) {
    var currentMtu = 23

    val address: String
        get() = device.address
    val name: String
        get() = if (device.name == null) "" else device.name
    val bondState: BondState
        get() = BondState.fromValue(device.bondState)

    /**
     * Create a bond
     */
    fun createBond(): Boolean {
        return device.createBond()
    }

    /**
     * Confirm pairing
     */
    fun setPairingConfirmation(confirm: Boolean): Boolean {
        return device.setPairingConfirmation(confirm)
    }

    /**
     * Get maximum length of byte array that can be written depending on WriteType
     *
     * This value is derived from the current negotiated MTU or the maximum characteristic length (512)
     */
    fun getMaximumWriteValueLength(writeType: WriteType): Int {
        return when (writeType) {
            WriteType.WITH_RESPONSE -> 512
            WriteType.SIGNED -> currentMtu - 15
            else -> currentMtu - 3
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as BluetoothCentral
        return device.address == that.device.address
    }

    override fun hashCode(): Int {
        return Objects.hash(device)
    }
}