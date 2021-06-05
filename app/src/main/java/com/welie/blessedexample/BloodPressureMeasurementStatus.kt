/*
 * Copyright (c) Koninklijke Philips N.V. 2020.
 * All rights reserved.
 */
package com.welie.blessedexample

class BloodPressureMeasurementStatus internal constructor(measurementStatus: Int) {
    /**
     * Body Movement Detected
     */
    val isBodyMovementDetected: Boolean
    /**
     * Cuff is too loose
     */
    val isCuffTooLoose: Boolean
    /**
     * Irregular pulse detected
     */
    val isIrregularPulseDetected: Boolean
    /**
     * Pulse is not in normal range
     */
    val isPulseNotInRange: Boolean
    /**
     * Improper measurement position
     */
    val isImproperMeasurementPosition: Boolean

    init {
        isBodyMovementDetected = measurementStatus and 0x0001 > 0
        isCuffTooLoose = measurementStatus and 0x0002 > 0
        isIrregularPulseDetected = measurementStatus and 0x0004 > 0
        isPulseNotInRange = measurementStatus and 0x0008 > 0
        isImproperMeasurementPosition = measurementStatus and 0x0020 > 0
    }
}