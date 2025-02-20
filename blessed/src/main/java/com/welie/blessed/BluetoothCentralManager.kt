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

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.BLUETOOTH_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.welie.blessed.BluetoothPeripheral.InternalCallback
import com.welie.blessed.BluetoothPeripheralCallback.NULL
import kotlinx.coroutines.*
import java.lang.Runnable
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Central Manager class to scan and connect with bluetooth peripherals.
 */
@SuppressLint("MissingPermission")
@Suppress("unused")
class BluetoothCentralManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bluetoothAdapter: BluetoothAdapter
    private var bluetoothScanner: BluetoothLeScanner? = null
    private var autoConnectScanner: BluetoothLeScanner? = null
    private var currentCentralManagerCallback: BluetoothCentralManagerCallback = BluetoothCentralManagerCallback.NULL()
    private val connectedPeripherals: MutableMap<String, BluetoothPeripheral> = ConcurrentHashMap()
    private val unconnectedPeripherals: MutableMap<String, BluetoothPeripheral> = ConcurrentHashMap()
    private val scannedPeripherals: MutableMap<String, BluetoothPeripheral> = ConcurrentHashMap()
    private val reconnectPeripheralAddresses: MutableList<String> = ArrayList()
    private var scanPeripheralNames = emptyArray<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var autoConnectRunnable: Runnable? = null
    private val connectLock = Any()
    private var currentCallback: ScanCallback? = null
    private var currentFilters: List<ScanFilter>? = null
    private var scanSettings: ScanSettings
    private val autoConnectScanSettings: ScanSettings
    private val connectionRetries: MutableMap<String, Int> = ConcurrentHashMap()
    private var disconnectRunnable: Runnable? = null
    private val pinCodes: MutableMap<String, String> = ConcurrentHashMap()
    private var currentResultCallback : ((BluetoothPeripheral, ScanResult) -> Unit)? = null
    private var currentScanErrorCallback : ((ScanFailure) -> Unit)? = null
    private var adapterStateCallback: (state: Int) -> Unit = {}

    private val scanByNameCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            synchronized(this) {
                val deviceName = result.device.name ?: return
                for (name in scanPeripheralNames) {
                    if (deviceName.contains(name)) {
                        sendScanResult(result)
                        return
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            sendScanFailed(ScanFailure.fromValue(errorCode))
        }
    }
    private val defaultScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            synchronized(this) { sendScanResult(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            sendScanFailed(ScanFailure.fromValue(errorCode))
        }
    }

    private fun sendScanResult(result: ScanResult) {
        scope.launch {
            if (isScanning) {
                val peripheral = getPeripheral(result.device.address)
                peripheral.setDevice(result.device)
                currentResultCallback?.invoke(peripheral, result)
            }
        }
    }

    private fun sendScanFailed(scanFailure: ScanFailure) {
        currentCallback = null
        currentFilters = null
        cancelTimeoutTimer()
        scope.launch {
            Logger.e(TAG, "scan failed with error code %d (%s)", scanFailure.value, scanFailure)
            currentScanErrorCallback?.invoke(scanFailure)
        }
    }

    private val autoConnectScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            synchronized(this) {
                if (!isAutoScanning) return
                Logger.d(TAG, "peripheral with address '%s' found", result.device.address)
                stopAutoconnectScan()
                val deviceAddress = result.device.address
                val peripheral = unconnectedPeripherals[deviceAddress]
                reconnectPeripheralAddresses.remove(deviceAddress)
                unconnectedPeripherals.remove(deviceAddress)
                scannedPeripherals.remove(deviceAddress)
                if (peripheral != null) {
                    autoConnectPeripheral(peripheral)
                }
                if (reconnectPeripheralAddresses.size > 0) {
                    scanForAutoConnectPeripherals()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val scanFailure = ScanFailure.fromValue(errorCode)
            Logger.e(TAG, "autoConnect scan failed with error code %d (%s)", errorCode, scanFailure)
            autoConnectScanner = null
         //   scope.launch { bluetoothCentralManagerCallback.onScanFailed(scanFailure) }
        }
    }

    @JvmField
    val internalCallback: InternalCallback = object : InternalCallback {
        override fun connecting(peripheral: BluetoothPeripheral) {
            scope.launch { connectionStateCallback.invoke(peripheral, ConnectionState.CONNECTING)}
        }

        override fun connected(peripheral: BluetoothPeripheral) {
            connectionRetries.remove(peripheral.address)
            unconnectedPeripherals.remove(peripheral.address)
            scannedPeripherals.remove(peripheral.address)
            connectedPeripherals[peripheral.address] = peripheral
            scope.launch {
                currentCentralManagerCallback.onConnectedPeripheral(peripheral)
                currentCentralManagerCallback = BluetoothCentralManagerCallback.NULL()
            }
            scope.launch { connectionStateCallback.invoke(peripheral, ConnectionState.CONNECTED)}
        }

        override fun connectFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            unconnectedPeripherals.remove(peripheral.address)
            scannedPeripherals.remove(peripheral.address)

            // Get the number of retries for this peripheral
            var nrRetries = 0
            val retries = connectionRetries[peripheral.address]
            if (retries != null) nrRetries = retries

            // Retry connection or conclude the connection has failed
            if (nrRetries < MAX_CONNECTION_RETRIES && status != HciStatus.CONNECTION_FAILED_ESTABLISHMENT) {
                Logger.i(TAG, "retrying connection to '%s' (%s)", peripheral.name, peripheral.address)
                nrRetries++
                connectionRetries[peripheral.address] = nrRetries
                unconnectedPeripherals[peripheral.address] = peripheral
                peripheral.connect()
            } else {
                Logger.i(TAG, "connection to '%s' (%s) failed", peripheral.name, peripheral.address)
                connectionRetries.remove(peripheral.address)
                scope.launch {
                    currentCentralManagerCallback.onConnectionFailed(peripheral, status)
                    currentCentralManagerCallback = BluetoothCentralManagerCallback.NULL()
                }
                scope.launch { connectionStateCallback.invoke(peripheral, ConnectionState.DISCONNECTED)}
            }
        }

        override fun disconnecting(peripheral: BluetoothPeripheral) {
            scope.launch { connectionStateCallback.invoke(peripheral, ConnectionState.DISCONNECTING)}
        }

        override fun disconnected(peripheral: BluetoothPeripheral, status: HciStatus) {
            connectedPeripherals.remove(peripheral.address)
            unconnectedPeripherals.remove(peripheral.address)
            scannedPeripherals.remove(peripheral.address)
            connectionRetries.remove(peripheral.address)
            scope.launch {
                currentCentralManagerCallback.onDisconnectedPeripheral(peripheral, status)
                currentCentralManagerCallback = BluetoothCentralManagerCallback.NULL()
            }
            scope.launch { connectionStateCallback.invoke(peripheral, ConnectionState.DISCONNECTED) }
        }

        override fun getPincode(peripheral: BluetoothPeripheral): String? {
            return pinCodes[peripheral.address]
        }
    }

    /**
     * Closes BluetoothCentralManager and cleans up internals. BluetoothCentralManager will not work anymore after this is called.
     */
    fun close() {
        unconnectedPeripherals.clear()
        connectedPeripherals.clear()
        reconnectPeripheralAddresses.clear()
        scannedPeripherals.clear()
        context.unregisterReceiver(adapterStateReceiver)
    }

    private fun getScanSettings(scanMode: ScanMode): ScanSettings {
        return ScanSettings.Builder()
            .setLegacy(false)
            .setScanMode(scanMode.value)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .setReportDelay(0L)
            .build()
    }

    /**
     * Set the default scanMode.
     *
     * @param scanMode the scanMode to set
     */
    fun setScanMode(scanMode: ScanMode) {
        scanSettings = getScanSettings(scanMode)
    }

    private fun startScan(filters: List<ScanFilter>, scanSettings: ScanSettings, scanCallback: ScanCallback) {
        if (bleNotReady()) return
        if (bluetoothScanner == null) {
            bluetoothScanner = bluetoothAdapter.bluetoothLeScanner
        }
        if (bluetoothScanner != null) {
            setScanTimer()
            currentCallback = scanCallback
            currentFilters = filters
            bluetoothScanner?.startScan(filters, scanSettings, scanCallback)
            Logger.i(TAG, "scan started")
        } else {
            Logger.e(TAG, "starting scan failed")
        }
    }

    /**
     * Scan for peripherals that advertise at least one of the specified service UUIDs.
     *
     * @param serviceUUIDs an array of service UUIDs
     * @throws IllegalArgumentException if the array of service UUIDs is empty
     */
    fun scanForPeripheralsWithServices(serviceUUIDs: Array<UUID>,  resultCallback: (BluetoothPeripheral, ScanResult) -> Unit, scanError: (ScanFailure) -> Unit   ) {
        require(serviceUUIDs.isNotEmpty()) { "at least one service UUID  must be supplied" }

        if (isScanning) stopScan()

        val filters: MutableList<ScanFilter> = ArrayList()
        for (serviceUUID in serviceUUIDs) {
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(serviceUUID))
                .build()
            filters.add(filter)
        }

        currentResultCallback = resultCallback
        currentScanErrorCallback = scanError
        startScan(filters, scanSettings, defaultScanCallback)
    }

    /**
     * Scan for peripherals with advertisement names containing any of the specified peripheral names.
     *
     *
     * Substring matching is used so only a partial peripheral names has to be supplied.
     *
     * @param peripheralNames array of partial peripheral names
     * @throws IllegalArgumentException if the array of peripheral names is empty
     */
    fun scanForPeripheralsWithNames(peripheralNames: Array<String>, resultCallback: (BluetoothPeripheral, ScanResult) -> Unit,  scanError: (ScanFailure) -> Unit   ) {
        require(peripheralNames.isNotEmpty()) { "at least one peripheral name must be supplied" }

        if (isScanning) stopScan()

        currentResultCallback = resultCallback
        currentScanErrorCallback = scanError

        // Start the scanner with no filter because we'll do the filtering ourselves
        scanPeripheralNames = peripheralNames
        startScan(emptyList(), scanSettings, scanByNameCallback)
    }

    /**
     * Scan for peripherals that have any of the specified peripheral mac addresses.
     *
     * @param peripheralAddresses array of peripheral mac addresses to scan for
     * @throws IllegalArgumentException if the array of addresses is empty
     */
    fun scanForPeripheralsWithAddresses(peripheralAddresses: Array<String>, resultCallback: (BluetoothPeripheral, ScanResult) -> Unit, scanError: (ScanFailure) -> Unit   ) {
        require(peripheralAddresses.isNotEmpty()) { "at least one peripheral address must be supplied" }

        if (isScanning) stopScan()

        currentResultCallback = resultCallback
        currentScanErrorCallback = scanError

        val filters: MutableList<ScanFilter> = ArrayList()
        for (address in peripheralAddresses) {
            if (BluetoothAdapter.checkBluetoothAddress(address)) {
                val filter = ScanFilter.Builder()
                    .setDeviceAddress(address)
                    .build()
                filters.add(filter)
            } else {
                Logger.e(TAG, "%s is not a valid address. Make sure all alphabetic characters are uppercase.", address)
            }
        }
        startScan(filters, scanSettings, defaultScanCallback)
    }

    /**
     * Scan for any peripheral that matches the supplied filters
     *
     * @param filters A list of ScanFilters
     * @throws IllegalArgumentException if the list of filters is empty
     */
    fun scanForPeripheralsUsingFilters(filters: List<ScanFilter>,resultCallback: (BluetoothPeripheral, ScanResult) -> Unit, scanError: (ScanFailure) -> Unit  ) {
        require(filters.isNotEmpty()) { "at least one scan filter must be supplied" }

        if (isScanning) stopScan()

        currentResultCallback = resultCallback
        currentScanErrorCallback = scanError
        startScan(filters, scanSettings, defaultScanCallback)
    }

    /**
     * Scan for any peripheral that is advertising.
     */
    fun scanForPeripherals(resultCallback: (BluetoothPeripheral, ScanResult) -> Unit, scanError: (ScanFailure) -> Unit ) {
        if (isScanning) stopScan()

        currentResultCallback = resultCallback
        currentScanErrorCallback = scanError
        startScan(emptyList(), scanSettings, defaultScanCallback)
    }

    /**
     * Scan for peripherals that need to be autoconnected but are not cached
     */
    private fun scanForAutoConnectPeripherals() {
        if (bleNotReady()) return
        if (autoConnectScanner != null) {
            stopAutoconnectScan()
        }

        autoConnectScanner = bluetoothAdapter.bluetoothLeScanner
        if (autoConnectScanner != null) {
            val filters: MutableList<ScanFilter> = ArrayList()
            for (address in reconnectPeripheralAddresses) {
                val filter = ScanFilter.Builder()
                    .setDeviceAddress(address)
                    .build()
                filters.add(filter)
            }
            autoConnectScanner!!.startScan(filters, autoConnectScanSettings, autoConnectScanCallback)
            Logger.d(TAG, "started scanning to autoconnect peripherals (" + reconnectPeripheralAddresses.size + ")")
            setAutoConnectTimer()
        } else {
            Logger.e(TAG, "starting autoconnect scan failed")
        }
    }

    private fun stopAutoconnectScan() {
        cancelAutoConnectTimer()
        try {
            autoConnectScanner?.stopScan(autoConnectScanCallback)
        } catch (ignore: Exception) {
        }
        autoConnectScanner = null
        Logger.i(TAG, "autoscan stopped")
    }

    private val isAutoScanning: Boolean
        get() = autoConnectScanner != null

    /**
     * Stop scanning for peripherals.
     */
    fun stopScan() {
        cancelTimeoutTimer()
        if (isScanning) {
            // Note that we can't call stopScan if the adapter is off
            // On some phones like the Nokia 8, the adapter will be already off at this point
            // So add a try/catch to handle any exceptions
            try {
                if (bluetoothScanner != null) {
                    bluetoothScanner?.stopScan(currentCallback)
                    currentCallback = null
                    currentFilters = null
                    Logger.i(TAG, "scan stopped")
                }
            } catch (ignore: Exception) {
                Logger.e(TAG, "caught exception in stopScan")
            }
        } else {
            Logger.d(TAG, "no scan to stop because no scan is running")
        }
        currentCallback = null
        currentFilters = null
        scannedPeripherals.clear()
    }

    /**
     * Check if a scanning is active
     *
     * @return true if a scan is active, otherwise false
     */
    val isScanning: Boolean
        get() = bluetoothScanner != null && currentCallback != null


    private var connectionStateCallback : (peripheral : BluetoothPeripheral, state : ConnectionState) -> Unit = {_,_ -> }

    fun observeConnectionState(connectionCallback: (peripheral : BluetoothPeripheral, state : ConnectionState) -> Unit) {
        connectionStateCallback = connectionCallback
    }


    /**
     * Connect to a known peripheral immediately. The peripheral must have been found by scanning for this call to succeed. This method will time out in max 30 seconds on most phones and in 5 seconds on Samsung phones.
     * If the peripheral is already connected, no connection attempt will be made. This method is asynchronous and there can be only one outstanding connect.
     *
     * @param peripheral BLE peripheral to connect with
     */
    suspend fun connectPeripheral(peripheral: BluetoothPeripheral): Unit =
        suspendCoroutine {
            try {
                connectPeripheral(peripheral, object : BluetoothCentralManagerCallback() {
                    override fun onConnectedPeripheral(peripheral: BluetoothPeripheral) {
                        it.resume(Unit)
                    }

                    override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
                        it.resumeWithException(ConnectionFailedException(status))
                    }
                })
            } catch (failedException: ConnectionFailedException) {
                it.resumeWithException(failedException)
            }
        }

    private fun connectPeripheral(peripheral: BluetoothPeripheral, resultCentralManagerCallback: BluetoothCentralManagerCallback) {
        synchronized(connectLock) {
            if (bleNotReady()) {
                Logger.e(TAG, "cannot connect peripheral '%s' because Bluetooth is off", peripheral.address)
                return
            }

            checkPeripheralStatus(peripheral)

            // Check if the peripheral is cached or not. If not, issue a warning because connection may fail
            // This is because Android will guess the address type and when incorrect it will fail
            if (peripheral.isUncached) {
                Logger.w(TAG, "peripheral with address '%s' is not in the Bluetooth cache, hence connection may fail", peripheral.address)
            }

            scannedPeripherals.remove(peripheral.address)
            unconnectedPeripherals[peripheral.address] = peripheral
            currentCentralManagerCallback = resultCentralManagerCallback
            peripheral.connect()
        }
    }

    /**
     * Automatically connect to a peripheral when it is advertising. It is not necessary to scan for the peripheral first. This call is asynchronous and will not time out.
     *
     * @param peripheral the peripheral
     */
    fun autoConnectPeripheral(peripheral: BluetoothPeripheral) {
        synchronized(connectLock) {
            if (bleNotReady()) {
                Logger.e(TAG, "cannot autoConnectPeripheral '%s' because Bluetooth is off", peripheral.address)
                return
            }

            checkPeripheralStatus(peripheral)

            // Check if the peripheral is uncached and start autoConnectPeripheralByScan
            if (peripheral.isUncached) {
                Logger.d(TAG, "peripheral with address '%s' not in Bluetooth cache, autoconnecting by scanning", peripheral.address)
                scannedPeripherals.remove(peripheral.address)
                unconnectedPeripherals[peripheral.address] = peripheral
                autoConnectPeripheralByScan(peripheral.address)
                return
            }

            scannedPeripherals.remove(peripheral.address)
            unconnectedPeripherals[peripheral.address] = peripheral
            peripheral.autoConnect()
        }
    }

    private fun checkPeripheralStatus(peripheral: BluetoothPeripheral) {
        if (connectedPeripherals.containsKey(peripheral.address)) {
            Logger.e(TAG, "already connected to %s'", peripheral.address)
            throw ConnectionFailedException(HciStatus.CONNECTION_ALREADY_EXISTS)
        }

        if (unconnectedPeripherals.containsKey(peripheral.address)) {
            Logger.e(TAG, "already issued autoconnect for '%s' ", peripheral.address)
            throw ConnectionFailedException(HciStatus.COMMAND_DISALLOWED)
        }

        if (peripheral.type == PeripheralType.CLASSIC) {
            Logger.e(TAG, "peripheral does not support Bluetooth LE")
            throw ConnectionFailedException(HciStatus.UNSUPPORTED_PARAMETER_VALUE)
        }
    }

    private fun autoConnectPeripheralByScan(peripheralAddress: String) {
        if (reconnectPeripheralAddresses.contains(peripheralAddress)) {
            Logger.w(TAG, "peripheral already on list for reconnection")
            return
        }
        reconnectPeripheralAddresses.add(peripheralAddress)
        scanForAutoConnectPeripherals()
    }

    /**
     * Cancel an active or pending connection for a peripheral.
     *
     * @param peripheral the peripheral
     */
    suspend fun cancelConnection(peripheral: BluetoothPeripheral): Unit =
        suspendCoroutine {
            cancelConnection(peripheral, object : BluetoothCentralManagerCallback() {
                override fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: HciStatus) {
                    it.resume(Unit)
                }
            })
        }

    private fun cancelConnection(peripheral: BluetoothPeripheral, resultCentralManagerCallback: BluetoothCentralManagerCallback) {
        // First check if we are doing a reconnection scan for this peripheral
        val peripheralAddress = peripheral.address
        if (reconnectPeripheralAddresses.contains(peripheralAddress)) {
            reconnectPeripheralAddresses.remove(peripheralAddress)
            unconnectedPeripherals.remove(peripheralAddress)
            stopAutoconnectScan()
            Logger.d(TAG, "cancelling autoconnect for %s", peripheralAddress)
            scope.launch { resultCentralManagerCallback.onDisconnectedPeripheral(peripheral, HciStatus.SUCCESS) }

            // If there are any devices left, restart the reconnection scan
            if (reconnectPeripheralAddresses.size > 0) {
                scanForAutoConnectPeripherals()
            }
            return
        }

        // Only cancel connections if it is an known peripheral
        if (unconnectedPeripherals.containsKey(peripheralAddress) || connectedPeripherals.containsKey(peripheralAddress)) {
            currentCentralManagerCallback = resultCentralManagerCallback
            peripheral.cancelConnection()
        } else {
            Logger.e(TAG, "cannot cancel connection to unknown peripheral %s", peripheralAddress)
            throw IllegalArgumentException("tyring to disconnect peripheral outside of library")
        }
    }

    /**
     * Autoconnect to a batch of peripherals.
     *
     *
     * Use this function to autoConnect to a batch of peripherals, instead of calling autoConnect on each of them.
     * Calling autoConnect on many peripherals may cause Android scanning limits to kick in, which is avoided by using autoConnectPeripheralsBatch.
     *
     * @param batch the map of peripherals and their callbacks to autoconnect to
     */
    fun autoConnectPeripheralsBatch(batch: Set<BluetoothPeripheral>) {
        // Find the uncached peripherals and issue autoConnectPeripheral for the cached ones
        val uncachedPeripherals: MutableSet<BluetoothPeripheral> = HashSet()
        for (peripheral in batch) {
            if (peripheral.isUncached) {
                uncachedPeripherals.add(peripheral)
            } else {
                autoConnectPeripheral(peripheral)
            }
        }

        // Add uncached peripherals to list of peripherals to scan for
        if (uncachedPeripherals.isNotEmpty()) {
            for (peripheral in uncachedPeripherals) {
                val peripheralAddress = peripheral.address
                reconnectPeripheralAddresses.add(peripheralAddress)
                unconnectedPeripherals[peripheralAddress] = peripheral
            }
            scanForAutoConnectPeripherals()
        }
    }

    /**
     * Get a peripheral object matching the specified mac address.
     *
     * @param peripheralAddress mac address
     * @return a BluetoothPeripheral object matching the specified mac address or null if it was not found
     */
    fun getPeripheral(peripheralAddress: String): BluetoothPeripheral {
        if (!BluetoothAdapter.checkBluetoothAddress(peripheralAddress)) {
            val message = String.format("%s is not a valid bluetooth address. Make sure all alphabetic characters are uppercase.", peripheralAddress)
            throw IllegalArgumentException(message)
        }
        return if (connectedPeripherals.containsKey(peripheralAddress)) {
            requireNotNull(connectedPeripherals[peripheralAddress])
        } else if (unconnectedPeripherals.containsKey(peripheralAddress)) {
            requireNotNull(unconnectedPeripherals[peripheralAddress])
        } else if (scannedPeripherals.containsKey(peripheralAddress)) {
            requireNotNull(scannedPeripherals[peripheralAddress])
        } else {
            val peripheral = BluetoothPeripheral(context, bluetoothAdapter.getRemoteDevice(peripheralAddress), internalCallback)
            scannedPeripherals[peripheralAddress] = peripheral
            peripheral
        }
    }

    /**
     * Get the list of connected peripherals.
     *
     * @return list of connected peripherals
     */
    fun getConnectedPeripherals(): List<BluetoothPeripheral> {
        return ArrayList(connectedPeripherals.values)
    }

    private fun bleNotReady(): Boolean {
        if (isBleSupported) {
            if (isBluetoothEnabled) {
                return !permissionsGranted()
            }
        }
        return true
    }

    private val isBleSupported: Boolean
        get() {
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                return true
            }
            Logger.e(TAG, "BLE not supported")
            return false
        }

    /**
     * Check if Bluetooth is enabled
     *
     * @return true is Bluetooth is enabled, otherwise false
     */
    val isBluetoothEnabled: Boolean
        get() {
            if (bluetoothAdapter.isEnabled) {
                return true
            }
            Logger.e(TAG, "Bluetooth disabled")
            return false
        }

    private fun permissionsGranted(): Boolean {
        val targetSdkVersion = context.applicationInfo.targetSdkVersion
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && targetSdkVersion >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("app does not have BLUETOOTH_SCAN permission, cannot start scan")
            }
            return if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("app does not have BLUETOOTH_CONNECT permission, cannot connect")
            } else true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && targetSdkVersion >= Build.VERSION_CODES.Q) {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Logger.e(TAG, "no ACCESS_FINE_LOCATION permission, cannot scan")
                false
            } else true
        } else
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Logger.e(TAG, "no ACCESS_COARSE_LOCATION permission, cannot scan")
                false
            } else true
    }

    /**
     * Set scan timeout timer, timeout time is `SCAN_TIMEOUT`.
     * If timeout is executed the scan is stopped and automatically restarted. This is done to avoid Android 9 scan restrictions
     */
    private fun setScanTimer() {
        cancelTimeoutTimer()
        timeoutRunnable = Runnable {
            Logger.d(TAG, "scanning timeout, restarting scan")
            val callback = currentCallback
            val filters = currentFilters
            stopScan()

            // Restart the scan and timer
            scope.launch {
                delay(SCAN_RESTART_DELAY)
                if (callback != null) {
                    startScan(filters!!, scanSettings, callback)
                }
            }
        }
        mainHandler.postDelayed(timeoutRunnable!!, SCAN_TIMEOUT)
    }

    /**
     * Cancel the scan timeout timer
     */
    private fun cancelTimeoutTimer() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable!!)
            timeoutRunnable = null
        }
    }

    /**
     * Set scan timeout timer, timeout time is `SCAN_TIMEOUT`.
     * If timeout is executed the scan is stopped and automatically restarted. This is done to avoid Android 9 scan restrictions
     */
    private fun setAutoConnectTimer() {
        cancelAutoConnectTimer()
        autoConnectRunnable = Runnable {
            Logger.d(TAG, "autoconnect scan timeout, restarting scan")

            // Stop previous autoconnect scans if any
            stopAutoconnectScan()

            // Restart the auto connect scan and timer
            mainHandler.postDelayed({ scanForAutoConnectPeripherals() }, SCAN_RESTART_DELAY)
        }
        mainHandler.postDelayed(autoConnectRunnable!!, SCAN_TIMEOUT)
    }

    /**
     * Cancel the scan timeout timer
     */
    private fun cancelAutoConnectTimer() {
        if (autoConnectRunnable != null) {
            mainHandler.removeCallbacks(autoConnectRunnable!!)
            autoConnectRunnable = null
        }
    }

    /**
     * Set a fixed PIN code for a peripheral that asks for a PIN code during bonding.
     *
     *
     * This PIN code will be used to programmatically bond with the peripheral when it asks for a PIN code. The normal PIN popup will not appear anymore.
     *
     * Note that this only works for peripherals with a fixed PIN code.
     *
     * @param peripheralAddress the address of the peripheral
     * @param pin               the 6 digit PIN code as a string, e.g. "123456"
     * @return true if the pin code and peripheral address are valid and stored internally
     */
    fun setPinCodeForPeripheral(peripheralAddress: String, pin: String): Boolean {
        if (!BluetoothAdapter.checkBluetoothAddress(peripheralAddress)) {
            Logger.e(TAG, "%s is not a valid address. Make sure all alphabetic characters are uppercase.", peripheralAddress)
            return false
        }
        if (pin.length != 6) {
            Logger.e(TAG, "%s is not 6 digits long", pin)
            return false
        }
        pinCodes[peripheralAddress] = pin
        return true
    }

    /**
     * Remove bond for a peripheral.
     *
     * @param peripheralAddress the address of the peripheral
     * @return true if the peripheral was succesfully bonded or it wasn't bonded, false if it was bonded and removing it failed
     */
    fun removeBond(peripheralAddress: String): Boolean {
        // Get the set of bonded devices
        val bondedDevices = bluetoothAdapter.bondedDevices

        // See if the device is bonded
        var peripheralToUnBond: BluetoothDevice? = null
        if (bondedDevices.size > 0) {
            for (device in bondedDevices) {
                if (device.address == peripheralAddress) {
                    peripheralToUnBond = device
                }
            }
        } else {
            return true
        }

        // Try to remove the bond
        return if (peripheralToUnBond != null) {
            try {
                val method = peripheralToUnBond.javaClass.getMethod("removeBond")
                val result = method.invoke(peripheralToUnBond) as Boolean
                if (result) {
                    Logger.i(TAG, "Succesfully removed bond for '%s'", peripheralToUnBond.name)
                }
                result
            } catch (e: Exception) {
                Logger.i(TAG, "could not remove bond")
                e.printStackTrace()
                false
            }
        } else {
            true
        }
    }

    /**
     * Make the pairing popup appear in the foreground by doing a 1 sec discovery.
     *
     *
     * If the pairing popup is shown within 60 seconds, it will be shown in the foreground.
     */
    fun startPairingPopupHack() {
        // Check if we are on a Samsung device because those don't need the hack
        val manufacturer = Build.MANUFACTURER
        if (!manufacturer.equals("samsung", ignoreCase = true)) {
            bluetoothAdapter.startDiscovery()
            scope.launch {
                delay(1000)
                Logger.d(TAG, "popup hack completed")
                bluetoothAdapter.cancelDiscovery()
            }
        }
    }

    /**
     * Some phones, like Google/Pixel phones, don't automatically disconnect devices so this method does it manually
     */
    private fun cancelAllConnectionsWhenBluetoothOff() {
        Logger.d(TAG, "disconnect all peripherals because bluetooth is off")
        // Call cancelConnection for connected peripherals
        for (peripheral in connectedPeripherals.values) {
            peripheral.disconnectWhenBluetoothOff()
        }
        connectedPeripherals.clear()

        // Call cancelConnection for unconnected peripherals
        for (peripheral in unconnectedPeripherals.values) {
            peripheral.disconnectWhenBluetoothOff()
        }
        unconnectedPeripherals.clear()

        // Clean up autoconnect by scanning information
        reconnectPeripheralAddresses.clear()
    }

    fun observeAdapterState(callback: (state: Int) -> Unit) {
        this.adapterStateCallback = callback
    }

    private val adapterStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                handleAdapterState(state)
                adapterStateCallback.invoke(state)
            }
        }
    }

    private fun handleAdapterState(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_OFF -> {
                // Check if there are any connected peripherals or connections in progress
                if (connectedPeripherals.isNotEmpty() || unconnectedPeripherals.isNotEmpty()) {
                    cancelAllConnectionsWhenBluetoothOff()
                }
                Logger.d(TAG, "bluetooth turned off")
            }
            BluetoothAdapter.STATE_TURNING_OFF -> {
                // Try to disconnect all peripherals because Android doesn't always do that
                connectedPeripherals.forEach { entry -> entry.value.cancelConnection()}
                unconnectedPeripherals.forEach { entry -> entry.value.cancelConnection()}

                // Clean up autoconnect by scanning information
                reconnectPeripheralAddresses.clear()

                // Stop all scans so that we are back in a clean state
                if (isScanning) {
                   stopScan()
                }

                if (isAutoScanning) {
                    stopAutoconnectScan()
                }

                // Stop all scans so that we are back in a clean state
                // Note that we can't call stopScan if the adapter is off
                cancelTimeoutTimer()
                cancelAutoConnectTimer()
                currentCallback = null
                currentFilters = null
                autoConnectScanner = null
                bluetoothScanner = null
                Logger.d(TAG, "bluetooth turning off")
            }
            BluetoothAdapter.STATE_ON -> {
                // On some phones like Nokia 8, this scanner may still have an older active scan from us
                // This happens when bluetooth is toggled. So make sure it is gone.
                bluetoothScanner = bluetoothAdapter.bluetoothLeScanner
                try {
                    bluetoothScanner?.stopScan(defaultScanCallback)
                } catch (ignore: Exception) {}

                Logger.d(TAG, "bluetooth turned on")
            }
            BluetoothAdapter.STATE_TURNING_ON -> {
                Logger.d(TAG, "bluetooth turning on")
            }
        }
    }

    fun disableLogging() {
        Logger.enabled = false
    }

    fun enableLogging() {
        Logger.enabled = false
    }

    companion object {
        private val TAG = BluetoothCentralManager::class.simpleName.toString()
        private const val SCAN_TIMEOUT = 180000L
        private const val SCAN_RESTART_DELAY = 1000L
        private const val MAX_CONNECTION_RETRIES = 1
        private const val NO_PERIPHERAL_ADDRESS_PROVIDED = "no peripheral address provided"
        private const val NO_VALID_PERIPHERAL_PROVIDED = "no valid peripheral provided"
        private const val NO_VALID_PERIPHERAL_CALLBACK_SPECIFIED = "no valid peripheral callback specified"
    }

    init {
        val manager = context.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        autoConnectScanSettings = getScanSettings(ScanMode.LOW_POWER)
        scanSettings = getScanSettings(ScanMode.LOW_LATENCY)

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(adapterStateReceiver, filter)
    }
}