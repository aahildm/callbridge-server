package com.callbridge.phonea

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.util.Log

object OngoingCall {
    private val TAG = "CallBridge-OngoingCall"

    @Volatile private var call: Call? = null
    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var pendingHangup = false
    @Volatile private var hangupRetries = 0

    fun set(c: Call) {
        call = c
        if (pendingHangup) {
            pendingHangup = false
            safeDisconnect(c)
        }
    }

    fun get(): Call? = call
    fun clear() { call = null }

    fun answer() {
        try {
            call?.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
        } catch (e: Exception) {
            Log.e(TAG, "answer() failed: ${e.message}")
        }
    }

    fun reject() {
        try {
            call?.reject(false, null)
        } catch (e: Exception) {
            Log.e(TAG, "reject() failed: ${e.message}")
        }
    }

    fun hold() {
        try {
            call?.hold()
        } catch (e: Exception) {
            Log.e(TAG, "hold() failed: ${e.message}")
        }
    }

    fun unhold() {
        try {
            call?.unhold()
        } catch (e: Exception) {
            Log.e(TAG, "unhold() failed: ${e.message}")
        }
    }

    private fun safeDisconnect(c: Call) {
        try {
            c.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "disconnect() failed: ${e.message}")
        }
    }

    fun hangup() {
        try {
            val c = call
            if (c != null) {
                pendingHangup = false
                safeDisconnect(c)
                return
            }
            pendingHangup = true
            hangupRetries = 0
            retryHangup()
        } catch (e: Exception) {
            Log.e(TAG, "hangup() failed: ${e.message}")
        }
    }

    private fun retryHangup() {
        if (!pendingHangup || hangupRetries >= 6) {
            pendingHangup = false
            return
        }
        val c = call
        if (c != null) {
            pendingHangup = false
            safeDisconnect(c)
            return
        }
        hangupRetries++
        try {
            handler.postDelayed({ retryHangup() }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "retryHangup schedule failed: ${e.message}")
            pendingHangup = false
        }
    }

    fun getCallerNumber(): String {
        return try {
            call?.details?.handle?.schemeSpecificPart ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    fun getState(): Int {
        return call?.state ?: Call.STATE_DISCONNECTED
    }

    fun hasActiveCall(): Boolean = call != null
}
