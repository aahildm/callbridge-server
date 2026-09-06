package com.callbridge.phonea

import android.telephony.SmsManager
import android.util.Log

object SmsSender {
    private val TAG = "CallBridge-SMS"

    fun send(number: String, body: String) {
        try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(body)
            if (parts.size == 1) {
                smsManager.sendTextMessage(number, null, body, null, null)
            } else {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            }
            Log.d(TAG, "SMS sent to $number")
            ProtocolHandler.broadcast("SMS_SENT|$number")
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed: ${e.message}")
            ProtocolHandler.broadcast("SMS_FAIL|$number|${e.message}")
        }
    }
}
