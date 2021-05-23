package com.welie.blessed

import android.bluetooth.le.ScanSettings

/**
 * This class represents the possible scan modes
 */
enum class ScanMode(val value: Int) {
    /**
     * A special Bluetooth LE scan mode. Applications using this scan mode will passively listen for
     * other scan results without starting BLE scans themselves.
     */
    OPPORTUNISTIC(-1),

    /**
     * Perform Bluetooth LE scan in balanced power mode. Scan results are returned at a rate that
     * provides a good trade-off between scan frequency and power consumption.
     */
    BALANCED(ScanSettings.SCAN_MODE_BALANCED),

    /**
     * Scan using highest duty cycle. It's recommended to only use this mode when the application is
     * running in the foreground.
     */
    LOW_LATENCY(ScanSettings.SCAN_MODE_LOW_LATENCY),

    /**
     * Perform Bluetooth LE scan in low power mode. This is the default scan mode as it consumes the
     * least power. This mode is enforced if the scanning application is not in foreground.
     */
    LOW_POWER(ScanSettings.SCAN_MODE_LOW_POWER);
}