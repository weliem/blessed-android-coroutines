/*
 * Copyright (c) Koninklijke Philips N.V., 2017.
 * All rights reserved.
 */
package com.welie.blessedexample

/**
 * Enum that contains all sensor contact feature as specified here:
 * https://www.bluetooth.com/specifications/gatt/viewer?attributeXmlFile=org.bluetooth.characteristic.heart_rate_measurement.xml
 */
enum class SensorContactFeature {
    NotSupported, SupportedNoContact, SupportedAndContact
}