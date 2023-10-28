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