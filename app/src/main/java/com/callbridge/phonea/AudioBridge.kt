package com.callbridge.phonea

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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
 * over Bluetooth. Capturing call audio needs CAPTURE_AUDIO_OUTPUT, which Android only
 * grants to privileged system apps — install the Magisk module built by CI.
 *
 * Upstream (client mic -> remote caller): Android has no public API to inject audio
 * into a call's uplink. We first try the hidden "Telephony TX" output (incall music,
 * works on many Qualcomm ROMs for privileged apps). If unavailable, we play on the
 * loudspeaker so the server's own mic picks it up and sends it into the call.
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
    private var usedSpeakerFallback = false

    fun init(context: Context) { appContext = context.applicationContext }

    fun onBluetoothAudio(base64Chunk: String) {
        if (!running) return
        try {
            val pcm = Base64.decode(base64Chunk, Base64.NO_WRAP)
            player?.write(pcm, 0, pcm.size)
        } catch (e: Exception) { Log.e(TAG, "BT audio decode error: ${e.message}") }
    }

    fun start() {
        if (running) return
        running = true
        startPlayer()
        sendThread = Thread { captureLoop() }.also { it.start() }
        Log.d(TAG, "Audio bridge started")
    }

    private fun audioManager(): AudioManager? =
        appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun startPlayer() {
        val outBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING) * 2
        val track = AudioTrack(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(ENCODING)
                .setChannelMask(CHANNEL_OUT).build(),
            outBuf, AudioTrack.MODE_STREAM, 0)

        var routedToTelephony = false
        val am = audioManager()
        if (am != null && Build.VERSION.SDK_INT >= 23) {
            val tx = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == TYPE_TELEPHONY }
            if (tx != null) routedToTelephony = track.setPreferredDevice(tx)
        }
        if (!routedToTelephony && am != null) {
            // Fallback: loudspeaker so the phone's mic carries client voice into the call
            usedSpeakerFallback = true
            try { am.isSpeakerphoneOn = true } catch (_: Exception) {}
        }
        Log.d(TAG, "Uplink route: ${if (routedToTelephony) "telephony TX" else "speaker fallback"}")
        BluetoothServer.sendEvent("STATUS|Uplink: ${if (routedToTelephony) "direct" else "speaker"}")
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
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); continue }
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
        if (usedSpeakerFallback) {
            try { audioManager()?.isSpeakerphoneOn = false } catch (_: Exception) {}
            usedSpeakerFallback = false
        }
        Log.d(TAG, "Audio bridge stopped")
    }
}
