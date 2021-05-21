package com.welie.blessedexample

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.Handler
import com.welie.blessed.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.nio.ByteOrder
import java.util.*

internal class BluetoothHandler private constructor(private val context: Context) {
    // Local variables

    private val handler = Handler()
    private var currentTimeCounter = 0

    // Callback for peripherals
    private val peripheralCallback: BluetoothPeripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
//            // Request a higher MTU, iOS always asks for 185
//            peripheral.requestMtu(185)
//
//            // Request a new connection priority
//            peripheral.requestConnectionPriority(ConnectionPriority.HIGH)
//
            // Read manufacturer and model number from the Device Information Service
//            peripheral.readCharacteristic(DIS_SERVICE_UUID, MANUFACTURER_NAME_CHARACTERISTIC_UUID, new BluetoothPeripheralCallback() {
//                @Override
//                public void onCharacteristicRead(@NotNull BluetoothPeripheral peripheral, @NotNull byte[] value, @NotNull BluetoothGattCharacteristic characteristic, @NotNull GattStatus status) {
//                    BluetoothBytesParser parser = new BluetoothBytesParser(value);
//                    String manufacturer = parser.getStringValue(0);
//                    Timber.i("Received manufacturer: %s", manufacturer);
//                }
//            });

            GlobalScope.launch(SupervisorJob()) {
                try {
                    val manufacturerName = peripheral.readCharacteristic(DIS_SERVICE_UUID, MANUFACTURER_NAME_CHARACTERISTIC_UUID).asString()
                    Timber.i("Received: $manufacturerName")

                    val model = peripheral.readCharacteristic(DIS_SERVICE_UUID, MODEL_NUMBER_CHARACTERISTIC_UUID).asString()
                    Timber.i("Received: $model")

                    val batteryLevel = peripheral.readCharacteristic(BTS_SERVICE_UUID, BATTERY_LEVEL_CHARACTERISTIC_UUID).asUInt8()
                    Timber.i("Battery level: $batteryLevel")

                    peripheral.getCharacteristic(PLX_SERVICE_UUID, PLX_CONTINUOUS_MEASUREMENT_CHAR_UUID)?.let {
                        peripheral.observe(it) { value ->
                            val measurement = PulseOximeterContinuousMeasurement(value)
                            Timber.i(measurement.toString())
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    Timber.e("illegal argument")
                }
            }

            // peripheral.readCharacteristic(DIS_SERVICE_UUID, MODEL_NUMBER_CHARACTERISTIC_UUID);

//            // Turn on notifications for Current Time Service and write it if possible
//            val currentTimeCharacteristic = peripheral.getCharacteristic(CTS_SERVICE_UUID, CURRENT_TIME_CHARACTERISTIC_UUID)
//            if (currentTimeCharacteristic != null) {
//                peripheral.setNotify(currentTimeCharacteristic, true)
//
//                // If it has the write property we write the current time
//                if (currentTimeCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE > 0) {
//                    // Write the current time unless it is an Omron device
//                    val name = peripheral.name
//                    if (!name.contains("BLEsmart_")) {
//                        val parser = BluetoothBytesParser()
//                        parser.setCurrentTime(Calendar.getInstance())
//                        peripheral.writeCharacteristic(currentTimeCharacteristic, parser.value, WriteType.WITH_RESPONSE)
//                    }
//                }
//            }
//
//            // Try to turn on notifications for other characteristics
//            //  peripheral.readCharacteristic(BTS_SERVICE_UUID, BATTERY_LEVEL_CHARACTERISTIC_UUID);
//            peripheral.setNotify(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID, true)
//            peripheral.setNotify(HTS_SERVICE_UUID, TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID, true)
//            peripheral.setNotify(HRS_SERVICE_UUID, HEARTRATE_MEASUREMENT_CHARACTERISTIC_UUID, true)
//            peripheral.setNotify(PLX_SERVICE_UUID, PLX_CONTINUOUS_MEASUREMENT_CHAR_UUID, true)
//            peripheral.setNotify(PLX_SERVICE_UUID, PLX_SPOT_MEASUREMENT_CHAR_UUID, true)
//            peripheral.setNotify(WSS_SERVICE_UUID, WSS_MEASUREMENT_CHAR_UUID, true)
//            peripheral.setNotify(GLUCOSE_SERVICE_UUID, GLUCOSE_MEASUREMENT_CHARACTERISTIC_UUID, true)
//            peripheral.setNotify(GLUCOSE_SERVICE_UUID, GLUCOSE_MEASUREMENT_CONTEXT_CHARACTERISTIC_UUID, true)
//            peripheral.setNotify(GLUCOSE_SERVICE_UUID, GLUCOSE_RECORD_ACCESS_POINT_CHARACTERISTIC_UUID, true)
//            peripheral.setNotify(CONTOUR_SERVICE_UUID, CONTOUR_CLOCK, true)
        }

        override fun onNotificationStateUpdate(peripheral: BluetoothPeripheral, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
            if (status == GattStatus.SUCCESS) {
                val isNotifying = peripheral.isNotifying(characteristic)
                Timber.i("SUCCESS: Notify set to '%s' for %s", isNotifying, characteristic.uuid)
                if (characteristic.uuid == CONTOUR_CLOCK) {
                    writeContourClock(peripheral)
                } else if (characteristic.uuid == GLUCOSE_RECORD_ACCESS_POINT_CHARACTERISTIC_UUID) {
                    writeGetAllGlucoseMeasurements(peripheral)
                }
            } else {
                Timber.e("ERROR: Changing notification state failed for %s (%s)", characteristic.uuid, status)
            }
        }

        override fun onCharacteristicWrite(peripheral: BluetoothPeripheral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
            if (status == GattStatus.SUCCESS) {
                Timber.i("SUCCESS: Writing <%s> to <%s>", BluetoothBytesParser.bytes2String(value), characteristic.uuid)
            } else {
                Timber.i("ERROR: Failed writing <%s> to <%s> (%s)", BluetoothBytesParser.bytes2String(value), characteristic.uuid, status)
            }
        }

        override fun onCharacteristicRead(peripheral: BluetoothPeripheral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
            super.onCharacteristicRead(peripheral, value, characteristic, status)
            val characteristicUUID = characteristic.uuid
            val parser = BluetoothBytesParser(value)
            if (characteristicUUID == MANUFACTURER_NAME_CHARACTERISTIC_UUID) {
                val manufacturer = parser.getStringValue(0)
                Timber.i("Received manufacturer: %s", manufacturer)
            } else if (characteristicUUID == MODEL_NUMBER_CHARACTERISTIC_UUID) {
                val modelNumber = parser.getStringValue(0)
                Timber.i("Received modelnumber: %s", modelNumber)
            } else if (characteristicUUID == PNP_ID_CHARACTERISTIC_UUID) {
                val modelNumber = parser.getStringValue(0)
                Timber.i("Received pnp: %s", modelNumber)
            }
        }

        override fun onCharacteristicUpdate(peripheral: BluetoothPeripheral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: GattStatus) {
            if (status != GattStatus.SUCCESS) return
            val characteristicUUID = characteristic.uuid
            val parser = BluetoothBytesParser(value)
            if (characteristicUUID == BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID) {
                val measurement = BloodPressureMeasurement(value)
                val intent = Intent(MEASUREMENT_BLOODPRESSURE)
                intent.putExtra(MEASUREMENT_BLOODPRESSURE_EXTRA, measurement)
                sendMeasurement(intent, peripheral)
                Timber.d("%s", measurement)
            } else if (characteristicUUID == TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID) {
                val measurement = TemperatureMeasurement(value)
                val intent = Intent(MEASUREMENT_TEMPERATURE)
                intent.putExtra(MEASUREMENT_TEMPERATURE_EXTRA, measurement)
                sendMeasurement(intent, peripheral)
                Timber.d("%s", measurement)
            } else if (characteristicUUID == HEARTRATE_MEASUREMENT_CHARACTERISTIC_UUID) {
                val measurement = HeartRateMeasurement(value)
                val intent = Intent(MEASUREMENT_HEARTRATE)
                intent.putExtra(MEASUREMENT_HEARTRATE_EXTRA, measurement)
                sendMeasurement(intent, peripheral)
                Timber.d("%s", measurement)
            } else if (characteristicUUID == PLX_CONTINUOUS_MEASUREMENT_CHAR_UUID) {
                val measurement = PulseOximeterContinuousMeasurement(value)
                if (measurement.spO2 <= 100 && measurement.pulseRate <= 220) {
                    val intent = Intent(MEASUREMENT_PULSE_OX)
                    intent.putExtra(MEASUREMENT_PULSE_OX_EXTRA_CONTINUOUS, measurement)
                    sendMeasurement(intent, peripheral)
                }
                Timber.d("%s", measurement)
            } else if (characteristicUUID == PLX_SPOT_MEASUREMENT_CHAR_UUID) {
                val measurement = PulseOximeterSpotMeasurement(value)
                val intent = Intent(MEASUREMENT_PULSE_OX)
                intent.putExtra(MEASUREMENT_PULSE_OX_EXTRA_SPOT, measurement)
                sendMeasurement(intent, peripheral)
                Timber.d("%s", measurement)
            } else if (characteristicUUID == WSS_MEASUREMENT_CHAR_UUID) {
                val measurement = WeightMeasurement(value)
                val intent = Intent(MEASUREMENT_WEIGHT)
                intent.putExtra(MEASUREMENT_WEIGHT_EXTRA, measurement)
                sendMeasurement(intent, peripheral)
                Timber.d("%s", measurement)
            } else if (characteristicUUID == GLUCOSE_MEASUREMENT_CHARACTERISTIC_UUID) {
                val measurement = GlucoseMeasurement(value)
                val intent = Intent(MEASUREMENT_GLUCOSE)
                intent.putExtra(MEASUREMENT_GLUCOSE_EXTRA, measurement)
                sendMeasurement(intent, peripheral)
                Timber.d("%s", measurement)
            } else if (characteristicUUID == CURRENT_TIME_CHARACTERISTIC_UUID) {
                val currentTime = parser.dateTime
                Timber.i("Received device time: %s", currentTime)

                // Deal with Omron devices where we can only write currentTime under specific conditions
                val name = peripheral.name
                if (name.contains("BLEsmart_")) {
                    val bloodpressureMeasurement = peripheral.getCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID) ?: return
                    val isNotifying = peripheral.isNotifying(bloodpressureMeasurement)
                    if (isNotifying) currentTimeCounter++

                    // We can set device time for Omron devices only if it is the first notification and currentTime is more than 10 min from now
                    val interval = Math.abs(Calendar.getInstance().timeInMillis - currentTime.time)
                    if (currentTimeCounter == 1 && interval > 10 * 60 * 1000) {
                        parser.setCurrentTime(Calendar.getInstance())
                       // peripheral.writeCharacteristic(characteristic, parser.value, WriteType.WITH_RESPONSE)
                    }
                }
            } else if (characteristicUUID == BATTERY_LEVEL_CHARACTERISTIC_UUID) {
                val batteryLevel = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT8)
                Timber.i("Received battery level %d%%", batteryLevel)
            }
        }

        override fun onMtuChanged(peripheral: BluetoothPeripheral, mtu: Int, status: GattStatus) {
            Timber.i("new MTU set: %d", mtu)
        }

        private fun sendMeasurement(intent: Intent, peripheral: BluetoothPeripheral) {
            intent.putExtra(MEASUREMENT_EXTRA_PERIPHERAL, peripheral.address)
            context.sendBroadcast(intent)
        }

        private fun writeContourClock(peripheral: BluetoothPeripheral) {
            val calendar = Calendar.getInstance()
            val offsetInMinutes = calendar.timeZone.rawOffset / 60000
            calendar.timeZone = TimeZone.getTimeZone("UTC")
            val parser = BluetoothBytesParser(ByteOrder.LITTLE_ENDIAN)
            parser.setIntValue(1, BluetoothBytesParser.FORMAT_UINT8)
            parser.setIntValue(calendar[Calendar.YEAR], BluetoothBytesParser.FORMAT_UINT16)
            parser.setIntValue(calendar[Calendar.MONTH] + 1, BluetoothBytesParser.FORMAT_UINT8)
            parser.setIntValue(calendar[Calendar.DAY_OF_MONTH], BluetoothBytesParser.FORMAT_UINT8)
            parser.setIntValue(calendar[Calendar.HOUR_OF_DAY], BluetoothBytesParser.FORMAT_UINT8)
            parser.setIntValue(calendar[Calendar.MINUTE], BluetoothBytesParser.FORMAT_UINT8)
            parser.setIntValue(calendar[Calendar.SECOND], BluetoothBytesParser.FORMAT_UINT8)
            parser.setIntValue(offsetInMinutes, BluetoothBytesParser.FORMAT_SINT16)
  //          peripheral.writeCharacteristic(CONTOUR_SERVICE_UUID, CONTOUR_CLOCK, parser.value, WriteType.WITH_RESPONSE)
        }

        private fun writeGetAllGlucoseMeasurements(peripheral: BluetoothPeripheral) {
            val OP_CODE_REPORT_STORED_RECORDS: Byte = 1
            val OPERATOR_ALL_RECORDS: Byte = 1
            val command = byteArrayOf(OP_CODE_REPORT_STORED_RECORDS, OPERATOR_ALL_RECORDS)
 //           peripheral.writeCharacteristic(GLUCOSE_SERVICE_UUID, GLUCOSE_RECORD_ACCESS_POINT_CHARACTERISTIC_UUID, command, WriteType.WITH_RESPONSE)
        }
    }

    // Callback for central
    private val bluetoothCentralManagerCallback: BluetoothCentralManagerCallback = object : BluetoothCentralManagerCallback() {
        override fun onConnectedPeripheral(peripheral: BluetoothPeripheral) {
            Timber.i("connected to '%s'", peripheral.name)
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            Timber.e("connection '%s' failed with status %s", peripheral.name, status)
        }

        override fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: HciStatus) {
            Timber.i("disconnected '%s' with status %s", peripheral.name, status)

            // Reconnect to this device when it becomes available again
            handler.postDelayed({ central.autoConnectPeripheral(peripheral, peripheralCallback) }, 5000)
        }

        override fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            Timber.i("Found peripheral '%s'", peripheral.name)
            central.stopScan()
            central.connectPeripheral(peripheral, peripheralCallback)
        }

        override fun onBluetoothAdapterStateChanged(state: Int) {
            Timber.i("bluetooth adapter changed state to %d", state)
            if (state == BluetoothAdapter.STATE_ON) {
                // Bluetooth is on now, start scanning again
                // Scan for peripherals with a certain service UUIDs
                central.startPairingPopupHack()
                central.scanForPeripheralsWithServices(arrayOf(BLP_SERVICE_UUID, HTS_SERVICE_UUID, HRS_SERVICE_UUID))
            }
        }

        override fun onScanFailed(scanFailure: ScanFailure) {
            Timber.i("scanning failed with error %s", scanFailure)
        }
    }

    companion object {
        // Intent constants
        const val MEASUREMENT_BLOODPRESSURE = "blessed.measurement.bloodpressure"
        const val MEASUREMENT_BLOODPRESSURE_EXTRA = "blessed.measurement.bloodpressure.extra"
        const val MEASUREMENT_TEMPERATURE = "blessed.measurement.temperature"
        const val MEASUREMENT_TEMPERATURE_EXTRA = "blessed.measurement.temperature.extra"
        const val MEASUREMENT_HEARTRATE = "blessed.measurement.heartrate"
        const val MEASUREMENT_HEARTRATE_EXTRA = "blessed.measurement.heartrate.extra"
        const val MEASUREMENT_GLUCOSE = "blessed.measurement.glucose"
        const val MEASUREMENT_GLUCOSE_EXTRA = "blessed.measurement.glucose.extra"
        const val MEASUREMENT_PULSE_OX = "blessed.measurement.pulseox"
        const val MEASUREMENT_PULSE_OX_EXTRA_CONTINUOUS = "blessed.measurement.pulseox.extra.continuous"
        const val MEASUREMENT_PULSE_OX_EXTRA_SPOT = "blessed.measurement.pulseox.extra.spot"
        const val MEASUREMENT_WEIGHT = "blessed.measurement.weight"
        const val MEASUREMENT_WEIGHT_EXTRA = "blessed.measurement.weight.extra"
        const val MEASUREMENT_EXTRA_PERIPHERAL = "blessed.measurement.peripheral"

        // UUIDs for the Blood Pressure service (BLP)
        private val BLP_SERVICE_UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
        private val BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002A35-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Health Thermometer service (HTS)
        private val HTS_SERVICE_UUID = UUID.fromString("00001809-0000-1000-8000-00805f9b34fb")
        private val TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002A1C-0000-1000-8000-00805f9b34fb")
        private val PNP_ID_CHARACTERISTIC_UUID = UUID.fromString("00002A50-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Heart Rate service (HRS)
        private val HRS_SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
        private val HEARTRATE_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Device Information service (DIS)
        private val DIS_SERVICE_UUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb")
        private val MANUFACTURER_NAME_CHARACTERISTIC_UUID = UUID.fromString("00002A29-0000-1000-8000-00805f9b34fb")
        private val MODEL_NUMBER_CHARACTERISTIC_UUID = UUID.fromString("00002A24-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Current Time service (CTS)
        private val CTS_SERVICE_UUID = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb")
        private val CURRENT_TIME_CHARACTERISTIC_UUID = UUID.fromString("00002A2B-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Battery Service (BAS)
        private val BTS_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Pulse Oximeter Service (PLX)
        val PLX_SERVICE_UUID = UUID.fromString("00001822-0000-1000-8000-00805f9b34fb")
        private val PLX_SPOT_MEASUREMENT_CHAR_UUID = UUID.fromString("00002a5e-0000-1000-8000-00805f9b34fb")
        private val PLX_CONTINUOUS_MEASUREMENT_CHAR_UUID = UUID.fromString("00002a5f-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Weight Scale Service (WSS)
        val WSS_SERVICE_UUID = UUID.fromString("0000181D-0000-1000-8000-00805f9b34fb")
        private val WSS_MEASUREMENT_CHAR_UUID = UUID.fromString("00002A9D-0000-1000-8000-00805f9b34fb")
        val GLUCOSE_SERVICE_UUID = UUID.fromString("00001808-0000-1000-8000-00805f9b34fb")
        val GLUCOSE_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002A18-0000-1000-8000-00805f9b34fb")
        val GLUCOSE_RECORD_ACCESS_POINT_CHARACTERISTIC_UUID = UUID.fromString("00002A52-0000-1000-8000-00805f9b34fb")
        val GLUCOSE_MEASUREMENT_CONTEXT_CHARACTERISTIC_UUID = UUID.fromString("00002A34-0000-1000-8000-00805f9b34fb")

        // Contour Glucose Service
        val CONTOUR_SERVICE_UUID = UUID.fromString("00000000-0002-11E2-9E96-0800200C9A66")
        private val CONTOUR_CLOCK = UUID.fromString("00001026-0002-11E2-9E96-0800200C9A66")
        private var instance: BluetoothHandler? = null
        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): BluetoothHandler? {
            if (instance == null) {
                instance = BluetoothHandler(context.applicationContext)
            }
            return instance
        }
    }

    @JvmField
    var central: BluetoothCentralManager

    init {

        // Plant a tree
        Timber.plant(DebugTree())

        // Create BluetoothCentral
        central = BluetoothCentralManager(context, bluetoothCentralManagerCallback, Handler())

        // Scan for peripherals with a certain service UUIDs
        central.startPairingPopupHack()
        handler.postDelayed({ central.scanForPeripheralsWithServices(arrayOf(BLP_SERVICE_UUID, HTS_SERVICE_UUID, HRS_SERVICE_UUID, PLX_SERVICE_UUID, WSS_SERVICE_UUID, GLUCOSE_SERVICE_UUID)) }, 1000)
    }
}