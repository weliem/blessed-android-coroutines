package com.welie.blessedexample

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessedexample.BluetoothHandler.Companion.getInstance
import com.welie.blessedexample.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import timber.log.Timber
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private var measurementValue: TextView? = null
    private val dateFormat: DateFormat = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        measurementValue = findViewById<View>(R.id.bloodPressureValue) as TextView
        registerReceiver(locationServiceStateReceiver, IntentFilter(LocationManager.MODE_CHANGED_ACTION))
    }

    override fun onResume() {
        super.onResume()
        if (BluetoothAdapter.getDefaultAdapter() != null) {
            if (!isBluetoothEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            } else {
                checkPermissions()
            }
        } else {
            Timber.e("This device has no Bluetooth hardware")
        }
    }

    private val isBluetoothEnabled: Boolean
        get() {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            return bluetoothAdapter.isEnabled
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)



    private fun initBluetoothHandler() {
        val bluetoothHandler = getInstance(applicationContext)

        collectBloodPressure(bluetoothHandler)
        collectHeartRate(bluetoothHandler)
        collectGlucose(bluetoothHandler)
        collectPulseOxContinuous(bluetoothHandler)
        collectPulseOxSpot(bluetoothHandler)
        collectTemperature(bluetoothHandler)
        collectWeight(bluetoothHandler)
    }

    private fun collectBloodPressure(bluetoothHandler: BluetoothHandler) {
        scope.launch {
            bluetoothHandler.bloodpressureChannel.consumeAsFlow().collect {
                withContext(Dispatchers.Main) {
                    measurementValue!!.text = String.format(
                        Locale.ENGLISH,
                        "%.0f/%.0f %s, %.0f bpm\n%s\n",
                        it.systolic,
                        it.diastolic,
                        if (it.unit == ObservationUnit.MMHG) "mmHg" else "kpa",
                        it.pulseRate,
                        dateFormat.format(it.timestamp ?: Calendar.getInstance())
                    )
                }
            }
        }
    }

    private fun collectGlucose(bluetoothHandler: BluetoothHandler) {
        scope.launch {
            bluetoothHandler.glucoseChannel.consumeAsFlow().collect {
                withContext(Dispatchers.Main) {
                    measurementValue!!.text = String.format(
                        Locale.ENGLISH,
                        "%.1f %s\n%s\n",
                        it.value,
                        if (it.unit === ObservationUnit.MmolPerLiter) "mmol/L" else "mg/dL",
                        dateFormat.format(it.timestamp ?: Calendar.getInstance()),
                    )
                }
            }
        }
    }

    private fun collectHeartRate(bluetoothHandler: BluetoothHandler) {
        scope.launch {
            bluetoothHandler.heartRateChannel.consumeAsFlow().collect {
                withContext(Dispatchers.Main) {
                    measurementValue?.text = String.format(Locale.ENGLISH, "%d bpm", it.pulse)
                }
            }
        }
    }

    private fun collectPulseOxContinuous(bluetoothHandler: BluetoothHandler) {
        scope.launch {
            bluetoothHandler.pulseOxContinuousChannel.consumeAsFlow().collect {
                withContext(Dispatchers.Main) {
                    measurementValue!!.text = String.format(
                        Locale.ENGLISH,
                        "SpO2 %d%%,  Pulse %d bpm\n%s\n\nfrom %s",
                        it.spO2,
                        it.pulseRate,
                        dateFormat.format(Calendar.getInstance())
                    )
                }
            }
        }
    }

    private fun collectPulseOxSpot(bluetoothHandler: BluetoothHandler) {
        scope.launch {
            bluetoothHandler.pulseOxSpotChannel.consumeAsFlow().collect {
                withContext(Dispatchers.Main) {
                    measurementValue!!.text = String.format(
                        Locale.ENGLISH,
                        "SpO2 %d%%,  Pulse %d bpm\n",
                        it.spO2,
                        it.pulseRate
                    )
                }
            }
        }
    }

    private fun collectTemperature(bluetoothHandler: BluetoothHandler) {
        scope.launch {
            bluetoothHandler.temperatureChannel.consumeAsFlow().collect {
                withContext(Dispatchers.Main) {
                    measurementValue?.text = String.format(
                        Locale.ENGLISH,
                        "%.1f %s (%s)\n%s\n",
                        it.temperatureValue,
                        if (it.unit == ObservationUnit.Celsius) "celsius" else "fahrenheit",
                        it.type,
                        dateFormat.format(it.timestamp ?: Calendar.getInstance())
                    )
                }
            }
        }
    }

    private fun collectWeight(bluetoothHandler: BluetoothHandler) {
        scope.launch {
            bluetoothHandler.weightChannel.consumeAsFlow().collect {
                withContext(Dispatchers.Main) {
                    measurementValue!!.text = String.format(
                            Locale.ENGLISH,
                            "%.1f %s\n%s\n",
                            it.weight, it.unit.toString(),
                            dateFormat.format(it.timestamp ?: Calendar.getInstance())
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(locationServiceStateReceiver)
    }

    private val locationServiceStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action != null && action == LocationManager.MODE_CHANGED_ACTION) {
                val isEnabled = areLocationServicesEnabled()
                Timber.i("Location service state changed to: %s", if (isEnabled) "on" else "off")
                checkPermissions()
            }
        }
    }

    private fun getPeripheral(peripheralAddress: String): BluetoothPeripheral {
        val central = getInstance(applicationContext).central
        return central.getPeripheral(peripheralAddress)
    }

    private fun checkPermissions() {
        val missingPermissions = getMissingPermissions(requiredPermissions)
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions, ACCESS_LOCATION_REQUEST)
        } else {
            permissionsGranted()
        }
    }

    private fun getMissingPermissions(requiredPermissions: Array<String>): Array<String> {
        val missingPermissions: MutableList<String> = ArrayList()
        for (requiredPermission in requiredPermissions) {
            if (applicationContext.checkSelfPermission(requiredPermission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(requiredPermission)
            }
        }
        return missingPermissions.toTypedArray()
    }

    private val requiredPermissions: Array<String>
        get() {
            val targetSdkVersion = applicationInfo.targetSdkVersion
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && targetSdkVersion >= Build.VERSION_CODES.Q) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) else arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

    private fun permissionsGranted() {
        // Check if Location services are on because they are required to make scanning work
        if (checkLocationServices()) {
            initBluetoothHandler()
        }
    }

    private fun areLocationServicesEnabled(): Boolean {
        val locationManager = applicationContext.getSystemService(LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        return isGpsEnabled || isNetworkEnabled
    }

    private fun checkLocationServices(): Boolean {
        return if (!areLocationServicesEnabled()) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Location services are not enabled")
                .setMessage("Scanning for Bluetooth peripherals requires locations services to be enabled.") // Want to enable?
                .setPositiveButton("Enable") { dialogInterface, _ ->
                    dialogInterface.cancel()
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    // if this button is clicked, just close
                    // the dialog box and do nothing
                    dialog.cancel()
                }
                .create()
                .show()
            false
        } else {
            true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // Check if all permission were granted
        var allGranted = true
        for (result in grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false
                break
            }
        }
        if (allGranted) {
            permissionsGranted()
        } else {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Location permission is required for scanning Bluetooth peripherals")
                .setMessage("Please grant permissions")
                .setPositiveButton("Retry") { dialogInterface, _ ->
                    dialogInterface.cancel()
                    checkPermissions()
                }
                .create()
                .show()
        }
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 1
        private const val ACCESS_LOCATION_REQUEST = 2
    }
}