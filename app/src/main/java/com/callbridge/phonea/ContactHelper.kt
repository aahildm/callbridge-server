package com.callbridge.phonea

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

object ContactHelper {
    private const val TAG = "CallBridge-Contacts"

    var appContext: Context? = null

    /** Returns the contact display name for [number], or empty string if not found. */
    fun lookupName(number: String): String {
        val ctx = appContext ?: return ""
        if (number.isBlank() || number == "Unknown") return ""
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                        ?: ""
                else ""
            } ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed for $number: ${e.message}")
            ""
        }
    }
}
