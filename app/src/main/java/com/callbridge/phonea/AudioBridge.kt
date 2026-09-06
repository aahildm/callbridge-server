package com.callbridge.phonea

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object AudioBridge {

    private val TAG = "CallBridge-Audio"

    // Audio config — 8kHz mono matches cellular voice quality
    private const val SAMPLE_RATE = 8000
    private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val AUDIO_PORT = 9001   // UDP port for sending audio to Phone B
    private const val AUDIO_IN_PORT = 9002 // UDP port for receiving audio from Phone B

    @Volatile var phoneBIp: String? = null  // Set when Phone B connects and sends its IP

    private var sendThread: Thread? = null
    private var receiveThread: Thread? = null
    @Volatile private var running = false

    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var sendSocket: DatagramSocket? = null
    private var receiveSocket: DatagramSocket? = null

    fun start() {
        if (running) return
        val ip = phoneBIp ?: run {
            Log.w(TAG, "Phone B IP not set, audio bridge not starting")
            return
        }

        running = true
        Log.d(TAG, "Starting audio bridge to $ip")

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)

        // Send: record call audio and stream to Phone B
        sendThread = Thread {
            try {
                sendSocket = DatagramSocket()
                val address = InetAddress.getByName(ip)
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
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
                Log.e(TAG, "Send error: ${e.message}")
            } finally {
                recorder?.stop()
                recorder?.release()
                sendSocket?.close()
            }
        }.also { it.start() }

        // Receive: get audio from Phone B mic and inject into call
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
                Log.e(TAG, "Receive error: ${e.message}")
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
        Log.d(TAG, "Audio bridge stopped")
    }
}
