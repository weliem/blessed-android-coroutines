package com.welie.blessed

import java.lang.RuntimeException

class ConnectionFailedException(val status: HciStatus) : RuntimeException("connection failed: $status (${status.value})")