/*
 *   Copyright (c) 2021 Martijn van Welie
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
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.welie.blessed.BluetoothBytesParser.Companion.bytes2String
import com.welie.blessed.BluetoothBytesParser.Companion.mergeArrays
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * This class represent a peripheral running on the local phone
 */
@SuppressLint("MissingPermission")
class BluetoothPeripheralManager(private val context: Context, private val bluetoothManager: BluetoothManager, private val callback: BluetoothPeripheralManagerCallback) {
//    private val context: Context
    private val mainHandler = Handler(Looper.getMainLooper())
//    private val bluetoothManager: BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter
    private val bluetoothLeAdvertiser: BluetoothLeAdvertiser
 //   private val callback: BluetoothPeripheralManagerCallback
    val commandQueue: Queue<Runnable> = ConcurrentLinkedQueue()
    private val writeLongCharacteristicTemporaryBytes = HashMap<BluetoothGattCharacteristic, ByteArray>()
    private val writeLongDescriptorTemporaryBytes = HashMap<BluetoothGattDescriptor, ByteArray>()
    private val connectedCentralsMap: MutableMap<String, BluetoothCentral> = ConcurrentHashMap()
    private var currentNotifyCharacteristic: BluetoothGattCharacteristic? = null
    private var currentNotifyValue = ByteArray(0)

    @Volatile
    private var commandQueueBusy = false

    val bluetoothGattServerCallback: BluetoothGattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Call connect() even though we are already connected
                    // It basically tells Android we will really use this connection
                    // If we don't do this, then cancelConnection won't work
                    // See https://issuetracker.google.com/issues/37127644
                    if (connectedCentralsMap.containsKey(device.address)) {
                        return
                    } else {
                        // This will lead to onConnectionStateChange be called again
                        bluetoothGattServer.connect(device, false)
                    }
                    handleDeviceConnected(device)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // Deal is double disconnect messages
                    if (!connectedCentralsMap.containsKey(device.address)) return
                    handleDeviceDisconnected(device)
                }
            } else {
                Logger.i(TAG, "Device '%s' disconnected with status %d", device.name ?: "null", status)
                handleDeviceDisconnected(device)
            }
        }

        private fun handleDeviceConnected(device: BluetoothDevice) {
            Logger.i(TAG, "Central '%s' (%s) connected", device.name ?: "null", device.address)
            val bluetoothCentral = BluetoothCentral(device.address, device.name ?: "null")
            connectedCentralsMap[bluetoothCentral.address] = bluetoothCentral
            mainHandler.post { callback.onCentralConnected(bluetoothCentral) }
        }

        private fun handleDeviceDisconnected(device: BluetoothDevice) {
            val bluetoothCentral = getCentral(device)
            Logger.i(TAG, "Central '%s' (%s) disconnected", bluetoothCentral.getName(), bluetoothCentral.address)
            mainHandler.post { callback.onCentralDisconnected(bluetoothCentral) }
            removeCentral(device)
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            mainHandler.post { callback.onServiceAdded(GattStatus.fromValue(status), service) }
            completedCommand()
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            Logger.i(TAG, "read request for characteristic <%s> with offset %d", characteristic.uuid, offset)
            val bluetoothCentral = getCentral(device)
            mainHandler.post { // Call onCharacteristic before any responses are sent, even if it is a long read
                if (offset == 0) {
                    callback.onCharacteristicRead(bluetoothCentral, characteristic)
                }

                // If data is longer than MTU - 1, cut the array. Only ATT_MTU - 1 bytes can be sent in Long Read.
                val value = copyOf(nonnullOf(characteristic.value), offset, bluetoothCentral.currentMtu - 1)
                bluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Logger.i(TAG, "write characteristic %s request <%s> offset %d for <%s>", if (responseNeeded) "WITH_RESPONSE" else "WITHOUT_RESPONSE", bytes2String(value), offset, characteristic.uuid)
            val safeValue = nonnullOf(value)
            val bluetoothCentral = getCentral(device)
            mainHandler.post {
                var status = GattStatus.SUCCESS
                if (!preparedWrite) {
                    status = callback.onCharacteristicWrite(bluetoothCentral, characteristic, safeValue)
                    if (status === GattStatus.SUCCESS) {
                        characteristic.value = safeValue
                    }
                } else {
                    if (offset == 0) {
                        writeLongCharacteristicTemporaryBytes[characteristic] = safeValue
                    } else {
                        val temporaryBytes = writeLongCharacteristicTemporaryBytes[characteristic]
                        if (temporaryBytes != null && offset == temporaryBytes.size) {
                            writeLongCharacteristicTemporaryBytes[characteristic] = mergeArrays(temporaryBytes, safeValue)
                        } else {
                            status = GattStatus.INVALID_OFFSET
                        }
                    }
                }
                if (responseNeeded) {
                    bluetoothGattServer.sendResponse(device, requestId, status.value, offset, safeValue)
                }
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            Logger.i(TAG, "read request for descriptor <%s> with offset %d", descriptor.uuid, offset)
            val bluetoothCentral = getCentral(device)
            mainHandler.post { // Call onDescriptorRead before any responses are sent, even if it is a long read
                if (offset == 0) {
                    callback.onDescriptorRead(bluetoothCentral, descriptor)
                }

                // If data is longer than MTU - 1, cut the array. Only ATT_MTU - 1 bytes can be sent in Long Read.
                val value = copyOf(nonnullOf(descriptor.value), offset, bluetoothCentral.currentMtu - 1)
                bluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val safeValue = nonnullOf(value)
            val characteristic = Objects.requireNonNull(descriptor.characteristic, "Descriptor does not have characteristic")
            Logger.i(TAG, "write descriptor %s request <%s> offset %d for <%s>", if (responseNeeded) "WITH_RESPONSE" else "WITHOUT_RESPONSE", bytes2String(value), offset, descriptor.uuid)
            val bluetoothCentral = getCentral(device)
            mainHandler.post {
                var status = GattStatus.SUCCESS
                if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                    status = checkCccDescriptorValue(safeValue, characteristic)
                } else {
                    if (!preparedWrite) {
                        // Ask callback if value is ok or not
                        status = callback.onDescriptorWrite(bluetoothCentral, descriptor, safeValue)
                    } else {
                        if (offset == 0) {
                            writeLongDescriptorTemporaryBytes[descriptor] = safeValue
                        } else {
                            val temporaryBytes = writeLongDescriptorTemporaryBytes[descriptor]
                            if (temporaryBytes != null && offset == temporaryBytes.size) {
                                writeLongDescriptorTemporaryBytes[descriptor] = mergeArrays(temporaryBytes, safeValue)
                            } else {
                                status = GattStatus.INVALID_OFFSET
                            }
                        }
                    }
                }
                if (status === GattStatus.SUCCESS && !preparedWrite) {
                    descriptor.value = safeValue
                }
                if (responseNeeded) {
                    bluetoothGattServer.sendResponse(device, requestId, status.value, offset, safeValue)
                }
                if (status === GattStatus.SUCCESS && descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                    if (Arrays.equals(safeValue, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                        || Arrays.equals(safeValue, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    ) {
                        Logger.i(TAG, "notifying enabled for <%s>", characteristic.uuid)
                        callback.onNotifyingEnabled(bluetoothCentral, characteristic)
                    } else {
                        Logger.i(TAG, "notifying disabled for <%s>", characteristic.uuid)
                        callback.onNotifyingDisabled(bluetoothCentral, characteristic)
                    }
                }
            }
        }

        // Check value to see if it is valid and if matches the characteristic properties
        private fun checkCccDescriptorValue(safeValue: ByteArray, characteristic: BluetoothGattCharacteristic): GattStatus {
            var status = GattStatus.SUCCESS
            if (safeValue.size != 2) {
                status = GattStatus.INVALID_ATTRIBUTE_VALUE_LENGTH
            } else if (!(Arrays.equals(safeValue, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                        || Arrays.equals(safeValue, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        || Arrays.equals(safeValue, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
            ) {
                status = GattStatus.VALUE_NOT_ALLOWED
            } else if (!supportsIndicate(characteristic) && Arrays.equals(safeValue, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                status = GattStatus.REQUEST_NOT_SUPPORTED
            } else if (!supportsNotify(characteristic) && Arrays.equals(safeValue, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                status = GattStatus.REQUEST_NOT_SUPPORTED
            }
            return status
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val bluetoothCentral = getCentral(device)
            if (execute) {
                mainHandler.post {
                    var status = GattStatus.SUCCESS
                    if (!writeLongCharacteristicTemporaryBytes.isEmpty()) {
                        val characteristic = writeLongCharacteristicTemporaryBytes.keys.iterator().next()

                        // Ask callback if value is ok or not
                        status = callback.onCharacteristicWrite(bluetoothCentral, characteristic, writeLongCharacteristicTemporaryBytes[characteristic]!!)
                        if (status === GattStatus.SUCCESS) {
                            characteristic.value = writeLongCharacteristicTemporaryBytes[characteristic]
                            writeLongCharacteristicTemporaryBytes.clear()
                        }
                    } else if (!writeLongDescriptorTemporaryBytes.isEmpty()) {
                        val descriptor = writeLongDescriptorTemporaryBytes.keys.iterator().next()

                        // Ask callback if value is ok or not
                        status = callback.onDescriptorWrite(bluetoothCentral, descriptor, writeLongDescriptorTemporaryBytes[descriptor]!!)
                        if (status === GattStatus.SUCCESS) {
                            descriptor.value = writeLongDescriptorTemporaryBytes[descriptor]
                            writeLongDescriptorTemporaryBytes.clear()
                        }
                    }
                    bluetoothGattServer.sendResponse(device, requestId, status.value, 0, null)
                }
            } else {
                // Long write was cancelled, clean up already received bytes
                writeLongCharacteristicTemporaryBytes.clear()
                writeLongDescriptorTemporaryBytes.clear()
                bluetoothGattServer.sendResponse(device, requestId, GattStatus.SUCCESS.value, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val bluetoothCentral = getCentral(device)
            val characteristic = requireNotNull(currentNotifyCharacteristic)
            val value = currentNotifyValue
            currentNotifyValue = ByteArray(0)
            mainHandler.post { callback.onNotificationSent(bluetoothCentral, value, characteristic, GattStatus.fromValue(status)) }
            completedCommand()
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Logger.i(TAG, "new MTU: %d", mtu)
            val bluetoothCentral = getCentral(device)
            bluetoothCentral.currentMtu = mtu
        }

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyUpdate(device, txPhy, rxPhy, status)
        }

        override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyRead(device, txPhy, rxPhy, status)
        }
    }
    @JvmField
    val advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Logger.i(TAG, "advertising started")
            mainHandler.post { callback.onAdvertisingStarted(settingsInEffect) }
        }

        override fun onStartFailure(errorCode: Int) {
            val advertiseError = AdvertiseError.fromValue(errorCode)
            Logger.e(TAG, "advertising failed with error '%s'", advertiseError)
            mainHandler.post { callback.onAdvertiseFailure(advertiseError) }
        }
    }

    protected fun onAdvertisingStopped() {
        Logger.i(TAG, "advertising stopped")
        mainHandler.post { callback.onAdvertisingStopped() }
    }

    /**
     * Close the BluetoothPeripheralManager
     *
     * Application should call this method as early as possible after it is done with
     * this BluetoothPeripheralManager.
     *
     */
    fun close() {
        stopAdvertising()
        context.unregisterReceiver(adapterStateReceiver)
        bluetoothGattServer.close()
    }

    /**
     * Start Bluetooth LE Advertising. The `advertiseData` will be broadcasted if the
     * operation succeeds. The `scanResponse` is returned when a scanning device sends an
     * active scan request. This method returns immediately, the operation status is delivered
     * through [BluetoothPeripheralManagerCallback.onAdvertisingStarted] or [BluetoothPeripheralManagerCallback.onAdvertiseFailure].
     *
     * @param settings the AdvertiseSettings
     * @param advertiseData the AdvertiseData
     * @param scanResponse the ScanResponse
     */
    fun startAdvertising(settings: AdvertiseSettings, advertiseData: AdvertiseData, scanResponse: AdvertiseData) {
        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            Logger.e(TAG, "device does not support advertising")
        } else {
            bluetoothLeAdvertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        }
    }

    /**
     * Stop advertising
     */
    fun stopAdvertising() {
        bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
        onAdvertisingStopped()
    }

    /**
     * Add a service to the peripheral
     *
     *
     * Once a service has been added to the list, the service and its
     * included characteristics will be provided by the local peripheral.
     *
     *
     * If the local peripheral has already exposed services when this function
     * is called, a service update notification will be sent to all clients.
     *
     * A callback on [BluetoothPeripheralManagerCallback.onServiceAdded] will be received when this operation has completed
     *
     * @param service the service to add
     * @return true if the operation was enqueued, false otherwise
     */
    fun add(service: BluetoothGattService): Boolean {
        Objects.requireNonNull(service, SERVICE_IS_NULL)
        val result = commandQueue.add(Runnable {
            if (!bluetoothGattServer.addService(service)) {
                Logger.e(TAG, "adding service %s failed", service.uuid)
                completedCommand()
            }
        })
        if (result) {
            nextCommand()
        } else {
            Logger.e(TAG, "could not enqueue add service command")
        }
        return result
    }

    /**
     * Remove a service
     *
     * @param service the service to remove
     * @return true if the service was removed, otherwise false
     */
    fun remove(service: BluetoothGattService): Boolean {
        Objects.requireNonNull(service, SERVICE_IS_NULL)
        return bluetoothGattServer.removeService(service)
    }

    /**
     * Remove all services
     */
    fun removeAllServices() {
        bluetoothGattServer.clearServices()
    }

    /**
     * Get a list of the all advertised services of this peripheral
     *
     * @return a list of zero or more services
     */
    val services: List<BluetoothGattService>
        get() = bluetoothGattServer.services

    /**
     * Send a notification or indication that a local characteristic has been
     * updated
     *
     *
     * A notification or indication is sent to all remote centrals to signal
     * that the characteristic has been updated.
     *
     * @param characteristic the characteristic for which to send a notification
     * @return true if the operation was enqueued, otherwise false
     */
    fun notifyCharacteristicChanged(value: ByteArray, characteristic: BluetoothGattCharacteristic): Boolean {
        Objects.requireNonNull(value, CHARACTERISTIC_VALUE_IS_NULL)
        Objects.requireNonNull(characteristic, CHARACTERISTIC_IS_NULL)
        if (doesNotSupportNotifying(characteristic)) return false
        var result = true
        for (device in connectedDevices) {
            if (!notifyCharacteristicChanged(copyOf(value), device, characteristic)) {
                result = false
            }
        }
        return result
    }

    private fun notifyCharacteristicChanged(value: ByteArray, bluetoothDevice: BluetoothDevice, characteristic: BluetoothGattCharacteristic): Boolean {
        Objects.requireNonNull(value, CHARACTERISTIC_VALUE_IS_NULL)
        Objects.requireNonNull(bluetoothDevice, DEVICE_IS_NULL)
        Objects.requireNonNull(characteristic, CHARACTERISTIC_IS_NULL)
        Objects.requireNonNull(characteristic.value, CHARACTERISTIC_VALUE_IS_NULL)
        if (doesNotSupportNotifying(characteristic)) return false
        val confirm = supportsIndicate(characteristic)
        val result = commandQueue.add(Runnable {
            currentNotifyValue = value
            currentNotifyCharacteristic = characteristic
            characteristic.value = value
            if (!bluetoothGattServer.notifyCharacteristicChanged(bluetoothDevice, characteristic, confirm)) {
                Logger.e(TAG, "notifying characteristic changed failed for <%s>", characteristic.uuid)
                completedCommand()
            }
        })
        if (result) {
            nextCommand()
        } else {
            Logger.e(TAG, "could not enqueue notify command")
        }
        return result
    }

    /**
     * Cancel a connection to a Central
     *
     * @param bluetoothCentral the Central
     */
    fun cancelConnection(bluetoothCentral: BluetoothCentral) {
        Objects.requireNonNull(bluetoothCentral, CENTRAL_IS_NULL)
        cancelConnection(bluetoothAdapter.getRemoteDevice(bluetoothCentral.address))
    }

    private fun cancelConnection(bluetoothDevice: BluetoothDevice) {
        Objects.requireNonNull(bluetoothDevice, DEVICE_IS_NULL)
        Logger.i(TAG, "cancelConnection with '%s' (%s)", bluetoothDevice.name ?: "null", bluetoothDevice.address)
        bluetoothGattServer.cancelConnection(bluetoothDevice)
    }

    private val connectedDevices: List<BluetoothDevice>
        get() = bluetoothManager.getConnectedDevices(BluetoothGattServer.GATT)

    /**
     * Get the set of connected Centrals
     *
     * @return a set with zero or more connected Centrals
     */
    val connectedCentrals: Set<BluetoothCentral>
        get() {
            val bluetoothCentrals: Set<BluetoothCentral> = HashSet(connectedCentralsMap.values)
            return Collections.unmodifiableSet(bluetoothCentrals)
        }

    /**
     * The current command has been completed, move to the next command in the queue (if any)
     */
    private fun completedCommand() {
        commandQueue.poll()
        commandQueueBusy = false
        nextCommand()
    }

    /**
     * Execute the next command in the subscribe queue.
     * A queue is used because some calls have to be executed sequentially.
     */
    private fun nextCommand() {
        synchronized(this) {

            // If there is still a command being executed, then bail out
            if (commandQueueBusy) return

            // Check if there is something to do at all
            val bluetoothCommand = commandQueue.peek() ?: return

            // Execute the next command in the queue
            commandQueueBusy = true
            mainHandler.post {
                try {
                    bluetoothCommand.run()
                } catch (ex: Exception) {
                    Logger.e(TAG,"command exception")
                    Logger.e(TAG, ex.toString())
                    completedCommand()
                }
            }
        }
    }

    fun getCentral(address: String): BluetoothCentral? {
        Objects.requireNonNull(address, ADDRESS_IS_NULL)
        return connectedCentralsMap[address]
    }

    private fun getCentral(device: BluetoothDevice): BluetoothCentral {
        Objects.requireNonNull(device, DEVICE_IS_NULL)
        var result = connectedCentralsMap[device.address]
        if (result == null) {
            result = BluetoothCentral(device.address, device.name)
        }
        return result
    }

    private fun removeCentral(device: BluetoothDevice) {
        Objects.requireNonNull(device, DEVICE_IS_NULL)
        connectedCentralsMap.remove(device.address)
    }

    private val adapterStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                handleAdapterState(state)
            }
        }
    }

    private fun handleAdapterState(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_OFF -> {
                Logger.d(TAG, "bluetooth turned off")
                cancelAllConnectionsWhenBluetoothOff()
            }
            BluetoothAdapter.STATE_TURNING_OFF -> Logger.d(TAG, "bluetooth turning off")
            BluetoothAdapter.STATE_ON -> Logger.d(TAG, "bluetooth turned on")
            BluetoothAdapter.STATE_TURNING_ON -> Logger.d(TAG, "bluetooth turning on")
        }
    }

    private fun cancelAllConnectionsWhenBluetoothOff() {
        val bluetoothCentrals = connectedCentrals
        for (bluetoothCentral in bluetoothCentrals) {
            bluetoothGattServerCallback.onConnectionStateChange(bluetoothAdapter.getRemoteDevice(bluetoothCentral.address), 0, BluetoothProfile.STATE_DISCONNECTED)
        }
        onAdvertisingStopped()
    }

    private fun copyOf(source: ByteArray, offset: Int, maxSize: Int): ByteArray {
        if (source.size > maxSize) {
            val chunkSize = Math.min(source.size - offset, maxSize)
            return Arrays.copyOfRange(source, offset, offset + chunkSize)
        }
        return Arrays.copyOf(source, source.size)
    }

    /**
     * Make a safe copy of a nullable byte array
     *
     * @param source byte array to copy
     * @return non-null copy of the source byte array or an empty array if source was null
     */
    fun copyOf(source: ByteArray?): ByteArray {
        return if (source == null) ByteArray(0) else Arrays.copyOf(source, source.size)
    }

    /**
     * Make a byte array nonnull by either returning the original byte array if non-null or an empty bytearray
     *
     * @param source byte array to make nonnull
     * @return the source byte array or an empty array if source was null
     */
    private fun nonnullOf(source: ByteArray?): ByteArray {
        return source ?: ByteArray(0)
    }

    private fun supportsNotify(characteristic: BluetoothGattCharacteristic): Boolean {
        return characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY > 0
    }

    private fun supportsIndicate(characteristic: BluetoothGattCharacteristic): Boolean {
        return characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE > 0
    }

    private fun doesNotSupportNotifying(characteristic: BluetoothGattCharacteristic): Boolean {
        return !(supportsIndicate(characteristic) || supportsNotify(characteristic))
    }

    companion object {
        private val TAG = BluetoothPeripheralManager::class.simpleName.toString()
        val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SERVICE_IS_NULL = "service is null"
        private const val CHARACTERISTIC_IS_NULL = "characteristic is null"
        private const val DEVICE_IS_NULL = "device is null"
        private const val CHARACTERISTIC_VALUE_IS_NULL = "characteristic value is null"
        private const val CENTRAL_IS_NULL = "central is null"
        private const val ADDRESS_IS_NULL = "address is null"
    }

    private val bluetoothGattServer: BluetoothGattServer = bluetoothManager.openGattServer(context, bluetoothGattServerCallback)

    /**
     * Create a BluetoothPeripheralManager
     *
     * @param context the application context
     * @param bluetoothManager a valid BluetoothManager
     * @param callback an instance of BluetoothPeripheralManagerCallback where the callbacks will be handled
     */
    init {
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser


        // Register for broadcasts on BluetoothAdapter state change
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(adapterStateReceiver, filter)
    }
}
