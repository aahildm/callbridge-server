package com.callbridge.phonea

/**
 * Tracks which transport (if any) Phone B is currently connected through,
 * so the server UI can show real connection status instead of nothing.
 */
object ConnectionStatus {
    // NONE, WIFI, BLUETOOTH
    @Volatile var transport: String = "NONE"
    var onChange: ((String) -> Unit)? = null

    fun setWifi() {
        transport = "WIFI"
        onChange?.invoke(transport)
    }

    fun setBluetooth() {
        transport = "BLUETOOTH"
        onChange?.invoke(transport)
    }

    fun setNone() {
        transport = "NONE"
        onChange?.invoke(transport)
    }
}
