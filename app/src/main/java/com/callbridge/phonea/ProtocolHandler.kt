package com.callbridge.phonea

import android.util.Log

object ProtocolHandler {

    private val TAG = "CallBridge-Protocol"
    const val SECRET = "callbridge123"

    fun handle(message: String) {
        try {
            when {
                message == "ANSWER" -> OngoingCall.answer()
                message == "REJECT" -> OngoingCall.reject()
                message == "HANGUP" -> OngoingCall.hangup()
                message == "CANCEL_DIAL" -> OngoingCall.hangup()
                message == "HOLD" -> OngoingCall.hold()
                message == "UNHOLD" -> OngoingCall.unhold()
                message == "GET_CALLLOG" -> CallLogHelper.sendRecentCallLog()
                message == "GET_UPLINK_STATUS" -> AudioBridge.sendUplinkStatus()
                message == "GET_CONTACTS" -> ContactsSender.sendContacts()
                message == "GET_UPLINK_MODE" -> BluetoothServer.sendEvent("UPLINK_MODE|${AudioBridge.uplinkMode()}")
                message.startsWith("UPLINK_MODE|") -> AudioBridge.setUplinkMode(message.removePrefix("UPLINK_MODE|"))
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
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message '$message': ${e.message}", e)
        }
    }

    fun broadcast(event: String) {
        try {
            Log.d(TAG, "Broadcasting: $event")
            BluetoothServer.sendEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast failed: ${e.message}")
        }
    }
}
