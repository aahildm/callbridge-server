package com.callbridge.phonea

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    private val TAG = "CallBridge-SMS"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].displayOriginatingAddress ?: "Unknown"
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val timestamp = System.currentTimeMillis()

        Log.d(TAG, "SMS from $sender: $body")

        // Forward to Phone B via WebSocket
        // Format: SMS_IN|sender|timestamp|body
        SocketServer.sendEvent("SMS_IN|$sender|$timestamp|$body")
    }
}
