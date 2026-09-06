package com.callbridge.phonea

import android.util.Log

/**
 * Shared handling for post-auth messages, regardless of which transport
 * (WiFi WebSocket or Bluetooth RFCOMM) they arrived on. Also the single
 * source of truth for the shared secret and for broadcasting outgoing
 * events to every connected transport.
 */
object ProtocolHandler {

    private val TAG = "CallBridge-Protocol"
    const val SECRET = "callbridge123" // Must match Phone B

    fun handle(message: String) {
        when (message) {
            "ANSWER" -> OngoingCall.answer()
            "REJECT" -> OngoingCall.reject()
            "HANGUP" -> OngoingCall.hangup()
            else -> {
                if (message.startsWith("SMS_SEND|")) {
                    val parts = message.removePrefix("SMS_SEND|").split("|", limit = 2)
                    if (parts.size == 2) {
                        SmsSender.send(parts[0], parts[1])
                    }
                }
            }
        }
    }

    /** Broadcast an event to every connected client, on every transport. */
    fun broadcast(event: String) {
        Log.d(TAG, "Broadcasting: $event")
        SocketServer.sendEvent(event)
        BluetoothServer.sendEvent(event)
    }
}
