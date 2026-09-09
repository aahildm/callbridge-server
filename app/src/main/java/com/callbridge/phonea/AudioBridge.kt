package com.callbridge.phonea

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Bridges audio between Phone A's real GSM call and Phone B.
 *
 * Phone A puts the call on speaker mode so the actual call audio is
 * acoustically present at the mic, then captures that with AudioRecord to
 * send to Phone B, and plays Phone B's audio through the earpiece/speaker
 * so it's heard by the other party on the call. This avoids fighting with
 * the telecom stack's own audio session, which happens if AudioRecord tries
 * to grab VOICE_COMMUNICATION directly while InCallService already owns it.
 */
object AudioBridge {

    private val TAG = "CallBridge-Audio"
    private const val SAMPLE_RATE = 8000
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

        // Force the real call onto speaker so the mic can acoustically pick up
        // both sides of the conversation for the bridge to Phone B.
        try {
            audioManager?.let {
                previousSpeakerState = it.isSpeakerphoneOn
                it.mode = AudioManager.MODE_IN_COMMUNICATION
                it.isSpeakerphoneOn = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable speaker: ${e.message}")
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
                // MIC (not VOICE_COMMUNICATION) so it doesn't fight the telecom
                // audio session — it just picks up the acoustic sound in the room,
                // which includes both call parties since speaker mode is on.
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize
                )
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
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize
                )
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

        // Restore original speaker state so a normal (non-bridged) call
        // afterward behaves as expected.
        try {
            audioManager?.isSpeakerphoneOn = previousSpeakerState
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore speaker state: ${e.message}")
        }
        Log.d(TAG, "Audio bridge stopped")
    }
}
