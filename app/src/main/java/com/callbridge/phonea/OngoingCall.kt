package com.callbridge.phonea

import android.os.Handler
import android.os.Looper
import android.telecom.Call

object OngoingCall {
    private var call: Call? = null
    private val handler = Handler(Looper.getMainLooper())

    fun set(c: Call) {
        call = c
        // If a hangup was requested before the call was added, apply it now
        if (pendingHangup) {
            pendingHangup = false
            call?.disconnect()
        }
    }

    fun get(): Call? = call
    fun clear() { call = null }

    fun answer() {
        call?.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
    }

    fun reject() {
        call?.reject(false, null)
    }

    private var pendingHangup = false
    private var hangupRetries = 0

    /**
     * Hangs up the current call. If the call hasn't reached CallService yet
     * (e.g. cancelling right after DIAL), retries for up to 3 seconds instead
     * of silently doing nothing.
     */
    fun hangup() {
        val c = call
        if (c != null) {
            c.disconnect()
            return
        }
        // No call yet — mark pending and retry briefly
        pendingHangup = true
        hangupRetries = 0
        retryHangup()
    }

    private fun retryHangup() {
        if (!pendingHangup || hangupRetries >= 6) {
            pendingHangup = false
            return
        }
        val c = call
        if (c != null) {
            pendingHangup = false
            c.disconnect()
            return
        }
        hangupRetries++
        handler.postDelayed({ retryHangup() }, 500)
    }

    fun getCallerNumber(): String {
        return call?.details?.handle?.schemeSpecificPart ?: "Unknown"
    }

    fun getState(): Int {
        return call?.state ?: Call.STATE_DISCONNECTED
    }

    fun hasActiveCall(): Boolean = call != null
}
