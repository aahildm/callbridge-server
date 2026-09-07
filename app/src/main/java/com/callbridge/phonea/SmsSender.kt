package com.callbridge.phonea

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

object SmsSender {
    private val TAG = "CallBridge-SMS"

    // Context needed for non-deprecated SmsManager on API 31+
    var appContext: Context? = null

    fun send(number: String, body: String) {
        try {
            @Suppress("DEPRECATION")
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appContext?.getSystemService(SmsManager::class.java)
                    ?: SmsManager.getDefault()
            } else {
                SmsManager.getDefault()
            }
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
