package com.callbridge.phonea

import android.content.Context
import android.provider.CallLog
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Reads recent call log entries and sends them to Phone B as JSON. */
object CallLogHelper {
    private val TAG = "CallBridge-CallLog"

    var appContext: Context? = null

    fun sendRecentCallLog(limit: Int = 50) {
        val context = appContext ?: return
        try {
            val entries = JSONArray()
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.CACHED_NAME
                ),
                null, null,
                "${CallLog.Calls.DATE} DESC LIMIT $limit"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val entry = JSONObject()
                    entry.put("number", it.getString(0) ?: "Unknown")
                    entry.put("type", it.getInt(1))
                    entry.put("date", it.getLong(2))
                    entry.put("duration", it.getLong(3))
                    entry.put("name", it.getString(4) ?: "")
                    entries.put(entry)
                }
            }
            // Base64-encode JSON to keep it safe as a single-line message
            val json = entries.toString()
            val encoded = Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
            ProtocolHandler.broadcast("CALLLOG|$encoded")
            Log.d(TAG, "Sent ${entries.length()} call log entries")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing READ_CALL_LOG permission")
        } catch (e: Exception) {
            Log.e(TAG, "Call log read failed: ${e.message}")
        }
    }
}
