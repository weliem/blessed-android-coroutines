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
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.AdvertiseSettings
import com.welie.blessed.GattStatus

/**
 * Callbacks for the BluetoothPeripheralManager class
 */
@Suppress("UNUSED_PARAMETER")
abstract class BluetoothPeripheralManagerCallback {
    /**
     * Indicates whether a local service has been added successfully.
     *
     * @param status  Returns SUCCESS if the service was added
     * successfully.
     * @param service The service that has been added
     */
    open fun onServiceAdded(status: GattStatus, service: BluetoothGattService) {}

    /**
     * A remote central has requested to read a local characteristic.
     *
     *
     * This callback is called before the current value of the characteristic is returned to the central.
     * Therefore, any modifications to the characteristic value can still be made.
     * If the characteristic's value is longer than the MTU - 1 bytes, a long read will be executed automatically
     *
     * @param bluetoothCentral the central that is doing the request
     * @param characteristic the characteristic to be read
     */
    open fun onCharacteristicRead(bluetoothCentral: BluetoothCentral, characteristic: BluetoothGattCharacteristic): ReadResponse {
        return ReadResponse(GattStatus.SUCCESS, ByteArray(0))
    }

    /**
     * A remote central has requested to write a local characteristic.
     *
     *
     * This callback is called before the current value of the characteristic is set to `value`.
     * The value should be checked and a GattStatus should be returned. If anything else than GattStatus.SUCCESS is returned,
     * the characteristic's value will not be updated.
     *
     *
     * The value may be up to 512 bytes (in case of a long write)
     *
     * @param bluetoothCentral the central that is doing the request
     * @param characteristic the characteristic to be written
     * @param value the value the central wants to write
     * @return GattStatus.SUCCESS if the value is acceptable, otherwise an appropriate status
     */
    open fun onCharacteristicWrite(bluetoothCentral: BluetoothCentral, characteristic: BluetoothGattCharacteristic, value: ByteArray): GattStatus {
        return GattStatus.SUCCESS
    }

    /**
     * A remote central has successfully written a new value to a characteristic
     *
     *
     * This callback is called after a response has been sent to the BluetoothCentral.
     * On api-level < 33 the value has been set to the characteristic.
     *
     * @param bluetoothCentral the central that did the write
     * @param characteristic   the characteristic that was written
     * @param value            the value the central has written
     */
    open fun onCharacteristicWriteCompleted(bluetoothCentral: BluetoothCentral, characteristic: BluetoothGattCharacteristic, value: ByteArray) {}

    /**
     * A remote central has requested to read a local descriptor.
     *
     *
     * This callback is called before the current value of the descriptor is returned to the central.
     * Therefore, any modifications to the characteristic value can still be made.
     * If the descriptor's value is longer than the MTU - 1 bytes, a long read will be executed automatically
     *
     * @param bluetoothCentral the central that is doing the request
     * @param descriptor the descriptor to be read
     */
    open fun onDescriptorRead(bluetoothCentral: BluetoothCentral, descriptor: BluetoothGattDescriptor): ReadResponse {
        return ReadResponse(GattStatus.SUCCESS, ByteArray(0))
    }

    /**
     * A remote central has requested to write a local descriptor.
     *
     *
     * This callback is called before the current value of the descriptor is set to `value`.
     * The value should be checked and a GattStatus should be returned. If anything else than GattStatus.SUCCESS is returned,
     * the descriptor's value will not be updated.
     *
     *
     * The value may be up to 512 bytes (in case of a long write)
     *
     * @param bluetoothCentral the central that is doing the request
     * @param descriptor the descriptor to be written
     * @param value the value the central wants to write
     * @return GattStatus.SUCCESS if the value is acceptable, otherwise an appropriate status
     */
    open fun onDescriptorWrite(bluetoothCentral: BluetoothCentral, descriptor: BluetoothGattDescriptor, value: ByteArray): GattStatus {
        return GattStatus.SUCCESS
    }

    /**
     * A remote central has successfully written a new value to a descriptor
     *
     *
     * This callback is called after a response has been sent.
     * On api-level < 33, the value has been set to the descriptor.
     *
     * @param bluetoothCentral the central that did the write
     * @param descriptor       the descriptor that was written
     * @param value            the value the central has written
     */
    open fun onDescriptorWriteCompleted(bluetoothCentral: BluetoothCentral, descriptor: BluetoothGattDescriptor, value: ByteArray) {}

    /**
     * A remote central has enabled notifications or indications for a characteristic
     *
     * @param bluetoothCentral the central
     * @param characteristic the characteristic
     */
    open fun onNotifyingEnabled(bluetoothCentral: BluetoothCentral, characteristic: BluetoothGattCharacteristic) {}

    /**
     * A remote central has disabled notifications or indications for a characteristic
     *
     * @param bluetoothCentral the central
     * @param characteristic the characteristic
     */
    open fun onNotifyingDisabled(bluetoothCentral: BluetoothCentral, characteristic: BluetoothGattCharacteristic) {}

    /**
     * A notification has been sent to a central
     *
     * @param bluetoothCentral the central
     * @param value the value of the notification
     * @param characteristic the characteristic for which the notification was sent
     * @param status the status of the operation
     */
    open fun onNotificationSent(bluetoothCentral: BluetoothCentral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {}

    /**
     * A remote central has connected
     *
     * @param bluetoothCentral the central
     */
    open fun onCentralConnected(bluetoothCentral: BluetoothCentral) {}

    /**
     * A remote central has disconnected
     *
     * @param bluetoothCentral the central
     */
    open fun onCentralDisconnected(bluetoothCentral: BluetoothCentral) {}

    /**
     * Advertising has successfully started
     *
     * @param settingsInEffect the AdvertiseSettings that are currently active
     */
    open fun onAdvertisingStarted(settingsInEffect: AdvertiseSettings) {}

    /**
     * Advertising has failed
     *
     * @param advertiseError the error explaining why the advertising failed
     */
    open fun onAdvertiseFailure(advertiseError: AdvertiseError) {}

    /**
     * Advertising has stopped
     *
     */
    open fun onAdvertisingStopped() {}
}