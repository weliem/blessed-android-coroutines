package com.welie.blessed

import java.lang.RuntimeException

class ConnectionFailedException(val status: HciStatus) : RuntimeException("error $status (${status.value})") {
}