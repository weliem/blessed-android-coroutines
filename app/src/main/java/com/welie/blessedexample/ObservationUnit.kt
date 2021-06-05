package com.welie.blessedexample

enum class ObservationUnit(val notation: String, val mdc: String) {
    BeatsPerMinute("bpm", "MDC_DIM_BEAT_PER_MIN"),
    Celsius("\u00B0C", "MDC_DIM_DEGC"),
    Fahrenheit("\u00B0F", "MDC_DIM_FAHR"),
    Inches("inch", "MDC_DIM_INCH"),
    Kilograms("Kg", "MDC_DIM_KILO_G"),
    KgM2("kg/m2", "MDC_DIM_KG_PER_M_SQ"),
    KPA("kPa", "MDC_DIM_KILO_PASCAL"),
    Meters("m", "MDC_DIM_M"),
    MiligramPerDeciliter("mg/dL", "MDC_DIM_MILLI_G_PER_DL"),
    MmolPerLiter("mmol/L", "MDC_DIM_MILLI_MOLE_PER_L"),
    MMHG("mmHg", "MDC_DIM_MMHG"),
    Percent("%", "MDC_DIM_PERCENT"),
    Pounds("lbs", "MDC_DIM_LB"),
}