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

object AudioBridge {

    private val TAG = "CallBridge-Audio"
    private const val SAMPLE_RATE = 8000
    private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val AUDIO_PORT = 9001
    private const val AUDIO_IN_PORT = 9002

    @Volatile var phoneBIp: String? = null
    @Volatile var bluetoothMode: Boolean = false  // true when Phone B is on Bluetooth

    private var sendThread: Thread? = null
    private var receiveThread: Thread? = null
    @Volatile private var running = false

    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var sendSocket: DatagramSocket? = null
    private var receiveSocket: DatagramSocket? = null

    // Called by BluetoothServer when AUDIO| chunk arrives from Phone B
    fun onBluetoothAudio(base64Chunk: String) {
        if (!running) return
        try {
            val pcm = Base64.decode(base64Chunk, Base64.NO_WRAP)
            player?.write(pcm, 0, pcm.size)
        } catch (e: Exception) {
            Log.e(TAG, "BT audio decode error: ${e.message}")
        }
    }

    fun start() {
        if (running) return

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

        // Send call audio to Phone B via Bluetooth as base64
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        sendThread = Thread {
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
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
        Log.d(TAG, "Audio bridge stopped")
    }
}
