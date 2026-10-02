package com.callbridge.phonea

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log

/**
 * Server side of the call audio bridge.
 *
 * Downstream (remote caller -> client): capture the call's downlink audio and send it
 * over Bluetooth. Capturing call audio needs CAPTURE_AUDIO_OUTPUT — install the Magisk module.
 *
 * Upstream (client mic -> remote caller): Android has no public API to inject audio into a
 * call's uplink directly. Strategy:
 *   1. Try AudioDeviceInfo.TYPE_TELEPHONY (type=18) — "incall music" route on Qualcomm ROMs.
 *      This injects audio directly into the TX path so the remote caller hears it.
 *   2. Fallback: set AudioManager to MODE_IN_CALL + earpiece, play via VOICE_COMMUNICATION
 *      usage. On some devices this bleeds into the mic and therefore into the uplink.
 *   3. Last resort: speaker so the OnePlus 2's own mic picks up the Poco's voice.
 */
object AudioBridge {
    private const val TAG = "CallBridge-Audio"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val TYPE_TELEPHONY = 18 // AudioDeviceInfo.TYPE_TELEPHONY

    private var appContext: Context? = null
    private var sendThread: Thread? = null
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var prevAudioMode = AudioManager.MODE_NORMAL

    fun init(context: Context) { appContext = context.applicationContext }

    fun onBluetoothAudio(base64Chunk: String) {
        if (!running) return
        try {
            val pcm = Base64.decode(base64Chunk, Base64.NO_WRAP)
            val written = player?.write(pcm, 0, pcm.size) ?: -1
            if (written < 0) Log.w(TAG, "AudioTrack write returned $written")
        } catch (e: Exception) { Log.e(TAG, "BT audio decode error: ${e.message}") }
    }

    fun start() {
        if (running) return
        running = true
        startPlayer()
        sendThread = Thread { captureLoop() }.also { it.isDaemon = true; it.start() }
        Log.d(TAG, "Audio bridge started")
    }

    private fun audioManager(): AudioManager? =
        appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun startPlayer() {
        val am = audioManager()

        // Save current mode so we can restore it on stop()
        prevAudioMode = am?.mode ?: AudioManager.MODE_NORMAL

        // Switch to IN_CALL mode so audio routing behaves like a real call
        try { am?.mode = AudioManager.MODE_IN_CALL } catch (e: Exception) {
            Log.w(TAG, "Could not set MODE_IN_CALL: ${e.message}")
        }

        val sessionId = am?.generateAudioSessionId() ?: AudioManager.AUDIO_SESSION_ID_GENERATE

        val outBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING) * 2
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(ENCODING)
                .setChannelMask(CHANNEL_OUT)
                .build(),
            outBuf, AudioTrack.MODE_STREAM, sessionId)

        // Check the track was actually initialised before trying to route it
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialise (state=${track.state}), releasing")
            track.release()
            BluetoothServer.sendEvent("STATUS|Uplink: TRACK_INIT_FAILED")
            return
        }

        var uplinkDesc = "speaker"

        // Attempt 1: route directly to telephony TX (Qualcomm incall music path)
        if (Build.VERSION.SDK_INT >= 23 && am != null) {
            val tx = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == TYPE_TELEPHONY }
            if (tx != null && track.setPreferredDevice(tx)) {
                uplinkDesc = "direct-TX"
                Log.d(TAG, "Uplink routed to TYPE_TELEPHONY device: ${tx.productName}")
            }
        }

        if (uplinkDesc == "speaker") {
            // Fallback: play client audio through the loudspeaker.
            // The OnePlus 2's physical mic then picks it up and feeds it into the call's
            // uplink path (acoustic coupling). Quality is lower but it works.
            try {
                am?.isSpeakerphoneOn = true
                Log.d(TAG, "Speaker fallback enabled for uplink injection")
            } catch (e: Exception) {
                Log.w(TAG, "isSpeakerphoneOn=true failed: ${e.message}")
            }
        }

        Log.d(TAG, "Uplink route: $uplinkDesc (state=${track.state})")
        BluetoothServer.sendEvent("STATUS|Uplink: $uplinkDesc")

        track.play()
        player = track
    }

    private fun openRecorder(): Pair<AudioRecord, String>? {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING) * 2
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_DOWNLINK to "VOICE_DOWNLINK",
            MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL",
            MediaRecorder.AudioSource.MIC to "MIC")
        for ((src, name) in sources) {
            try {
                val r = AudioRecord(src, SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize)
                if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
                r.startRecording()
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.stop(); r.release(); continue }
                return r to name
            } catch (e: Exception) {
                Log.w(TAG, "Source $name unavailable: ${e.message}")
            }
        }
        return null
    }

    private fun captureLoop() {
        try {
            val opened = openRecorder()
            if (opened == null) {
                BluetoothServer.sendEvent("STATUS|Audio capture failed on server")
                return
            }
            val (rec, name) = opened
            recorder = rec
            Log.d(TAG, "Capturing from $name")
            BluetoothServer.sendEvent("STATUS|Downlink source: $name")
            val buffer = ByteArray(640) // 20 ms @ 16 kHz mono 16-bit
            while (running) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read > 0) {
                    BluetoothServer.sendEvent("AUDIO|" + Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP))
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord read error: $read")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture error: ${e.message}")
        } finally {
            try { recorder?.stop() } catch (_: Exception) {}
            recorder?.release(); recorder = null
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sendThread?.interrupt(); sendThread = null
        try { player?.stop() } catch (_: Exception) {}
        player?.release(); player = null
        // Restore audio mode
        try { audioManager()?.mode = prevAudioMode } catch (_: Exception) {}
        Log.d(TAG, "Audio bridge stopped")
    }
}
