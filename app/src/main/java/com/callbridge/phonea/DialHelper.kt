package com.callbridge.phonea

import android.content.Context
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log

/** Places outgoing calls on behalf of Phone B via TelecomManager. */
object DialHelper {
    private val TAG = "CallBridge-Dial"

    var appContext: Context? = null

    fun placeCall(number: String) {
        val context = appContext ?: return
        try {
            val telecomManager = context.getSystemService(TelecomManager::class.java)
            val uri = Uri.fromParts("tel", number, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                telecomManager.placeCall(uri, null)
            }
            Log.d(TAG, "Placing call to $number")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing CALL_PHONE permission: ${e.message}")
            ProtocolHandler.broadcast("DIAL_FAIL|Missing permission")
        } catch (e: Exception) {
            Log.e(TAG, "Dial failed: ${e.message}")
            ProtocolHandler.broadcast("DIAL_FAIL|${e.message}")
        }
    }
}
