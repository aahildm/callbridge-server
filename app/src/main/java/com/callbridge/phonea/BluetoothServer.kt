package com.callbridge.phonea

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

object BluetoothServer {

    private val TAG = "CallBridge-BtServer"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptThread: Thread? = null
    private var running = false

    private class Connection(val socket: BluetoothSocket, val out: OutputStream)

    private val connections = CopyOnWriteArraySet<Connection>()
    private val authenticated = CopyOnWriteArraySet<Connection>()

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Log.e(TAG, "No Bluetooth adapter")
            return
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth disabled")
            return
        }

        running = true
        acceptThread = Thread {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("CallBridge", SPP_UUID)
                Log.d(TAG, "Bluetooth server listening")
                while (running) {
                    val socket = try {
                        serverSocket?.accept() ?: break
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept failed: ${e.message}")
                        break
                    }
                    handleClient(socket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }
        acceptThread?.start()
    }

    private fun handleClient(socket: BluetoothSocket) {
        val conn = Connection(socket, socket.outputStream)
        connections.add(conn)
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                while (true) {
                    val line = reader.readLine() ?: break
                    onMessage(conn, line)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Client read loop ended: ${e.message}")
            } finally {
                connections.remove(conn)
                authenticated.remove(conn)
                AudioBridge.bluetoothMode = false
                if (authenticated.isEmpty()) ConnectionStatus.setNone()
                try { socket.close() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun onMessage(conn: Connection, message: String) {
        if (message.startsWith("AUDIO|")) {
            AudioBridge.onBluetoothAudio(message.removePrefix("AUDIO|"))
            return
        }

        Log.d(TAG, "Received (BT): $message")

        if (message.startsWith("AUTH|")) {
            val token = message.removePrefix("AUTH|")
            if (token == ProtocolHandler.SECRET) {
                authenticated.add(conn)
                sendTo(conn, "AUTH|OK")
                AudioBridge.bluetoothMode = true
                ConnectionStatus.setBluetooth()
                Log.d(TAG, "BT client authenticated")
            } else {
                sendTo(conn, "AUTH|FAIL")
                try { conn.socket.close() } catch (_: Exception) {}
            }
            return
        }

        if (conn !in authenticated) {
            try { conn.socket.close() } catch (_: Exception) {}
            return
        }

        ProtocolHandler.handle(message)
    }

    private fun sendTo(conn: Connection, message: String) {
        try {
            conn.out.write((message + "\n").toByteArray())
            conn.out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
        }
    }

    fun sendEvent(event: String) {
        val dead = mutableListOf<Connection>()
        authenticated.forEach { conn ->
            try {
                conn.out.write((event + "\n").toByteArray())
                conn.out.flush()
            } catch (e: Exception) {
                dead.add(conn)
            }
        }
        dead.forEach {
            connections.remove(it)
            authenticated.remove(it)
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        connections.forEach { try { it.socket.close() } catch (_: Exception) {} }
        connections.clear()
        authenticated.clear()
        serverSocket = null
        acceptThread = null
    }
}
