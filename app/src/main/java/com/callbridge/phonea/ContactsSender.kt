package com.callbridge.phonea

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object ContactsSender {
    private const val TAG = "CallBridge-Contacts"
    var appContext: Context? = null

    @SuppressLint("MissingPermission")
    fun sendContacts() {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            val cursor: Cursor? = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val seen = mutableSetOf<String>()
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: continue
                    val number = it.getString(numIdx)?.replace(Regex("[\\s\\-()]"), "") ?: continue
                    val key = "$name|$number"
                    if (seen.add(key)) {
                        arr.put(JSONObject().apply {
                            put("name", name)
                            put("number", number)
                        })
                    }
                }
            }
            val encoded = Base64.encodeToString(arr.toString().toByteArray(), Base64.NO_WRAP)
            BluetoothServer.sendEvent("CONTACTS|$encoded")
            Log.d(TAG, "Sent ${arr.length()} contacts")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending contacts: ${e.message}")
        }
    }
}
