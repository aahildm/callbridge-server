package com.callbridge.phonea

import android.content.Context
import android.media.AudioManager
import android.util.Log

/** Controls mute and speakerphone on Phone A's actual call, driven by Phone B. */
object CallAudioHelper {
    private val TAG = "CallBridge-CallAudio"

    var appContext: Context? = null
    private var muted = false
    private var speakerOn = false

    private fun audioManager(): AudioManager? =
        appContext?.getSystemService(AudioManager::class.java)

    fun setMute(mute: Boolean) {
        try {
            audioManager()?.isMicrophoneMute = mute
            muted = mute
            ProtocolHandler.broadcast("MUTE_STATE|${if (mute) "ON" else "OFF"}")
            Log.d(TAG, "Mic mute: $mute")
        } catch (e: Exception) {
            Log.e(TAG, "Mute failed: ${e.message}")
        }
    }

    fun toggleMute() = setMute(!muted)

    fun setSpeaker(on: Boolean) {
        try {
            val am = audioManager() ?: return
            am.isSpeakerphoneOn = on
            speakerOn = on
            ProtocolHandler.broadcast("SPEAKER_STATE|${if (on) "ON" else "OFF"}")
            Log.d(TAG, "Speaker: $on")
        } catch (e: Exception) {
            Log.e(TAG, "Speaker toggle failed: ${e.message}")
        }
    }

    fun toggleSpeaker() = setSpeaker(!speakerOn)

    fun reset() {
        muted = false
        speakerOn = false
    }
}
