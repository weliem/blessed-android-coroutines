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

import android.bluetooth.le.AdvertiseCallback

/**
 * This enum describes all possible errors that can occur when trying to start advertising
 */
enum class AdvertiseError(val value: Int) {
    /**
     * Failed to start advertising as the advertise data to be broadcasted is larger than 31 bytes.
     */
    DATA_TOO_LARGE(AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE),

    /**
     * Failed to start advertising because no advertising instance is available.
     */
    TOO_MANY_ADVERTISERS(AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS),

    /**
     * Failed to start advertising as the advertising is already started.
     */
    ALREADY_STARTED(AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED),

    /**
     * Operation failed due to an internal error.
     */
    INTERNAL_ERROR(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR),

    /**
     * This feature is not supported on this platform.
     */
    FEATURE_UNSUPPORTED(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED), UNKNOWN_ERROR(-1);

    companion object {
        @JvmStatic
        fun fromValue(value: Int): AdvertiseError {
            for (type in values()) {
                if (type.value == value) return type
            }
            return UNKNOWN_ERROR
        }
    }
}