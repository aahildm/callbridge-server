package com.callbridge.phonea

import android.telecom.Call

object OngoingCall {
    private var call: Call? = null

    fun set(c: Call) { call = c }
    fun get(): Call? = call
    fun clear() { call = null }

    fun answer() {
        call?.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
    }

    fun reject() {
        call?.reject(false, null)
    }

    fun hangup() {
        call?.disconnect()
    }

    fun getCallerNumber(): String {
        return call?.details?.handle?.schemeSpecificPart ?: "Unknown"
    }

    fun getState(): Int {
        return call?.state ?: Call.STATE_DISCONNECTED
    }
}
