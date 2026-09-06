package com.callbridge.phonea

import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

class CallService : InCallService() {

    private val TAG = "CallBridge-CallService"

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            Log.d(TAG, "Call state changed: $state")
            when (state) {
                Call.STATE_RINGING -> {
                    ProtocolHandler.broadcast("RING|${OngoingCall.getCallerNumber()}")
                }
                Call.STATE_ACTIVE -> {
                    ProtocolHandler.broadcast("STATE|ACTIVE")
                    // Start audio bridge when call is active
                    AudioBridge.start()
                }
                Call.STATE_DISCONNECTED -> {
                    ProtocolHandler.broadcast("ENDED")
                    AudioBridge.stop()
                    OngoingCall.clear()
                }
                Call.STATE_HOLDING -> {
                    ProtocolHandler.broadcast("STATE|HOLDING")
                }
            }
        }
    }

    override fun onCallAdded(call: Call) {
        Log.d(TAG, "Call added: ${call.details.handle}")
        OngoingCall.set(call)
        call.registerCallback(callCallback)

        if (call.state == Call.STATE_RINGING) {
            ProtocolHandler.broadcast("RING|${OngoingCall.getCallerNumber()}")
        }
    }

    override fun onCallRemoved(call: Call) {
        Log.d(TAG, "Call removed")
        call.unregisterCallback(callCallback)
        ProtocolHandler.broadcast("ENDED")
        AudioBridge.stop()
        OngoingCall.clear()
    }
}
