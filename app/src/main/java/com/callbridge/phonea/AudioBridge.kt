package com.callbridge.phonea

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log

object AudioBridge {
    private val TAG = "CallBridge-Audio"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    private var sendThread: Thread? = null
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var previousSpeakerState = false
    private var previousVolume = -1
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    fun onBluetoothAudio(base64Chunk: String) {
        if (!running) return
        try {
            val pcm = Base64.decode(base64Chunk, Base64.NO_WRAP)
            player?.write(pcm, 0, pcm.size)
        } catch (e: Exception) { Log.e(TAG, "BT audio decode error: ${e.message}") }
    }

    fun init(am: AudioManager) { audioManager = am }

    fun start() {
        if (running) return
        running = true
        try {
            audioManager?.let {
                previousSpeakerState = it.isSpeakerphoneOn
                previousVolume = it.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                it.mode = AudioManager.MODE_IN_COMMUNICATION
                it.isSpeakerphoneOn = false
                val maxVol = it.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                it.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.6).toInt().coerceAtLeast(1), 0)
            }
        } catch (e: Exception) { Log.e(TAG, "Audio routing error: ${e.message}") }

        val outBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        player = AudioTrack(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(ENCODING)
                .setChannelMask(CHANNEL_OUT).build(),
            outBuf, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        player?.play()

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        sendThread = Thread {
            try {
                recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize)
                attachAudioEffects(recorder!!.audioSessionId)
                recorder?.startRecording()
                val buffer = ByteArray(bufferSize)
                while (running) {
                    val read = recorder?.read(buffer, 0, bufferSize) ?: break
                    if (read > 0) {
                        val chunk = Base64.encodeToString(buffer.copyOf(read), Base64.NO_WRAP)
                        BluetoothServer.sendEvent("AUDIO|$chunk")
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "BT send error: ${e.message}") }
            finally { releaseAudioEffects(); recorder?.stop(); recorder?.release(); recorder = null }
        }.also { it.start() }
        Log.d(TAG, "Audio bridge started (Bluetooth mode)")
    }

    private fun attachAudioEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
        } catch (e: Exception) { Log.e(TAG, "Audio effects error: ${e.message}") }
    }

    private fun releaseAudioEffects() {
        try { echoCanceler?.release() } catch (_: Exception) {}
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        echoCanceler = null; noiseSuppressor = null; agc = null
    }

    fun stop() {
        running = false
        sendThread?.interrupt(); sendThread = null
        player?.stop(); player?.release(); player = null
        releaseAudioEffects()
        try {
            audioManager?.let {
                it.isSpeakerphoneOn = previousSpeakerState
                if (previousVolume >= 0) it.setStreamVolume(AudioManager.STREAM_VOICE_CALL, previousVolume, 0)
            }
        } catch (e: Exception) { Log.e(TAG, "Restore audio error: ${e.message}") }
        Log.d(TAG, "Audio bridge stopped")
    }
}
