package com.callbridge.phonea

import android.os.Handler
import android.os.Looper
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
                Call.STATE_DIALING -> {
                    ProtocolHandler.broadcast("STATE|DIALING")
                }
                Call.STATE_ACTIVE -> {
                    ProtocolHandler.broadcast("STATE|ACTIVE")
                    AudioBridge.start()
                }
                Call.STATE_DISCONNECTED -> {
                    ProtocolHandler.broadcast("ENDED")
                    AudioBridge.stop()
                    OngoingCall.clear()
                    // Send updated call log shortly after call ends
                    Handler(Looper.getMainLooper()).postDelayed({
                        CallLogHelper.sendRecentCallLog()
                    }, 1500)
                }
                Call.STATE_HOLDING -> {
                    ProtocolHandler.broadcast("STATE|HOLDING")
                }
            }
        }
    }

    override fun onCallAdded(call: Call) {
        Log.d(TAG, "Call added: ${call.details.handle}, state=${call.state}")
        OngoingCall.set(call)
        call.registerCallback(callCallback)

        when (call.state) {
            Call.STATE_RINGING -> ProtocolHandler.broadcast("RING|${OngoingCall.getCallerNumber()}")
            Call.STATE_DIALING -> ProtocolHandler.broadcast("STATE|DIALING")
            Call.STATE_ACTIVE -> {
                ProtocolHandler.broadcast("STATE|ACTIVE")
                AudioBridge.start()
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        Log.d(TAG, "Call removed")
        call.unregisterCallback(callCallback)
        ProtocolHandler.broadcast("ENDED")
        AudioBridge.stop()
        OngoingCall.clear()
        Handler(Looper.getMainLooper()).postDelayed({
            CallLogHelper.sendRecentCallLog()
        }, 1500)
    }
}
