package com.callbridge.phonea

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    @Volatile private var currentUplinkDesc = "unknown"
    // Software gain applied to client mic audio before injection (incall paths are often attenuated)
    private const val UPLINK_GAIN = 4.0f
    // Diagnostics
    @Volatile private var rxChunks = 0
    @Volatile private var rxPeak = 0
    @Volatile private var writeErrors = 0
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val peakPct = rxPeak * 100 / 32767
            BluetoothServer.sendEvent("STATUS|Uplink: $currentUplinkDesc · rx ${rxChunks} pkts · mic ${peakPct}%" +
                if (writeErrors > 0) " · ${writeErrors} write errs" else "")
            rxChunks = 0; rxPeak = 0; writeErrors = 0
            statsHandler.postDelayed(this, 3000)
        }
    }

    fun init(context: Context) { appContext = context.applicationContext }

    /** "speaker" (default, works everywhere) or "tx" (direct injection; ROM-dependent). */
    fun uplinkMode(): String =
        appContext?.getSharedPreferences("callbridge", Context.MODE_PRIVATE)
            ?.getString("uplink_mode", "speaker") ?: "speaker"

    fun setUplinkMode(mode: String) {
        if (mode != "speaker" && mode != "tx") return
        appContext?.getSharedPreferences("callbridge", Context.MODE_PRIVATE)
            ?.edit()?.putString("uplink_mode", mode)?.apply()
        BluetoothServer.sendEvent("UPLINK_MODE|$mode")
        // Apply immediately if a call is in progress
        if (running) {
            Handler(Looper.getMainLooper()).post {
                try { player?.stop() } catch (_: Exception) {}
                player?.release(); player = null
                try { audioManager()?.isSpeakerphoneOn = false } catch (_: Exception) {}
                startPlayer()
            }
        }
    }

    fun onBluetoothAudio(base64Chunk: String) {
        if (!running) return
        try {
            val pcm = Base64.decode(base64Chunk, Base64.NO_WRAP)
            applyGainAndMeasure(pcm)
            rxChunks++
            val written = player?.write(pcm, 0, pcm.size) ?: -1
            if (written < 0) { writeErrors++; Log.w(TAG, "AudioTrack write returned $written") }
        } catch (e: Exception) { Log.e(TAG, "BT audio decode error: ${e.message}") }
    }

    /** Boost 16-bit LE PCM in place with clipping, and track peak level of the raw input. */
    private fun applyGainAndMeasure(pcm: ByteArray) {
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val abs = if (sample < 0) -sample else sample
            if (abs > rxPeak) rxPeak = abs
            val boosted = (sample * UPLINK_GAIN).toInt().coerceIn(-32768, 32767)
            pcm[i] = (boosted and 0xFF).toByte()
            pcm[i + 1] = ((boosted shr 8) and 0xFF).toByte()
            i += 2
        }
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

        // Max volume so the injected audio is as loud as possible into the TX path
        track.setVolume(AudioTrack.getMaxVolume())

        // Check the track was actually initialised before trying to route it
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialise (state=${track.state}), releasing")
            track.release()
            BluetoothServer.sendEvent("STATUS|Uplink: TRACK_INIT_FAILED")
            return
        }

        var uplinkDesc = "speaker"

        // Attempt 1: route directly to telephony TX (Qualcomm incall music path)
        if (uplinkMode() == "tx" && Build.VERSION.SDK_INT >= 23 && am != null) {
            // Some Qualcomm HALs need this before the incall-music mixer path is used
            try { am.setParameters("incall_music_enabled=true") } catch (_: Exception) {}
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

        track.play()
        player = track
        currentUplinkDesc = uplinkDesc

        // setPreferredDevice() can return true even when the ROM ignores it.
        // After playback starts, check where audio is REALLY going; if not TX, fall back to speaker.
        Handler(Looper.getMainLooper()).postDelayed({
            val p = player ?: return@postDelayed
            if (Build.VERSION.SDK_INT >= 24 && currentUplinkDesc == "direct-TX") {
                val routed = p.routedDevice
                if (routed == null || routed.type != TYPE_TELEPHONY) {
                    Log.w(TAG, "TX not honored, actually routed to type=${routed?.type}; falling back to speaker")
                    try { p.setPreferredDevice(null) } catch (_: Exception) {}
                    try { am?.isSpeakerphoneOn = true } catch (_: Exception) {}
                    currentUplinkDesc = "speaker (TX ignored, was type ${routed?.type ?: "none"})"
                }
            }
            BluetoothServer.sendEvent("STATUS|Uplink: $currentUplinkDesc")
            statsHandler.removeCallbacks(statsRunnable)
            statsHandler.postDelayed(statsRunnable, 3000)
        }, 700)
    }

    fun sendUplinkStatus() {
        val p = player
        val desc = when {
            p == null -> "not-started"
            p.state != AudioTrack.STATE_INITIALIZED -> "TRACK_INIT_FAILED"
            else -> currentUplinkDesc
        }
        BluetoothServer.sendEvent("STATUS|Uplink: $desc")
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
        statsHandler.removeCallbacks(statsRunnable)
        currentUplinkDesc = "unknown"
        sendThread?.interrupt(); sendThread = null
        try { player?.stop() } catch (_: Exception) {}
        player?.release(); player = null
        // Restore speakerphone and audio mode
        try { audioManager()?.isSpeakerphoneOn = false } catch (_: Exception) {}
        try { audioManager()?.mode = prevAudioMode } catch (_: Exception) {}
        Log.d(TAG, "Audio bridge stopped")
    }
}
