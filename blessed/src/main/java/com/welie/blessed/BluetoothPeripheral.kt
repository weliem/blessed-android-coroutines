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
import android.bluetooth.*
import android.bluetooth.BluetoothGattDescriptor.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.*
import java.lang.Runnable
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Represents a remote Bluetooth peripheral and replaces BluetoothDevice and BluetoothGatt
 *
 * A [BluetoothPeripheral] lets you create a connection with the peripheral or query information about it.
 * This class is a wrapper around the [BluetoothDevice] and [BluetoothGatt] classes.
 * It takes care of operation queueing, some Android bugs, and provides several convenience functions.
 */
@SuppressLint("MissingPermission")
@Suppress("unused", "deprecation")
class BluetoothPeripheral internal constructor(
    private val context: Context,
    private var device: BluetoothDevice,
    private val listener: InternalCallback
) {
    private val commandQueue: Queue<Runnable> = ConcurrentLinkedQueue()

    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null
    private var cachedName = ""
    private var currentWriteBytes = ByteArray(0)
    private var currentCommand = IDLE
    private var currentResultCallback: BluetoothPeripheralCallback = BluetoothPeripheralCallback.NULL()
    private val notifyingCharacteristics: MutableSet<BluetoothGattCharacteristic> = HashSet()
    private var observeMap: MutableMap<BluetoothGattCharacteristic, (value: ByteArray) -> Unit> = HashMap()

    @Volatile
    private var commandQueueBusy = false
    private var isRetrying = false
    private var bondLost = false
    private var manuallyBonding = false
    private var discoveryStarted = false

    @Volatile
    private var state = BluetoothProfile.STATE_DISCONNECTED
    private var nrTries = 0
    private var connectTimestamp: Long = 0

    private val callbackScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var discoverJob: Job? = null

    /**
     * Returns the currently set MTU
     *
     * @return the MTU
     */
    var currentMtu = DEFAULT_MTU
        private set

    /**
     * This abstract class is used to implement BluetoothGatt callbacks.
     */
    private val bluetoothGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState != BluetoothProfile.STATE_CONNECTING) cancelConnectionTimer()
            val previousState = state
            state = newState

            val hciStatus = HciStatus.fromValue(status)
            if (hciStatus == HciStatus.SUCCESS) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> successfullyConnected()
                    BluetoothProfile.STATE_DISCONNECTED -> successfullyDisconnected(previousState)
                    BluetoothProfile.STATE_DISCONNECTING -> {
                        Logger.d(TAG, "peripheral is disconnecting")
                        listener.disconnecting(this@BluetoothPeripheral)
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        Logger.d(TAG, "peripheral is connecting")
                        listener.connecting(this@BluetoothPeripheral)
                    }
                    else -> Logger.e(TAG, "unknown state received")
                }
            } else {
                connectionStateChangeUnsuccessful(hciStatus, previousState, newState)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val gattStatus = GattStatus.fromValue(status)

            if (gattStatus != GattStatus.SUCCESS) {
                Logger.e(TAG, "service discovery failed due to internal error '%s', disconnecting", gattStatus)
                disconnect()
                return
            }

            Logger.d(TAG, "discovered %d services for '%s'", gatt.services.size, name)

            // Issue 'connected' since we are now fully connect incl service discovery
            listener.connected(this@BluetoothPeripheral)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            val parentCharacteristic = descriptor.characteristic
            val resultCallback = currentResultCallback

            if (gattStatus != GattStatus.SUCCESS) {
                Logger.e(TAG, 
                    "failed to write <%s> to descriptor of characteristic <%s> for device: '%s', status '%s' ",
                    BluetoothBytesParser.bytes2String(currentWriteBytes),
                    parentCharacteristic.uuid,
                    address,
                    gattStatus
                )
            }

            val value = currentWriteBytes
            currentWriteBytes = ByteArray(0)

            if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                if (gattStatus == GattStatus.SUCCESS) {
                    if (value.contentEquals(ENABLE_NOTIFICATION_VALUE) ||
                        value.contentEquals(ENABLE_INDICATION_VALUE)
                    ) {
                        notifyingCharacteristics.add(parentCharacteristic)
                    } else if (value.contentEquals(DISABLE_NOTIFICATION_VALUE)) {
                        notifyingCharacteristics.remove(parentCharacteristic)
                    }
                }
                callbackScope.launch { resultCallback.onNotificationStateUpdate(this@BluetoothPeripheral, parentCharacteristic, gattStatus) }
            } else {
                callbackScope.launch { resultCallback.onDescriptorWrite(this@BluetoothPeripheral, value, descriptor, gattStatus) }
            }
            completedCommand()
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray
        ) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus != GattStatus.SUCCESS) {
                Logger.e(TAG, "reading descriptor <%s> failed for device '%s, status '%s'", descriptor.uuid, address, gattStatus)
            }

            val resultCallback = currentResultCallback
            callbackScope.launch { resultCallback.onDescriptorRead(this@BluetoothPeripheral, value, descriptor, gattStatus) }
            completedCommand()
        }

        @Deprecated("Deprecated in Java")
        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                onDescriptorRead(gatt, descriptor, status, nonnullOf(descriptor.value))
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            callbackScope.launch { observeMap[characteristic]?.invoke(value) }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                onCharacteristicChanged(gatt, characteristic, nonnullOf(characteristic.value))
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus != GattStatus.SUCCESS) {
                Logger.e(TAG, "read failed for characteristic <%s>, status '%s'", characteristic.uuid, gattStatus)
            }

            val resultCallback = currentResultCallback
            callbackScope.launch { resultCallback.onCharacteristicRead(this@BluetoothPeripheral, value, characteristic, gattStatus) }
            completedCommand()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                onCharacteristicRead(gatt, characteristic, nonnullOf(characteristic.value), status)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus != GattStatus.SUCCESS) {
                Logger.e(TAG, "writing <%s> to characteristic <%s> failed, status '%s'", BluetoothBytesParser.bytes2String(currentWriteBytes), characteristic.uuid, gattStatus)
            }

            val value = currentWriteBytes
            currentWriteBytes = ByteArray(0)
            val resultCallback = currentResultCallback
            callbackScope.launch { resultCallback.onCharacteristicWrite(this@BluetoothPeripheral, value, characteristic, gattStatus) }
            completedCommand()
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus != GattStatus.SUCCESS) {
                Logger.e(TAG, "reading RSSI failed, status '%s'", gattStatus)
            }

            val resultCallback = currentResultCallback
            callbackScope.launch { resultCallback.onReadRemoteRssi(this@BluetoothPeripheral, rssi, gattStatus) }
            completedCommand()
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus != GattStatus.SUCCESS) {
                Logger.e(TAG, "change MTU failed, status '%s'", gattStatus)
            }

            currentMtu = mtu
            val resultCallback = currentResultCallback
            callbackScope.launch { resultCallback.onMtuChanged(this@BluetoothPeripheral, mtu, gattStatus) }

            // Only complete the command if we initiated the operation. It can also be initiated by the remote peripheral...
            if (currentCommand == REQUEST_MTU_COMMAND) {
                currentCommand = IDLE
                completedCommand()
            }
        }

        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus != GattStatus.SUCCESS) {
                Logger.e(TAG, "read Phy failed, status '%s'", gattStatus)
            } else {
                Logger.d(TAG, "updated Phy: tx = %s, rx = %s", PhyType.fromValue(txPhy), PhyType.fromValue(rxPhy))
            }

            val resultCallback = currentResultCallback
            callbackScope.launch { resultCallback.onPhyUpdate(this@BluetoothPeripheral, PhyType.fromValue(txPhy), PhyType.fromValue(rxPhy), gattStatus) }
            completedCommand()
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus != GattStatus.SUCCESS) {
                Logger.e(TAG, "update Phy failed, status '%s'", gattStatus)
            } else {
                Logger.d(TAG, "updated Phy: tx = %s, rx = %s", PhyType.fromValue(txPhy), PhyType.fromValue(rxPhy))
            }

            val resultCallback = currentResultCallback
            callbackScope.launch { resultCallback.onPhyUpdate(this@BluetoothPeripheral, PhyType.fromValue(txPhy), PhyType.fromValue(rxPhy), gattStatus) }

            // Only complete the command if we initiated the operation. It can also be initiated by the remote peripheral...
            if (currentCommand == SET_PHY_TYPE_COMMAND) {
                currentCommand = IDLE
                completedCommand()
            }
        }

        /**
         * This callback is only called from Android 8 (Oreo) or higher
         */
        @Suppress("UNUSED_PARAMETER")
        fun onConnectionUpdated(gatt: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
            val gattStatus = GattStatus.fromValue(status)
            if (gattStatus == GattStatus.SUCCESS) {
                val msg = String.format(Locale.ENGLISH, "connection parameters: interval=%.1fms latency=%d timeout=%ds", interval * 1.25f, latency, timeout / 100)
                Logger.d(TAG, msg)
            } else {
                Logger.e(TAG, "connection parameters update failed with status '%s'", gattStatus)
            }
        }
    }

    private fun successfullyConnected() {
        val timePassed = SystemClock.elapsedRealtime() - connectTimestamp
        Logger.d(TAG, "connected to '%s' (%s) in %.1fs", name, bondState, timePassed / 1000.0f)

        if (bondState == BondState.NONE || bondState == BondState.BONDED) {
            discoverServices()
        } else if (bondState == BondState.BONDING) {
            // Apparently the bonding process has already started, so let it complete.
            // We'll do discoverServices() when bonding finishes
            Logger.d(TAG, "waiting for bonding to complete")
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices() {
        discoverJob = scope.launch {
            Logger.d(TAG, "discovering services of '%s'", name)
            if (bluetoothGatt != null && (bluetoothGatt?.discoverServices() == true)) {
                discoveryStarted = true
            } else {
                Logger.e(TAG, "discoverServices failed to start")
            }
        }
    }

    private fun successfullyDisconnected(previousState: Int) {
        if (previousState == BluetoothProfile.STATE_CONNECTED || previousState == BluetoothProfile.STATE_DISCONNECTING) {
            Logger.d(TAG, "disconnected '%s' on request", name)
        } else if (previousState == BluetoothProfile.STATE_CONNECTING) {
            Logger.d(TAG, "cancelling connect attempt")
        }
        if (bondLost) {
            completeDisconnect(false, HciStatus.SUCCESS)

            // Consider the loss of the bond a connection failure so that a connection retry will take place
            scope.launch {
                // Give the stack some time to register the bond loss internally. This is needed on most phones...
                delay(DELAY_AFTER_BOND_LOST)

                listener.connectFailed(this@BluetoothPeripheral, HciStatus.SUCCESS)
            }
        } else {
            completeDisconnect(true, HciStatus.SUCCESS)
        }
    }

    private fun connectionStateChangeUnsuccessful(status: HciStatus, previousState: Int, newState: Int) {
        cancelPendingServiceDiscovery()
        val servicesDiscovered = services.isNotEmpty()

        // See if the initial connection failed
        if (previousState == BluetoothProfile.STATE_CONNECTING) {
            val timePassed = SystemClock.elapsedRealtime() - connectTimestamp
            val isTimeout = timePassed > timeoutThreshold
            val adjustedStatus = if (status == HciStatus.ERROR && isTimeout) HciStatus.CONNECTION_FAILED_ESTABLISHMENT else status
            Logger.d(TAG, "connection failed with status '%s'", adjustedStatus)
            completeDisconnect(false, adjustedStatus)
            listener.connectFailed(this@BluetoothPeripheral, adjustedStatus)
        } else if (previousState == BluetoothProfile.STATE_CONNECTED && newState == BluetoothProfile.STATE_DISCONNECTED && !servicesDiscovered) {
            // We got a disconnection before the services were even discovered
            Logger.d(TAG, "peripheral '%s' disconnected with status '%s' (%d) before completing service discovery", name, status, status.value)
            completeDisconnect(false, status)
            listener.connectFailed(this@BluetoothPeripheral, status)
        } else {
            // See if we got connection drop
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Logger.d(TAG, "peripheral '%s' disconnected with status '%s' (%d)", name, status, status.value)
            } else {
                Logger.d(TAG, "unexpected connection state change for '%s' status '%s' (%d)", name, status, status.value)
            }
            completeDisconnect(true, status)
        }
    }

    private fun cancelPendingServiceDiscovery() {
        discoverJob?.cancel()
        discoverJob = null
    }

    private val bondStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val receivedDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return

            // Ignore updates for other devices
            if (!receivedDevice.address.equals(address, ignoreCase = true)) return
            if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                handleBondStateChange(bondState, previousBondState)
            }
        }
    }

    private fun handleBondStateChange(bondState: Int, previousBondState: Int) {
        when (bondState) {
            BluetoothDevice.BOND_BONDING -> {
                Logger.d(TAG, "starting bonding with '%s' (%s)", name, address)
                callbackScope.launch { bondStateCallback.invoke(BondState.fromValue(bondState)) }
            }
            BluetoothDevice.BOND_BONDED -> {
                Logger.d(TAG, "bonded with '%s' (%s)", name, address)
                callbackScope.launch { bondStateCallback.invoke(BondState.fromValue(bondState)) }

                // If bonding was started at connection time, we may still have to discover the services
                // Also make sure we are not starting a discovery while another one is already in progress
                if (services.isEmpty() && !discoveryStarted) {
                    discoverServices()
                }
                
                if (manuallyBonding) {
                    manuallyBonding = false
                    completedCommand()
                }
            }
            BluetoothDevice.BOND_NONE -> {
                if (previousBondState == BluetoothDevice.BOND_BONDING) {
                    Logger.e(TAG, "bonding failed for '%s', disconnecting device", name)
                    callbackScope.launch { bondStateCallback.invoke(BondState.BONDING_FAILED) }

                } else {
                    Logger.e(TAG, "bond lost for '%s'", name)
                    bondLost = true
                    
                    cancelPendingServiceDiscovery()
                    callbackScope.launch { bondStateCallback.invoke(BondState.BOND_LOST) }
                }
                disconnect()
            }
        }
    }

    private val pairingRequestBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val receivedDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (!receivedDevice.address.equals(address, ignoreCase = true)) return
            val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
            
            Logger.d(TAG, "pairing request received: %s (%s)", pairingVariantToString(variant), variant)
            if (variant == PAIRING_VARIANT_PIN) {
                val pin = listener.getPincode(this@BluetoothPeripheral)
                if (pin != null) {
                    Logger.d(TAG, "setting PIN code for this peripheral using '%s'", pin)
                    receivedDevice.setPin(pin.toByteArray())
                    abortBroadcast()
                }
            }
        }
    }

    fun setDevice(bluetoothDevice: BluetoothDevice) {
        device = bluetoothDevice
    }

    /**
     * Connect directly with the bluetooth device. This call will timeout in max 30 seconds (5 seconds on Samsung phones)
     */
    fun connect() {
        if (state == BluetoothProfile.STATE_DISCONNECTED) {
            scope.launch {
                delay(DIRECT_CONNECTION_DELAY_IN_MS)
                Logger.d(TAG, "connect to '%s' (%s) using TRANSPORT_LE", name, address)
                registerBondingBroadcastReceivers()
                discoveryStarted = false
                connectTimestamp = SystemClock.elapsedRealtime()
                startConnectionTimer(this@BluetoothPeripheral)
                bluetoothGatt = try {
                    device.connectGatt(context, false, bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE)
                } catch (e: SecurityException) {
                    Logger.d(TAG, "exception")
                    cancelConnectionTimer()
                    null
                }
                
                bluetoothGatt?.let {
                    bluetoothGattCallback.onConnectionStateChange(it, HciStatus.SUCCESS.value, BluetoothProfile.STATE_CONNECTING)
                }
            }
        } else {
            Logger.e(TAG, "peripheral '%s' not yet disconnected, will not connect", name)
        }
    }

    /**
     * Try to connect to a device whenever it is found by the OS. This call never times out.
     * Connecting to a device will take longer than when using connect()
     */
    fun autoConnect() {
        // Note that this will only work for devices that are known! After turning BT on/off Android doesn't know the device anymore!
        // https://stackoverflow.com/questions/43476369/android-save-ble-device-to-reconnect-after-app-close
        if (state == BluetoothProfile.STATE_DISCONNECTED) {
            scope.launch {
                Logger.d(TAG, "autoConnect to '%s' (%s) using TRANSPORT_LE", name, address)
                registerBondingBroadcastReceivers()
                discoveryStarted = false
                bluetoothGatt = try {
                    device.connectGatt(context, true, bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE)
                } catch (e: SecurityException) {
                    Logger.e(TAG, "connectGatt exception")
                    null
                }
                bluetoothGatt?.let {
                    bluetoothGattCallback.onConnectionStateChange(it, HciStatus.SUCCESS.value, BluetoothProfile.STATE_CONNECTING)
                    connectTimestamp = SystemClock.elapsedRealtime()
                }
            }
        } else {
            Logger.e(TAG, "peripheral '%s' not yet disconnected, will not connect", name)
        }
    }

    private fun registerBondingBroadcastReceivers() {
        context.registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        context.registerReceiver(pairingRequestBroadcastReceiver, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST))
    }

    /**
     * Create a bond with the peripheral.
     *
     *
     * If a (auto)connect has been issued, the bonding command will be enqueued and you will
     * receive updates via the [BluetoothPeripheralCallback]. Otherwise the bonding will
     * be done immediately and no updates via the callback will happen.
     *
     * @return true if bonding was started/enqueued, false if not
     */

    fun createBond(): Boolean {
        // Check if we have a Gatt object
        if (bluetoothGatt == null) {
            // No gatt object so no connection issued, do create bond immediately
            registerBondingBroadcastReceivers()
            return device.createBond()
        }

        // Enqueue the bond command because a connection has been issued or we are already connected
        return enqueue {
            manuallyBonding = true
            if (!device.createBond()) {
                Logger.e(TAG, "bonding failed for %s", address)
                completedCommand()
            } else {
                Logger.d(TAG, "manually bonding %s", address)
                nrTries++
            }
        }
    }

    /**
     * Cancel an active or pending connection.
     *
     *
     * This operation is asynchronous and you will receive a callback on onDisconnectedPeripheral.
     */
    internal fun cancelConnection() {
        // Check if we have a Gatt object
        if (bluetoothGatt == null) {
            Logger.w(TAG, "cannot cancel connection because no connection attempt is made yet")
            return
        }

        // Check if we are not already disconnected or disconnecting
        if (state == BluetoothProfile.STATE_DISCONNECTED || state == BluetoothProfile.STATE_DISCONNECTING) {
            return
        }

        // Cancel the connection timer
        cancelConnectionTimer()

        // Check if we are in the process of connecting
        if (state == BluetoothProfile.STATE_CONNECTING) {
            // Cancel the connection by calling disconnect
            disconnect()

            // Since we will not get a callback on onConnectionStateChange for this, we issue the disconnect ourselves
            scope.launch {
                delay(50)
                bluetoothGatt?.let {
                    bluetoothGattCallback.onConnectionStateChange(bluetoothGatt, HciStatus.SUCCESS.value, BluetoothProfile.STATE_DISCONNECTED)
                }
            }
        } else {
            // Cancel active connection and onConnectionStateChange will be called by Android
            disconnect()
        }
    }

    /**
     * Disconnect the bluetooth peripheral.
     *
     *
     * When the disconnection has been completed [BluetoothCentralManagerCallback.onDisconnectedPeripheral] will be called.
     */
    private fun disconnect() {
        if (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_CONNECTING) {
            bluetoothGatt?.let {
                bluetoothGattCallback.onConnectionStateChange(it, HciStatus.SUCCESS.value, BluetoothProfile.STATE_DISCONNECTING)
            }

            scope.launch {
                if (state == BluetoothProfile.STATE_DISCONNECTING && bluetoothGatt != null) {
                    bluetoothGatt?.disconnect()
                    Logger.i(TAG, "force disconnect '%s' (%s)", name, address)
                }
            }
        } else {
            listener.disconnected(this@BluetoothPeripheral, HciStatus.SUCCESS)
        }
    }

    fun disconnectWhenBluetoothOff() {
        completeDisconnect(true, HciStatus.SUCCESS)
    }

    /**
     * Complete the disconnect after getting connectionstate == disconnected
     */
    private fun completeDisconnect(notify: Boolean, status: HciStatus) {
        if (bluetoothGatt != null) {
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
        commandQueue.clear()
        commandQueueBusy = false
        notifyingCharacteristics.clear()
        currentMtu = DEFAULT_MTU
        currentCommand = IDLE
        manuallyBonding = false
        discoveryStarted = false
        try {
            context.unregisterReceiver(bondStateReceiver)
            context.unregisterReceiver(pairingRequestBroadcastReceiver)
        } catch (e: IllegalArgumentException) {
            // In case bluetooth is off, unregistering broadcast receivers may fail
        }
        bondLost = false
        if (notify) {
            listener.disconnected(this@BluetoothPeripheral, status)
        }
    }

    /**
     * Get the mac address of the bluetooth peripheral.
     *
     * @return Address of the bluetooth peripheral
     */
    val address: String
        get() = device.address

    /**
     * Get the type of the peripheral.
     *
     * @return the PeripheralType
     */
    val type: PeripheralType
        get() = PeripheralType.fromValue(device.type)

    /**
     * Get the name of the bluetooth peripheral.
     *
     * @return name of the bluetooth peripheral
     */
    val name: String
        get() {
            val name = device.name
            if (name != null) {
                // Cache the name so that we even know it when bluetooth is switched off
                cachedName = name
                return name
            }
            return cachedName
        }

    /**
     * Get the bond state of the bluetooth peripheral.
     *
     * @return the bond state
     */
    val bondState: BondState
        get() = BondState.fromValue(device.bondState)

    var bondStateCallback: (state: BondState) -> Unit = {}

    fun observeBondState(callback: (state: BondState) -> Unit) {
        this.bondStateCallback = callback
    }

    /**
     * Get the services supported by the connected bluetooth peripheral.
     * Only services that are also supported by [BluetoothCentralManager] are included.
     *
     * @return Supported services.
     */
    val services: List<BluetoothGattService>
        get() = bluetoothGatt?.services ?: emptyList()

    /**
     * Get the BluetoothGattService object for a service UUID.
     *
     * @param serviceUUID the UUID of the service
     * @return the BluetoothGattService object for the service UUID or null if the peripheral does not have a service with the specified UUID
     */
    fun getService(serviceUUID: UUID): BluetoothGattService? {
        return bluetoothGatt?.getService(serviceUUID)
    }

    /**
     * Get the BluetoothGattCharacteristic object for a characteristic UUID.
     *
     * @param serviceUUID        the service UUID the characteristic is part of
     * @param characteristicUUID the UUID of the characteristic
     * @return the BluetoothGattCharacteristic object for the characteristic UUID or null if the peripheral does not have a characteristic with the specified UUID
     */
    fun getCharacteristic(serviceUUID: UUID, characteristicUUID: UUID): BluetoothGattCharacteristic? {
        return getService(serviceUUID)?.getCharacteristic(characteristicUUID)
    }

    /**
     * Returns the connection state of the peripheral.
     *
     * @return the connection state.
     */
    fun getState(): ConnectionState {
        return ConnectionState.fromValue(state)
    }

    /**
     * Get maximum length of byte array that can be written depending on WriteType
     *
     *
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

    /**
     * Boolean to indicate if the specified characteristic is currently notifying or indicating.
     *
     * @param characteristic the characteristic to check
     * @return true if the characteristic is notifying or indicating, false if it is not
     */
    fun isNotifying(characteristic: BluetoothGattCharacteristic): Boolean {
        return notifyingCharacteristics.contains(characteristic)
    }

    /**
     * Get all notifying/indicating characteristics
     *
     * @return Set of characteristics or empty set
     */
    fun getNotifyingCharacteristics(): Set<BluetoothGattCharacteristic> {
        return Collections.unmodifiableSet(notifyingCharacteristics)
    }

    private val isConnected: Boolean
        get() = bluetoothGatt != null && state == BluetoothProfile.STATE_CONNECTED

    private fun notConnected(): Boolean {
        return !isConnected
    }

    /**
     * Check if the peripheral is uncached by the Android BLE stack
     *
     * @return true if unchached, otherwise false
     */
    val isUncached: Boolean
        get() = type == PeripheralType.UNKNOWN

    /**
     * Read the value of a characteristic.
     *
     * The characteristic must support reading it, otherwise the operation will not be enqueued.
     *
     * @param serviceUUID        the service UUID the characteristic belongs to
     * @param characteristicUUID the characteristic's UUID
     * @return the bytes that were read or an empty byte array if the characteristic was not found
     * @throws IllegalArgumentException if the characteristic is not readable
     */
    suspend fun readCharacteristic(serviceUUID: UUID, characteristicUUID: UUID): ByteArray {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }

        val characteristic = getCharacteristic(serviceUUID, characteristicUUID)
        return if (characteristic != null) {
            readCharacteristic(characteristic)
        } else {
            ByteArray(0)
        }
    }

    /**
     * Read the value of a characteristic.
     *
     * @param characteristic Specifies the characteristic to read.
     * @return the bytes that were read or an empty byte array if the characteristic was not found
     * @throws IllegalArgumentException if the characteristic is not readable
     */
    suspend fun readCharacteristic(characteristic: BluetoothGattCharacteristic): ByteArray =
        suspendCoroutine {
            try {
                val result = readCharacteristic(characteristic, object : BluetoothPeripheralCallback() {
                    override fun onCharacteristicRead(peripheral: BluetoothPeripheral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
                        if (status == GattStatus.SUCCESS) {
                            it.resume(value)
                        } else {
                            it.resumeWithException(GattException(status))
                        }
                    }
                })
                if (!result) {
                    it.resume(ByteArray(0))
                }
            } catch (e: IllegalArgumentException) {
                it.resumeWithException(e)
            }
        }

    /**
     * Read the value of a characteristic.
     *
     * The characteristic must support reading it, otherwise the operation will not be enqueued.
     * [BluetoothPeripheralCallback.onCharacteristicUpdate]   will be triggered as a result of this call.
     *
     * @param characteristic Specifies the characteristic to read.
     * @return true if the operation was enqueued, false if the characteristic does not support reading or the characteristic was invalid
     */
    private fun readCharacteristic(characteristic: BluetoothGattCharacteristic, resultCallback: BluetoothPeripheralCallback): Boolean {
        require(characteristic.supportsReading()) { "characteristic does not have read property" }
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }

        return enqueue {
            if (isConnected) {
                currentResultCallback = resultCallback
                if (bluetoothGatt?.readCharacteristic(characteristic) == true) {
                    Logger.d(TAG, "reading characteristic <%s>", characteristic.uuid)
                    nrTries++
                } else {
                    Logger.e(TAG, "readCharacteristic failed for characteristic: %s", characteristic.uuid)
                    resultCallback.onCharacteristicRead(this@BluetoothPeripheral, ByteArray(0), characteristic, GattStatus.READ_NOT_PERMITTED)
                    completedCommand()
                }
            } else {
                resultCallback.onCharacteristicRead(this@BluetoothPeripheral, ByteArray(0), characteristic, GattStatus.CONNECTION_CANCELLED)
                completedCommand()
            }
        }
    }



    suspend fun writeCharacteristic(serviceUUID: UUID, characteristicUUID: UUID, value: ByteArray, writeType: WriteType): ByteArray {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }

        val characteristic = getCharacteristic(serviceUUID, characteristicUUID)
        return if (characteristic != null) {
            writeCharacteristic(characteristic, value, writeType)
        } else {
            ByteArray(0)
        }
    }


    /**
     * Write a value to a characteristic using the specified write type.
     *
     * All parameters must have a valid value in order for the operation to be enqueued.
     * If the characteristic does not support writing with the specified writeType, the operation will not be enqueued.
     *
     * @param characteristic the characteristic to write to
     * @param value              the byte array to write
     * @param writeType          the write type to use when writing. Must be WRITE_TYPE_DEFAULT, WRITE_TYPE_NO_RESPONSE or WRITE_TYPE_SIGNED
     * @return the byte array that was written or an empty byte array if the characteristic was not valid
     */
    suspend fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray, writeType: WriteType): ByteArray =
        suspendCoroutine {
            try {
                val result = writeCharacteristic(characteristic, value, writeType, object : BluetoothPeripheralCallback() {
                    override fun onCharacteristicWrite(peripheral: BluetoothPeripheral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
                        if (status == GattStatus.SUCCESS) {
                            it.resume(value)
                        } else {
                            it.resumeWithException(GattException(status))
                        }
                    }
                })

                if (!result) {
                    it.resume(ByteArray(0))
                }
            } catch (e: IllegalArgumentException) {
                it.resumeWithException(e)
            }
        }


    /**
     * Write a value to a characteristic using the specified write type.
     *
     *
     * All parameters must have a valid value in order for the operation
     * to be enqueued. If the characteristic does not support writing with the specified writeType, the operation will not be enqueued.
     * The length of the byte array to write must be between 1 and getMaximumWriteValueLength(writeType).
     *
     *
     * [BluetoothPeripheralCallback.onCharacteristicWrite] will be triggered as a result of this call.
     *
     * @param characteristic the characteristic to write to
     * @param value          the byte array to write
     * @param writeType      the write type to use when writing.
     * @return true if a write operation was successfully enqueued, otherwise false
     */
    private fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray, writeType: WriteType, resultCallback: BluetoothPeripheralCallback): Boolean {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }
        require(value.isNotEmpty()) { VALUE_BYTE_ARRAY_IS_EMPTY }
        require(value.size <= getMaximumWriteValueLength(writeType)) { VALUE_BYTE_ARRAY_IS_TOO_LONG }
        require(characteristic.supportsWriteType(writeType)) { "characteristic <${characteristic.uuid}> does not support writeType '$writeType'" }

        // Copy the value to avoid race conditions
        val bytesToWrite = copyOf(value)
        return enqueue {
            if (isConnected) {
                currentResultCallback = resultCallback
                currentWriteBytes = bytesToWrite
                characteristic.writeType = writeType.writeType
                if (willCauseLongWrite(bytesToWrite, writeType)) {
                    // Android will turn this into a Long Write because it is larger than the MTU - 3.
                    // When doing a Long Write the byte array will be automatically split in chunks of size MTU - 3.
                    // However, the peripheral's firmware must also support it, so it is not guaranteed to work.
                    // Long writes are also very inefficient because of the confirmation of each write operation.
                    // So it is better to increase MTU if possible. Hence a warning if this write becomes a long write...
                    // See https://stackoverflow.com/questions/48216517/rxandroidble-write-only-sends-the-first-20b
                    Logger.w(TAG, "value byte array is longer than allowed by MTU, write will fail if peripheral does not support long writes")
                }

                if (internalWriteCharacteristic(characteristic, bytesToWrite, writeType)) {
                    Logger.d(TAG, "writing <%s> to characteristic <%s>", bytesToWrite.asHexString(), characteristic.uuid)
                    nrTries++
                } else {
                    Logger.e(TAG, "writeCharacteristic failed for characteristic: %s", characteristic.uuid)
                    resultCallback.onCharacteristicWrite(this@BluetoothPeripheral, ByteArray(0), characteristic, GattStatus.WRITE_NOT_PERMITTED)
                    completedCommand()
                }
            } else {
                resultCallback.onCharacteristicWrite(this@BluetoothPeripheral, ByteArray(0), characteristic, GattStatus.CONNECTION_CANCELLED)
                completedCommand()
            }
        }
    }

    private fun willCauseLongWrite(value: ByteArray, writeType: WriteType): Boolean {
        return value.size > currentMtu - 3 && writeType == WriteType.WITH_RESPONSE
    }

    private fun internalWriteCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: WriteType
    ): Boolean {
        if (bluetoothGatt == null) return false

        currentWriteBytes = value
        return if (Build.VERSION.SDK_INT >= 33) {
            val result = bluetoothGatt?.writeCharacteristic(characteristic, currentWriteBytes, writeType.writeType)
            result == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = writeType.writeType
            characteristic.value = value
            bluetoothGatt!!.writeCharacteristic(characteristic)
        }
    }

    suspend fun readDescriptor(descriptor: BluetoothGattDescriptor): ByteArray =
        suspendCoroutine {
            try {
                val result = readDescriptor(descriptor, object : BluetoothPeripheralCallback() {
                    override fun onDescriptorRead(peripheral: BluetoothPeripheral, value: ByteArray, descriptor: BluetoothGattDescriptor, status: GattStatus) {
                        if (status == GattStatus.SUCCESS) {
                            it.resume(value)
                        } else {
                            it.resumeWithException(GattException(status))
                        }
                    }
                })

                if (!result) {
                    it.resume(ByteArray(0))
                }
            } catch (e: IllegalArgumentException) {
                it.resumeWithException(e)
            }
        }


    /**
     * Read the value of a descriptor.
     *
     * @param descriptor the descriptor to read
     * @return true if a write operation was successfully enqueued, otherwise false
     */
    private fun readDescriptor(descriptor: BluetoothGattDescriptor, resultCallback: BluetoothPeripheralCallback): Boolean {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }

        return enqueue {
            if (isConnected) {
                currentResultCallback = resultCallback
                if (bluetoothGatt?.readDescriptor(descriptor) == true) {
                    Logger.d(TAG, "reading descriptor <%s>", descriptor.uuid)
                    nrTries++
                } else {
                    Logger.e(TAG, "readDescriptor failed for characteristic: %s", descriptor.uuid)
                    resultCallback.onDescriptorRead(this@BluetoothPeripheral, ByteArray(0), descriptor, GattStatus.READ_NOT_PERMITTED)
                    completedCommand()
                }
            } else {
                resultCallback.onDescriptorRead(this@BluetoothPeripheral, ByteArray(0), descriptor, GattStatus.CONNECTION_CANCELLED)
                completedCommand()
            }
        }
    }

    suspend fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray): ByteArray =
        suspendCoroutine {
            try {
                val result = writeDescriptor(descriptor, value, object : BluetoothPeripheralCallback() {
                    override fun onDescriptorWrite(peripheral: BluetoothPeripheral, value: ByteArray, descriptor: BluetoothGattDescriptor, status: GattStatus) {
                        if (status == GattStatus.SUCCESS) {
                            it.resume(value)
                        } else {
                            it.resumeWithException(GattException(status))
                        }
                    }
                })

                if (!result) {
                    it.resume(ByteArray(0))
                }
            } catch (e: IllegalArgumentException) {
                it.resumeWithException(e)
            }
        }

    /**
     * Write a value to a descriptor.
     *
     *
     * For turning on/off notifications use [BluetoothPeripheral.setNotify] instead.
     *
     * @param descriptor the descriptor to write to
     * @param value      the value to write
     * @return true if a write operation was successfully enqueued, otherwise false
     */
    private fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray, resultCallback: BluetoothPeripheralCallback): Boolean {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }
        require(value.isNotEmpty()) { VALUE_BYTE_ARRAY_IS_EMPTY }
        require(value.size <= getMaximumWriteValueLength(WriteType.WITH_RESPONSE)) { VALUE_BYTE_ARRAY_IS_TOO_LONG }

        // Copy the value to avoid race conditions
        val bytesToWrite = copyOf(value)

        return enqueue {
            if (isConnected) {
                currentResultCallback = resultCallback
                if (internalWriteDescriptor(descriptor, bytesToWrite)) {
                    Logger.d(TAG, "writing <%s> to descriptor <%s>", bytesToWrite.asHexString(), descriptor.uuid)
                    nrTries++
                } else {
                    Logger.e(TAG, "writeDescriptor failed for descriptor: %s", descriptor.uuid)
                    resultCallback.onDescriptorWrite(this@BluetoothPeripheral, ByteArray(0), descriptor, GattStatus.WRITE_NOT_PERMITTED)
                    completedCommand()
                }
            } else {
                resultCallback.onDescriptorWrite(this@BluetoothPeripheral, ByteArray(0), descriptor, GattStatus.CONNECTION_CANCELLED)
                completedCommand()
            }
        }
    }

    private fun internalWriteDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean {
        if (bluetoothGatt == null) return false
        currentWriteBytes = value
        return if (Build.VERSION.SDK_INT >= 33) {
            val result = bluetoothGatt?.writeDescriptor(descriptor, value)
            result == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            bluetoothGatt!!.writeDescriptor(descriptor)
        }
    }

    suspend fun observe(characteristic: BluetoothGattCharacteristic, callback: (value: ByteArray) -> Unit): Boolean =
        suspendCoroutine {
            try {
                observeMap[characteristic] = callback
                val result = setNotify(characteristic, true, object : BluetoothPeripheralCallback() {
                    override fun onNotificationStateUpdate(peripheral: BluetoothPeripheral, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
                        if (status == GattStatus.SUCCESS) {
                            Logger.d(TAG, "observing <${characteristic.uuid}> succeeded")
                            it.resume(true)
                        } else {
                            observeMap.remove(characteristic)
                            it.resumeWithException(GattException(status))
                        }
                    }
                })

                if (!result) {
                    it.resume(false)
                }
            } catch (e : IllegalArgumentException) {
                it.resumeWithException(e)
            }
        }

    suspend fun stopObserving(characteristic: BluetoothGattCharacteristic): Boolean =
        suspendCoroutine {
            try {
                observeMap.remove(characteristic)
                val result = setNotify(characteristic, false, object : BluetoothPeripheralCallback() {
                    override fun onNotificationStateUpdate(peripheral: BluetoothPeripheral, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
                        if (status == GattStatus.SUCCESS) {
                            Logger.d(TAG, "stopped observing <${characteristic.uuid}>")
                            it.resume(true)
                        } else {
                            it.resumeWithException(GattException(status))
                        }
                    }
                })

                if (!result) {
                    it.resume(false)
                }
            } catch (e : IllegalArgumentException) {
                it.resumeWithException(e)
            }
        }

    /**
     * Set the notification state of a characteristic to 'on' or 'off'. The characteristic must support notifications or indications.
     *
     * @param serviceUUID        the service UUID the characteristic belongs to
     * @param characteristicUUID the characteristic's UUID
     * @param enable             true for setting notification on, false for turning it off
     * @return true if the operation was enqueued, false the characteristic could not be found or does not support notifications
     */
    private fun setNotify(serviceUUID: UUID, characteristicUUID: UUID, enable: Boolean, resultCallback: BluetoothPeripheralCallback): Boolean {
        return getCharacteristic(serviceUUID, characteristicUUID)?.let { setNotify(it, enable, resultCallback) } ?: false
    }

    /**
     * Set the notification state of a characteristic to 'on' or 'off'. The characteristic must support notifications or indications.
     *
     * [BluetoothPeripheralCallback.onNotificationStateUpdate] will be triggered as a result of this call.
     *
     * @param characteristic the characteristic to turn notification on/off for
     * @param enable         true for setting notification on, false for turning it off
     * @return true if the operation was enqueued, false if the characteristic doesn't support notification or indications or
     */
    private fun setNotify(characteristic: BluetoothGattCharacteristic, enable: Boolean, resultCallback: BluetoothPeripheralCallback): Boolean {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }
        require(characteristic.supportsNotifying()) { "characteristic <${characteristic.uuid}> does not have notify or indicate property" }

        val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)
        if (descriptor == null) {
            Logger.e(TAG, "could not get CCC descriptor for characteristic %s", characteristic.uuid)
            return false
        }

        // Check if characteristic has NOTIFY or INDICATE properties and set the correct byte value to be written
        val properties = characteristic.properties
        val value = when {
            properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY > 0 -> {
                ENABLE_NOTIFICATION_VALUE
            }
            properties and BluetoothGattCharacteristic.PROPERTY_INDICATE > 0 -> {
                ENABLE_INDICATION_VALUE
            }
            else -> {
                Logger.e(TAG, "characteristic %s does not have notify or indicate property", characteristic.uuid)
                return false
            }
        }
        val finalValue = if (enable) value else DISABLE_NOTIFICATION_VALUE

        return enqueue {
            if (notConnected()) {
                resultCallback.onDescriptorWrite(this@BluetoothPeripheral, ByteArray(0), descriptor, GattStatus.CONNECTION_CANCELLED)
                completedCommand()
            } else {
                currentResultCallback = resultCallback
                if (bluetoothGatt?.setCharacteristicNotification(characteristic, enable) == false) {
                    Logger.e(TAG, "setCharacteristicNotification failed for characteristic: %s", characteristic.uuid)
                    completedCommand()
                } else {
                    currentWriteBytes = finalValue
                    if (internalWriteDescriptor(descriptor, finalValue)) {
                        nrTries++
                    } else {
                        Logger.e(TAG, "writeDescriptor failed for descriptor: %s", descriptor.uuid)
                        resultCallback.onDescriptorWrite(this@BluetoothPeripheral, ByteArray(0), descriptor, GattStatus.WRITE_NOT_PERMITTED)
                        completedCommand()
                    }
                }
            }
        }
    }

    suspend fun readRemoteRssi(): Int =
        suspendCoroutine {
            val result = readRemoteRssi(object : BluetoothPeripheralCallback() {
                override fun onReadRemoteRssi(peripheral: BluetoothPeripheral, rssi: Int, status: GattStatus) {
                    if (status == GattStatus.SUCCESS) {
                        it.resume(rssi)
                    } else {
                        it.resumeWithException(GattException(status))
                    }
                }
            })

            if (!result) {
                it.resume(-255)
            }
        }

    /**
     * Read the RSSI for a connected remote peripheral.
     *
     *
     * [BluetoothPeripheralCallback.onReadRemoteRssi] will be triggered as a result of this call.
     *
     * @return true if the operation was enqueued, false otherwise
     */
    private fun readRemoteRssi(resultCallback: BluetoothPeripheralCallback): Boolean {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }

        return enqueue {
            if (isConnected) {
                currentResultCallback = resultCallback
                if (bluetoothGatt?.readRemoteRssi() == false) {
                    Logger.e(TAG, "readRemoteRssi failed")
                    resultCallback.onReadRemoteRssi(this@BluetoothPeripheral, 0, GattStatus.ERROR)
                    completedCommand()
                }
            } else {
                resultCallback.onReadRemoteRssi(this@BluetoothPeripheral, 0, GattStatus.CONNECTION_CANCELLED)
                completedCommand()
            }
        }
    }

    suspend fun requestMtu(mtu: Int): Int =
        suspendCoroutine {
            try {
                val result = requestMtu(mtu, object : BluetoothPeripheralCallback() {
                    override fun onMtuChanged(peripheral: BluetoothPeripheral, mtu: Int, status: GattStatus) {
                        if (status == GattStatus.SUCCESS) {
                            it.resume(mtu)
                        } else {
                            it.resumeWithException(GattException(status))
                        }
                    }
                })

                if (!result) {
                    it.resume(currentMtu)
                }
            } catch (exception: IllegalArgumentException) {
                it.resumeWithException(exception)
            }
        }

    /**
     * Request an MTU size used for a given connection.
     *
     *
     * When performing a write request operation (write without response),
     * the data sent is truncated to the MTU size. This function may be used
     * to request a larger MTU size to be able to send more data at once.
     *
     *
     * Note that requesting an MTU should only take place once per connection, according to the Bluetooth standard.
     *
     * [BluetoothPeripheralCallback.onMtuChanged] will be triggered as a result of this call.
     *
     * @param mtu the desired MTU size
     * @return true if the operation was enqueued, false otherwise
     */
    private fun requestMtu(mtu: Int, resultCallback: BluetoothPeripheralCallback): Boolean {
        require(mtu in DEFAULT_MTU..MAX_MTU) { "mtu must be between 23 and 517" }
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }

        return enqueue {
            if (isConnected) {
                currentResultCallback = resultCallback
                if (bluetoothGatt?.requestMtu(mtu) == true) {
                    currentCommand = REQUEST_MTU_COMMAND
                    Logger.d(TAG, "requesting MTU of %d", mtu)
                } else {
                    Logger.e(TAG, "requestMtu failed")
                    resultCallback.onMtuChanged(this@BluetoothPeripheral, 0, GattStatus.ERROR)
                    completedCommand()
                }
            } else {
                resultCallback.onMtuChanged(this@BluetoothPeripheral, 0, GattStatus.CONNECTION_CANCELLED)
                completedCommand()
            }
        }
    }


    suspend fun requestConnectionPriority(priority: ConnectionPriority): Boolean =
        suspendCoroutine {
            try {
                val result = requestConnectionPriority(priority, object : BluetoothPeripheralCallback() {
                    override fun onRequestedConnectionPriority(peripheral: BluetoothPeripheral) {
                        it.resume(true)
                    }
                })

                if (!result) {
                    it.resume(false)
                }
            } catch (e: IllegalArgumentException) {
                it.resumeWithException(e)
            }
        }

    /**
     * Request a different connection priority.
     *
     * @param priority the requested connection priority
     * @return true if request was enqueued, false if not
     */
    private fun requestConnectionPriority(priority: ConnectionPriority, resultCallback: BluetoothPeripheralCallback): Boolean {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }

        return enqueue {
            if (isConnected) {
                currentResultCallback = resultCallback
                if (bluetoothGatt?.requestConnectionPriority(priority.value) == true) {
                    Logger.d(TAG, "requesting connection priority %s", priority)
                } else {
                    Logger.e(TAG, "could not request connection priority")
                }
            }

            callbackScope.launch {
                delay(AVG_REQUEST_CONNECTION_PRIORITY_DURATION)
                currentResultCallback.onRequestedConnectionPriority(this@BluetoothPeripheral)
                completedCommand()
            }
        }
    }

    /**
     * Set the preferred connection PHY for this app. Please note that this is just a
     * recommendation, whether the PHY change will happen depends on other applications preferences,
     * local and remote controller capabilities. Controller can override these settings.
     *
     * @param txPhy      the desired TX PHY
     * @param rxPhy      the desired RX PHY
     * @param phyOptions the desired optional sub-type for PHY_LE_CODED
     * @return the resulting Phy after negotiating with Peripheral
     */
    suspend fun setPreferredPhy(txPhy: PhyType, rxPhy: PhyType, phyOptions: PhyOptions): Phy =
        suspendCoroutine {
            val result = setPreferredPhy(txPhy, rxPhy, phyOptions, object: BluetoothPeripheralCallback() {
                override fun onPhyUpdate(peripheral: BluetoothPeripheral, txPhy: PhyType, rxPhy: PhyType, status: GattStatus) {
                    if (status == GattStatus.SUCCESS) {
                        it.resume(Phy(txPhy, rxPhy))
                    } else {
                        it.resumeWithException(GattException(status))
                    }
                }
            })
            if (!result) {
                it.resumeWithException(IllegalStateException("could not execute operation"))
            }
        }


    private fun setPreferredPhy(txPhy: PhyType, rxPhy: PhyType, phyOptions: PhyOptions, resultCallback: BluetoothPeripheralCallback): Boolean {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }

        return enqueue {
            if (isConnected) {
                Logger.d(TAG, "setting preferred Phy: tx = %s, rx = %s, options = %s", txPhy, rxPhy, phyOptions)
                currentResultCallback = resultCallback
                currentCommand = SET_PHY_TYPE_COMMAND
                bluetoothGatt?.setPreferredPhy(txPhy.mask, rxPhy.mask, phyOptions.value)
            } else {
                resultCallback.onPhyUpdate(this@BluetoothPeripheral, txPhy, rxPhy, GattStatus.CONNECTION_CANCELLED)
                completedCommand()
            }
        }
    }

    /**
     * Read the current transmitter PHY and receiver PHY of the connection.
     *
     * @return Phy object with the current values
     */
    suspend fun readPhy(): Phy =
        suspendCoroutine {
            readPhy(object : BluetoothPeripheralCallback() {
                override fun onPhyUpdate(peripheral: BluetoothPeripheral, txPhy: PhyType, rxPhy: PhyType, status: GattStatus) {
                    if(status == GattStatus.SUCCESS) {
                        it.resume(Phy(txPhy, rxPhy))
                    } else {
                        it.resumeWithException(GattException(status))
                    }
                }
            })
        }

    /**
     * Read the current transmitter PHY and receiver PHY of the connection. The values are returned
     * in [BluetoothPeripheralCallback.onPhyUpdate]
     */
    private fun readPhy(resultCallback: BluetoothPeripheralCallback): Boolean {
        require(isConnected) { PERIPHERAL_NOT_CONNECTED }

        return enqueue {
            if (isConnected) {
                currentResultCallback = resultCallback
                bluetoothGatt?.readPhy()
                Logger.d(TAG, "reading Phy")
            } else {
                resultCallback.onPhyUpdate(this@BluetoothPeripheral, PhyType.UNKNOWN_PHY_TYPE, PhyType.UNKNOWN_PHY_TYPE, GattStatus.CONNECTION_CANCELLED)
                completedCommand()
            }
        }
    }

    /**
     * Asynchronous method to clear the services cache. Make sure to add a delay when using this!
     *
     * @return true if the method was executed, false if not executed
     */
    suspend fun clearServicesCache(): Boolean {
        if (bluetoothGatt == null) return false
        var result = false
        try {
            val refreshMethod = bluetoothGatt?.javaClass?.getMethod("refresh")
            if (refreshMethod != null) {
                result = refreshMethod.invoke(bluetoothGatt) as Boolean
            }
        } catch (e: Exception) {
            Logger.e(TAG, "could not invoke refresh method")
        }
        delay(100)
        return result
    }

    /**
     * Enqueue a command
     *
     * Return true if the command was enqueued, otherwise false
     */
    private fun enqueue(command: Runnable) : Boolean {
        val result = commandQueue.add(command)
        if (result) {
            nextCommand()
        } else {
            Logger.e(TAG, "could not enqueue command")
        }
        return result
    }

    /**
     * The current command has been completed, move to the next command in the queue (if any)
     */
    private fun completedCommand() {
        isRetrying = false
        commandQueue.poll()
        commandQueueBusy = false
        nextCommand()
    }

    /**
     * Execute the next command in the subscribe queue.
     * A queue is used because the calls have to be executed sequentially.
     * If the read or write fails, the next command in the queue is executed.
     */
    private fun nextCommand() {
        synchronized(this) {

            // If there is still a command being executed, then bail out
            if (commandQueueBusy) return

            // Check if there is something to do at all
            val bluetoothCommand = commandQueue.peek() ?: return

            // Check if we still have a valid gatt object
            if (bluetoothGatt == null) {
                Logger.e(TAG, "gatt is 'null' for peripheral '%s', clearing command queue", address)
                commandQueue.clear()
                commandQueueBusy = false
                return
            }

            // Execute the next command in the queue
            commandQueueBusy = true
            if (!isRetrying) {
                nrTries = 0
            }
            scope.launch {
                try {
                    bluetoothCommand.run()
                } catch (ex: Exception) {
                    Logger.e(TAG, "command exception for device '%s'", name)
                    Logger.e(TAG, ex.toString())
                    completedCommand()
                }
            }
        }
    }

    private fun pairingVariantToString(variant: Int): String {
        return when (variant) {
            PAIRING_VARIANT_PIN -> "PAIRING_VARIANT_PIN"
            PAIRING_VARIANT_PASSKEY -> "PAIRING_VARIANT_PASSKEY"
            PAIRING_VARIANT_PASSKEY_CONFIRMATION -> "PAIRING_VARIANT_PASSKEY_CONFIRMATION"
            PAIRING_VARIANT_CONSENT -> "PAIRING_VARIANT_CONSENT"
            PAIRING_VARIANT_DISPLAY_PASSKEY -> "PAIRING_VARIANT_DISPLAY_PASSKEY"
            PAIRING_VARIANT_DISPLAY_PIN -> "PAIRING_VARIANT_DISPLAY_PIN"
            PAIRING_VARIANT_OOB_CONSENT -> "PAIRING_VARIANT_OOB_CONSENT"
            else -> "UNKNOWN"
        }
    }

    interface InternalCallback {
        /**
         * Trying to connect to [BluetoothPeripheral]
         *
         * @param peripheral [BluetoothPeripheral] the peripheral.
         */
        fun connecting(peripheral: BluetoothPeripheral)

        /**
         * [BluetoothPeripheral] has successfully connected.
         *
         * @param peripheral [BluetoothPeripheral] that connected.
         */
        fun connected(peripheral: BluetoothPeripheral)

        /**
         * Connecting with [BluetoothPeripheral] has failed.
         *
         * @param peripheral [BluetoothPeripheral] of which connect failed.
         */
        fun connectFailed(peripheral: BluetoothPeripheral, status: HciStatus)

        /**
         * Trying to disconnect to [BluetoothPeripheral]
         *
         * @param peripheral [BluetoothPeripheral] the peripheral.
         */
        fun disconnecting(peripheral: BluetoothPeripheral)

        /**
         * [BluetoothPeripheral] has disconnected.
         *
         * @param peripheral [BluetoothPeripheral] that disconnected.
         */
        fun disconnected(peripheral: BluetoothPeripheral, status: HciStatus)
        fun getPincode(peripheral: BluetoothPeripheral): String?
    }

    private var timeoutJob : Job? = null
    private fun startConnectionTimer(peripheral: BluetoothPeripheral) {
        cancelConnectionTimer()

        timeoutJob = scope.launch {
            delay(CONNECTION_TIMEOUT_IN_MS)
            Logger.e(TAG, "connection timeout, disconnecting '%s'", peripheral.name)
            disconnect()
            scope.launch {
                delay(50)
                bluetoothGatt?.let {
                    bluetoothGattCallback.onConnectionStateChange(bluetoothGatt, HciStatus.CONNECTION_FAILED_ESTABLISHMENT.value, BluetoothProfile.STATE_DISCONNECTED)
                }
            }
        }
    }

    private fun cancelConnectionTimer() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private val timeoutThreshold: Int
        get() {
            val manufacturer = Build.MANUFACTURER
            return if (manufacturer.equals("samsung", ignoreCase = true)) {
                TIMEOUT_THRESHOLD_SAMSUNG
            } else {
                TIMEOUT_THRESHOLD_DEFAULT
            }
        }

    /**
     * Make a safe copy of a nullable byte array
     *
     * @param source byte array to copy
     * @return non-null copy of the source byte array or an empty array if source was null
     */
    private fun copyOf(source: ByteArray?): ByteArray {
        return if (source == null) ByteArray(0) else Arrays.copyOf(source, source.size)
    }

    /**
     * Make a byte array nonnull by either returning the original byte array if non-null or an empty bytearray
     *
     * @param source byte array to make nonnull
     * @return the source byte array or an empty array if source was null
     */
    fun nonnullOf(source: ByteArray?): ByteArray {
        return source ?: ByteArray(0)
    }

    companion object {
        private val TAG = BluetoothPeripheral::class.simpleName.toString()
        
        private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Max MTU that Android can handle
         */
        const val MAX_MTU = 517

        // Minimal and default MTU
        private const val DEFAULT_MTU = 23

        // Maximum number of retries of commands
        private const val MAX_TRIES = 2

        // Delay to use when doing a connect
        private const val DIRECT_CONNECTION_DELAY_IN_MS = 100L

        // Timeout to use if no callback on onConnectionStateChange happens
        private const val CONNECTION_TIMEOUT_IN_MS = 35000L

        // Samsung phones time out after 5 seconds while most other phone time out after 30 seconds
        private const val TIMEOUT_THRESHOLD_SAMSUNG = 4500

        // Most other phone time out after 30 seconds
        private const val TIMEOUT_THRESHOLD_DEFAULT = 25000

        // When a bond is lost, the bluetooth stack needs some time to update its internal state
        private const val DELAY_AFTER_BOND_LOST = 1000L

        // The average time it takes to complete requestConnectionPriority
        private const val AVG_REQUEST_CONNECTION_PRIORITY_DURATION = 500L

        // Error message constants
        private const val PERIPHERAL_NOT_CONNECTED = "peripheral not connected"
        private const val VALUE_BYTE_ARRAY_IS_EMPTY = "value byte array is empty"
        private const val VALUE_BYTE_ARRAY_IS_TOO_LONG = "value byte array is too long"

        // String constants for commands where the callbacks can also happen because the remote peripheral initiated the command
        private const val IDLE = 0
        private const val REQUEST_MTU_COMMAND = 1
        private const val SET_PHY_TYPE_COMMAND = 2

        // Pairing variant codes
        private const val PAIRING_VARIANT_PIN = 0
        private const val PAIRING_VARIANT_PASSKEY = 1
        private const val PAIRING_VARIANT_PASSKEY_CONFIRMATION = 2
        private const val PAIRING_VARIANT_CONSENT = 3
        private const val PAIRING_VARIANT_DISPLAY_PASSKEY = 4
        private const val PAIRING_VARIANT_DISPLAY_PIN = 5
        private const val PAIRING_VARIANT_OOB_CONSENT = 6
    }
}