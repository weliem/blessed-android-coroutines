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

import android.bluetooth.le.ScanResult

/**
 * Callbacks for the BluetoothCentralManager class
 */
internal abstract class BluetoothCentralManagerCallback {
    /**
     * Successfully connected with a peripheral.
     *
     * @param peripheral the peripheral that was connected.
     */
    open fun onConnectedPeripheral(peripheral: BluetoothPeripheral) {}

    /**
     * Connecting with the peripheral has failed.
     *
     * @param peripheral the peripheral for which the connection was attempted
     * @param status the status code for the connection failure
     */
    open fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {}

    /**
     * Peripheral disconnected
     *
     * @param peripheral the peripheral that disconnected.
     * @param status the status code for the disconnection
     */
    open fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: HciStatus) {}

    /**
     * Discovered a peripheral
     *
     * @param peripheral the peripheral that was found
     * @param scanResult the scanResult describing the peripheral
     */
    open fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: ScanResult) {}

    /**
     * Scanning failed
     *
     * @param scanFailure the status code for the scanning failure
     */
    open fun onScanFailed(scanFailure: ScanFailure) {}

    /**
     * Bluetooth adapter status changed
     *
     * @param state the current status code for the adapter
     */
    open fun onBluetoothAdapterStateChanged(state: Int) {}

    /**
     * NULL class to deal with nullability
     */
    internal class NULL : BluetoothCentralManagerCallback()
}