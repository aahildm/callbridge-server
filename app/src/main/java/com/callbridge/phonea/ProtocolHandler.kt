package com.callbridge.phonea

import android.util.Log

object ProtocolHandler {

    private val TAG = "CallBridge-Protocol"
    const val SECRET = "callbridge123"

    fun handle(message: String) {
        when {
            message == "ANSWER" -> OngoingCall.answer()
            message == "REJECT" -> OngoingCall.reject()
            message == "HANGUP" -> OngoingCall.hangup()
            message == "GET_CALLLOG" -> CallLogHelper.sendRecentCallLog()
            message.startsWith("DIAL|") -> {
                val number = message.removePrefix("DIAL|")
                DialHelper.placeCall(number)
            }
            message.startsWith("SMS_SEND|") -> {
                val parts = message.removePrefix("SMS_SEND|").split("|", limit = 2)
                if (parts.size == 2) {
                    SmsSender.send(parts[0], parts[1])
                }
            }
            else -> Log.w(TAG, "Unknown message: $message")
        }
    }

    fun broadcast(event: String) {
        Log.d(TAG, "Broadcasting: $event")
        SocketServer.sendEvent(event)
        BluetoothServer.sendEvent(event)
    }
}
