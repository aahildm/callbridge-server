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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Acoustic audio bridge between Phone A's real GSM call and Phone B.
 *
 * Since Android blocks direct call-audio capture (VOICE_CALL source) on
 * non-rooted, non-system apps, this uses earpiece playback + close-range
 * mic pickup instead of speaker, which drastically reduces the echo/
 * feedback that speaker mode causes. Echo cancellation, noise suppression,
 * and automatic gain control are layered on top to clean up what's left.
 * This is inherently a workaround, not a true audio tap — quality will
 * still be a step below a real VoIP bridge, but should be clear enough
 * for normal conversation at moderate volume.
 */
object AudioBridge {

    private val TAG = "CallBridge-Audio"
    private const val SAMPLE_RATE = 16000 // higher rate helps AEC/NS quality
    private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val AUDIO_PORT = 9001
    private const val AUDIO_IN_PORT = 9002

    @Volatile var phoneBIp: String? = null
    @Volatile var bluetoothMode: Boolean = false

    private var sendThread: Thread? = null
    private var receiveThread: Thread? = null
    @Volatile private var running = false

    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var sendSocket: DatagramSocket? = null
    private var receiveSocket: DatagramSocket? = null
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
        } catch (e: Exception) {
            Log.e(TAG, "BT audio decode error: ${e.message}")
        }
    }

    fun init(am: AudioManager) {
        audioManager = am
    }

    fun start() {
        if (running) return

        try {
            audioManager?.let {
                previousSpeakerState = it.isSpeakerphoneOn
                previousVolume = it.getStreamVolume(AudioManager.STREAM_VOICE_CALL)

                it.mode = AudioManager.MODE_IN_COMMUNICATION
                // Earpiece, NOT speaker — far less acoustic leakage into the mic
                it.isSpeakerphoneOn = false

                // Turn the earpiece volume down a bit — quieter output means
                // less of it bleeds back into the mic pickup, and AEC has an
                // easier time canceling what's left.
                val maxVol = it.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val targetVol = (maxVol * 0.6).toInt().coerceAtLeast(1)
                it.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetVol, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure audio routing: ${e.message}")
        }

        if (bluetoothMode) {
            startBluetooth()
        } else {
            val ip = phoneBIp ?: run {
                Log.w(TAG, "Phone B IP not set, audio bridge not starting")
                return
            }
            startWifi(ip)
        }
    }

    /** Attaches AEC/NS/AGC to a recorder session if the device supports them. */
    private fun attachAudioEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                Log.d(TAG, "AEC enabled: ${echoCanceler?.enabled}")
            } else {
                Log.w(TAG, "AEC not available on this device")
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                Log.d(TAG, "NS enabled: ${noiseSuppressor?.enabled}")
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                Log.d(TAG, "AGC enabled: ${agc?.enabled}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach audio effects: ${e.message}")
        }
    }

    private fun releaseAudioEffects() {
        try { echoCanceler?.release() } catch (_: Exception) {}
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        echoCanceler = null
        noiseSuppressor = null
        agc = null
    }

    private fun startBluetooth() {
        Log.d(TAG, "Starting audio bridge over Bluetooth")
        running = true
        val outBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        player = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(ENCODING)
                .setChannelMask(CHANNEL_OUT)
                .build(),
            outBufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        player?.play()

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        sendThread = Thread {
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize
                )
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
            } catch (e: Exception) {
                Log.e(TAG, "BT send error: ${e.message}")
            } finally {
                releaseAudioEffects()
                recorder?.stop()
                recorder?.release()
            }
        }.also { it.start() }
    }

    private fun startWifi(ip: String) {
        Log.d(TAG, "Starting audio bridge over WiFi to $ip")
        running = true
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)

        sendThread = Thread {
            try {
                sendSocket = DatagramSocket()
                val address = InetAddress.getByName(ip)
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize
                )
                attachAudioEffects(recorder!!.audioSessionId)
                recorder?.startRecording()
                val buffer = ByteArray(bufferSize)
                while (running) {
                    val read = recorder?.read(buffer, 0, bufferSize) ?: break
                    if (read > 0) {
                        val packet = DatagramPacket(buffer, read, address, AUDIO_PORT)
                        sendSocket?.send(packet)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WiFi send error: ${e.message}")
            } finally {
                releaseAudioEffects()
                recorder?.stop()
                recorder?.release()
                sendSocket?.close()
            }
        }.also { it.start() }

        receiveThread = Thread {
            try {
                receiveSocket = DatagramSocket(AUDIO_IN_PORT)
                val outBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
                player = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(ENCODING)
                        .setChannelMask(CHANNEL_OUT)
                        .build(),
                    outBufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                player?.play()
                val buffer = ByteArray(outBufferSize)
                val packet = DatagramPacket(buffer, buffer.size)
                while (running) {
                    receiveSocket?.receive(packet)
                    player?.write(packet.data, 0, packet.length)
                }
            } catch (e: Exception) {
                Log.e(TAG, "WiFi receive error: ${e.message}")
            } finally {
                player?.stop()
                player?.release()
                receiveSocket?.close()
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        sendThread?.interrupt()
        receiveThread?.interrupt()
        sendThread = null
        receiveThread = null
        player?.stop()
        player?.release()
        player = null
        releaseAudioEffects()

        try {
            audioManager?.let {
                it.isSpeakerphoneOn = previousSpeakerState
                if (previousVolume >= 0) {
                    it.setStreamVolume(AudioManager.STREAM_VOICE_CALL, previousVolume, 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore audio state: ${e.message}")
        }
        Log.d(TAG, "Audio bridge stopped")
    }
}
