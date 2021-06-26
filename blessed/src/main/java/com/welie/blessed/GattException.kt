package com.welie.blessed

class GattException(val status: GattStatus) : RuntimeException("GATT error $status (${status.value})")