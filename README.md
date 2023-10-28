# BLESSED for Android with Coroutines - BLE made easy

[![Jitpack Link](https://jitpack.io/v/weliem/blessed-android-coroutines.svg)](https://jitpack.io/#weliem/blessed-android-coroutines)
[![Downloads](https://jitpack.io/v/weliem/blessed-android-coroutines/month.svg)](https://jitpack.io/#weliem/blessed-android-coroutines)
[![Android Build](https://github.com/weliem/blessed-android-coroutines/actions/workflows/gradle.yml/badge.svg)](https://github.com/weliem/blessed-android-coroutines/actions/workflows/gradle.yml)

BLESSED is a very compact Bluetooth Low Energy (BLE) library for Android 8 and higher, that makes working with BLE on Android very easy. It is powered by Kotlin's **Coroutines** and turns asynchronous GATT methods into synchronous methods! It is based on the [Blessed](https://github.com/weliem/blessed-android) Java library and has been rewritten in Kotlin using Coroutines.

## Installation

This library is available on Jitpack. Include the following in your projects's build.gradle file:

```groovy
allprojects {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```

Include the following in your app's build.gradle file under `dependencies` block:

```groovy
dependencies {
    ...
    implementation "com.github.weliem:blessed-android-coroutines:$version"
}
```

where `$version` is the latest published version in Jitpack [![Jitpack](https://jitpack.io/v/weliem/blessed-android-coroutines.svg)](https://jitpack.io/#weliem/blessed-android-coroutines)

### Adding permissions

If you plan on supporting older devices that are on Android 11 and below, then you need to add the below permissions to your AndroidManifest.xml file:

```xml
    <!-- Needed to target Android 11 and lower   -->
    <!-- Link: https://developer.android.com/guide/topics/connectivity/bluetooth/permissions#declare-android11-or-lower-->
    <uses-permission
        android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="30" />
    <uses-permission
        android:name="android.permission.ACCESS_COARSE_LOCATION"
        android:maxSdkVersion="30" />

    <!-- Link: https://developer.android.com/guide/topics/connectivity/bluetooth/permissions  -->
    <!-- Request legacy Bluetooth permissions on older devices. -->
    <uses-permission
        android:name="android.permission.BLUETOOTH"
        android:maxSdkVersion="30" />
    <uses-permission
        android:name="android.permission.BLUETOOTH_ADMIN"
        android:maxSdkVersion="30" />
```

## Overview of classes

The library consists of 5 core classes and corresponding callback abstract classes:

1. `BluetoothCentralManager`, for scanning and connecting peripherals
2. `BluetoothPeripheral`, for all peripheral related methods
3. `BluetoothPeripheralManager`, and its companion abstract class `BluetoothPeripheralManagerCallback`
4. `BluetoothCentral`
5. `BluetoothBytesParser`

The `BluetoothCentralManager` class is used to scan for devices and manage connections. The `BluetoothPeripheral` class is a replacement for the standard Android `BluetoothDevice` and `BluetoothGatt` classes. It wraps all GATT related peripheral functionality.

The `BluetoothPeripheralManager` class is used to create your own peripheral running on an Android phone. You can add service, control advertising, and deal with requests from remote centrals, represented by the `BluetoothCentral` class. For more about creating your own peripherals see the separate guide: [creating your own peripheral](SERVER.md)

The `BluetoothBytesParser` class is a utility class that makes parsing byte arrays easy. You can also use it to construct your own byte arrays by adding integers, floats, or strings.

## Scanning

The `BluetoothCentralManager` class has several differrent scanning methods:

```kotlin
fun scanForPeripherals(resultCallback: (BluetoothPeripheral, ScanResult) -> Unit, scanError: (ScanFailure) -> Unit )
fun scanForPeripheralsWithServices(serviceUUIDs: Array<UUID>, resultCallback: (BluetoothPeripheral, ScanResult) -> Unit, scanError: (ScanFailure) -> Unit)
fun scanForPeripheralsWithNames(peripheralNames: Array<String>, resultCallback: (BluetoothPeripheral, ScanResult) -> Unit,  scanError: (ScanFailure) -> Unit)
fun scanForPeripheralsWithAddresses(peripheralAddresses: Array<String>, resultCallback: (BluetoothPeripheral, ScanResult) -> Unit, scanError: (ScanFailure) -> Unit)
fun scanForPeripheralsUsingFilters(filters: List<ScanFilter>,resultCallback: (BluetoothPeripheral, ScanResult) -> Unit, scanError: (ScanFailure) -> Unit)
```

They all work in the same way and take an array of either service UUIDs, peripheral names, or mac addresses. When a peripheral is found your callback lambda will be called with the `BluetoothPeripheral` object and a `ScanResult` object that contains the scan details. The method `scanForPeripheralsUsingFilters` is for scanning using your own list of filters. See [Android documentation](https://developer.android.com/reference/android/bluetooth/le/ScanFilter) for more info on the use of `ScanFilter`. A second lambda is used to deliver any scan failures.

So in order to setup a scan for a device with the Bloodpressure service or HeartRate service, you do:

```kotlin

val BLP_SERVICE_UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
val HRS_SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")

central.scanForPeripheralsWithServices(arrayOf(BLP_SERVICE_UUID, HRS_SERVICE_UUID)            
    { peripheral, scanResult ->
        Timber.i("Found peripheral '${peripheral.name}' with RSSI ${scanResult.rssi}")
        central.stopScan()
        connectPeripheral(peripheral)
    },
    { scanFailure -> Timber.e("scan failed with reason $scanFailure") })
```

The scanning functions are not suspending functions and simply use a lambda function to receive the results.

**Note** Only 1 of these 4 types of scans can be active at one time! So call `stopScan()` before calling another scan.

## Connecting to devices

There are 3 ways to connect to a device:

```kotlin
suspend fun connectPeripheral(peripheral: BluetoothPeripheral): Unit
fun autoConnectPeripheral(peripheral: BluetoothPeripheral)
fun autoConnectPeripheralsBatch(batch: Set<BluetoothPeripheral>)
```

The method `connectPeripheral` is a **suspending function** that will try to immediately connect to a device that has already been found using a scan. This method will time out after 30 seconds or less, depending on the device manufacturer, and a `ConnectionFailedException` will be thrown. Note that there can be **only 1 outstanding** `connectPeripheral`. So if it is called multiple times only 1 will succeed.

```kotlin
scope.launch {
    try {
        central.connectPeripheral(peripheral)
    } catch (connectionFailed: ConnectionFailedException) {
        Timber.e("connection failed")
    }
}
```

The method `autoConnectPeripheral` will **not suspend** and is for re-connecting to known devices for which you already know the device's mac address. The BLE stack will automatically connect to the device when it sees it in its internal scan. Therefore, it may take longer to connect to a device but this call will never time out! So you can issue the autoConnect command and the device will be connected whenever it is found. This call will **also work** when the device is not cached by the Android stack, as BLESSED takes care of it! In contrary to `connectPeripheral`, there can be multiple outstanding `autoConnectPeripheral` requests.

The method `autoConnectPeripheralsBatch` is for re-connecting to multiple peripherals in one go. Since the normal `autoConnectPeripheral` may involve scanning, if peripherals are uncached, it is not suitable for calling very fast after each other, since it may trigger scanner limitations of Android. So use `autoConnectPeripheralsBatch` if you want to re-connect to many known peripherals.

If you know the mac address of your peripheral you can obtain a `BluetoothPeripheral` object using:

```kotlin
val peripheral = central.getPeripheral("CF:A9:BA:D9:62:9E")
```

After issuing a connect call, you can observe the connection state of peripherals:

```kotlin
central.observeConnectionState { peripheral, state ->
    Timber.i("Peripheral ${peripheral.name} has $state")
}
```

To disconnect or to cancel an outstanding `connectPeripheral()` or `autoConnectPeripheral()`, you call:

```kotlin
suspend fun cancelConnection(peripheral: BluetoothPeripheral): Unit
```

The function will suspend until the peripheral is disconnected.

## Service discovery

The BLESSED library will automatically do the service discovery for you. When the CONNECTED state is reached, the services have also been discovered.

In order to get the services you can use methods like `getServices()` or `getService(UUID)`. In order to get hold of characteristics you can call `getCharacteristic(UUID)` on the BluetoothGattService object or call `getCharacteristic()` on the BluetoothPeripheral object.

This callback is the proper place to start enabling notifications or read/write characteristics.

## Reading and writing

Reading and writing to characteristics/descriptors is done using the following methods:

```kotlin
suspend fun readCharacteristic(serviceUUID: UUID, characteristicUUID: UUID): ByteArray
suspend fun readCharacteristic(characteristic: BluetoothGattCharacteristic): ByteArray
suspend fun writeCharacteristic(serviceUUID: UUID, characteristicUUID: UUID, value: ByteArray, writeType: WriteType): ByteArray
suspend fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray, writeType: WriteType): ByteArray

suspend fun readDescriptor(descriptor: BluetoothGattDescriptor): ByteArray
suspend fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray): ByteArray
```

All methods are **suspending** and will return the result of the operation. The method `readCharacteristic` will return the ByteArray that has been read. It will throw `IllegalArgumentException` if the characteristic you provide is not readable, and it will throw `GattException` if the read was not successful.

If you want to write to a characteristic, you need to provide a `value` and a `writeType`. The `writeType` is usually `WITH_RESPONSE` or `WITHOUT_RESPONSE`. If the write type you specify is not supported by the characteristic it will throw `IllegalArgumentException`. The method will return the bytes that were written or an empty byte array in case something went wrong.

There are 2 ways to specify which characteristic to use in the read/write method:

- Using its `serviceUUID` and `characteristicUUID`
- Using the `BluetoothGattCharacteristic` reference directly

For example:

```kotlin
peripheral.getCharacteristic(DIS_SERVICE_UUID, MANUFACTURER_NAME_CHARACTERISTIC_UUID)?.let {
    val manufacturerName = peripheral.readCharacteristic(it).asString()
    Timber.i("Received: $manufacturerName")
}

val model = peripheral.readCharacteristic(DIS_SERVICE_UUID, MODEL_NUMBER_CHARACTERISTIC_UUID).asString()
Timber.i("Received: $model")
```

Note that there are also some extension methods like `asString()` and `asUInt8()` to quickly turn byte arrays in Strings or UInt8s.

## Turning notifications on/off

You can **observe** notifications/indications and receive them in the callback lambda. All the necessary operations like writing to the Client Characteristic Configuration descriptor are handled by Blessed. So all you need to do is:

```kotlin
peripheral.getCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID)?.let {
    peripheral.observe(it) { value ->
        val measurement = BloodPressureMeasurement.fromBytes(value)
        ...
    }
}
```

To stop observing notifications you call `peripheral.stopObserving(characteristic: BluetoothGattCharacteristic)`

## Bonding

BLESSED handles bonding for you and will make sure all bonding variants work smoothly. During the process of bonding, you will be informed of the process via a number of callbacks:

```kotlin
peripheral.observeBondState {
    Timber.i("Bond state is $it")
}
```

In most cases, the peripheral will initiate bonding either at the time of connection or when trying to read/write protected characteristics. However, if you want you can also initiate bonding yourself by calling `createBond` on a peripheral. There are two ways to do this:

- Calling `createBond` when not yet connected to a peripheral. In this case, a connection is made and bonding is requested.
- Calling `createBond` when already connected to a peripheral. In this case, only the bond is created.

It is also possible to remove a bond by calling `removeBond`. Note that this method uses a hidden Android API and may stop working in the future. When calling the `removeBond` method, the peripheral will also disappear from the settings menu on the phone.

Lastly, it is also possible to automatically issue a PIN code when pairing. Use the method `central.setPinCodeForPeripheral` to register a 6 digit PIN code. Once bonding starts, BLESSED will automatically issue the PIN code and the UI dialog to enter the PIN code will not appear anymore.

## Requesting a higher MTU to increase throughput

The default MTU is 23 bytes, which allows you to send and receive byte arrays of MTU - 3 = 20 bytes at a time. The 3 bytes overhead are used by the ATT packet. If your peripheral supports a higher MTU, you can request that by calling:

```kotlin
val mtu = peripheral.requestMtu(185)
```

The method will return the negotiated MTU value. Note that you may not get the value you requested if the peripheral doesn't accept your offer.
If you simply want the highest possible MTU, you can call `peripheral.requestMtu(BluetoothPeripheral.MAX_MTU)` and that will lead to receiving the highest possible MTU your peripheral supports.

Once the MTU has been set, you can always access it by calling `peripheral.currentMtu`. If you want to know the maximum length of the byte arrays that you can write, you can call the method `peripheral.getMaximumWriteValueLength()`. Note that the maximum value depends on the write type you want to use.

## Long reads and writes

The library also supports so called 'long reads/writes'. You don't need to do anything special for them. Just read a characteristic or descriptor as you normally do, and if the characteristic's value is longer than MTU - 1, then a series of reads will be done by the Android BLE stack. But you will simply receive the 'long' characteristic value in the same way as normal reads.

Similarly, for long writes, you just write to a characteristic or descriptor and the Android BLE stack will take care of the rest. But keep in mind that long writes only work with `WriteType.WITH_RESPONSE` and the maximum length of your byte array should be 512 or less. Note that not all peripherals support long reads/writes so this is not guaranteed to work always.

## Status codes

When connecting or disconnecting, the callback methods will contain a parameter `HciStatus status`. This enum class will have the value `SUCCESS` if the operation succeeded and otherwise it will provide a value indicating what went wrong.

Similarly, when doing GATT operations, the callbacks methods contain a parameter `GattStatus status`. These two enum classes replace the `int status` parameter that Android normally passes.

## Bluetooth 5 support

As of Android 8, Bluetooth 5 is natively supported. One of the things that Bluetooth 5 brings, is new physical layer options, called **Phy** that either give more speed or longer range.
The options you can choose are:

- **LE_1M**,  1 mbit PHY, compatible with Bluetooth 4.0, 4.1, 4.2 and 5.0
- **LE_2M**, 2 mbit PHY for higher speeds, requires Bluetooth 5.0
- **LE_CODED**, Coded PHY for long range connections, requires Bluetooth 5.0

You can set a preferred Phy by calling:

```kotlin
suspend fun setPreferredPhy(txPhy: PhyType, rxPhy: PhyType, phyOptions: PhyOptions): Phy
```

By calling `setPreferredPhy()` you indicate what you would like to have but it is not guaranteed that you get what you ask for. That depends on what the peripheral will actually support and give you.
If you are requesting `LE_CODED` you can also provide PhyOptions which has 3 possible values:

- **NO_PREFERRED**, for no preference (use this when asking for LE_1M or LE_2M)
- **S2**, for 2x long range
- **S8**, for 4x long range

The result of this negotiation will be received as a `Phy` object that is returned by `setPrefferedPhy`

As you can see the Phy for sending and receiving can be different but most of the time you will see the same Phy for both.
If you don't call `setPreferredPhy()`, Android seems to pick `PHY_LE_2M` if the peripheral supports Bluetooth 5. So in practice you only need to call `setPreferredPhy` if you want to use `PHY_LE_CODED`.

You can request the current values at any point by calling:

```kotlin
suspend fun readPhy(): Phy
```

It will return the current Phy

## Example application

An example application is provided in the repo. It shows how to connect to Blood Pressure meters, Heart Rate monitors, Weight scales, Glucose Meters, Pulse Oximeters, and Thermometers, read the data, and show it on screen. It only works with peripherals that use the Bluetooth SIG services. Working peripherals include:

- Beurer FT95 thermometer
- GRX Thermometer (TD-1241)
- Masimo MightySat
- Nonin 3230
- Indiehealth scale
- A&D 352BLE scale
- A&D 651BLE blood pressure meter
- Beurer BM57 blood pressure meter
- Soehnle Connect 300/400 blood pressure meter
- Polar H7/H10/OH1 heartrate monitors
- Contour Next One glucose meter
- Accu-Chek Instant glucose meter
