package com.callbridge.phonea

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

object SocketServer {

    private val TAG = "CallBridge-SocketServer"
    private const val PORT = 8765

    private var server: WebSocketServer? = null
    private val clients = mutableSetOf<WebSocket>()

    fun start() {
        if (server != null) return

        server = object : WebSocketServer(InetSocketAddress(PORT)) {

            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                Log.d(TAG, "Client connected: ${conn.remoteSocketAddress}")
                // Store client IP so AudioBridge can send UDP audio back
                AudioBridge.phoneBIp = conn.remoteSocketAddress?.address?.hostAddress
                Log.d(TAG, "Phone B IP captured: ${AudioBridge.phoneBIp}")
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                clients.remove(conn)
                Log.d(TAG, "Client disconnected")
            }

            override fun onMessage(conn: WebSocket, message: String) {
                Log.d(TAG, "Received: $message")

                if (message.startsWith("AUTH|")) {
                    val token = message.removePrefix("AUTH|")
                    if (token == ProtocolHandler.SECRET) {
                        clients.add(conn)
                        conn.send("AUTH|OK")
                        Log.d(TAG, "Client authenticated")
                    } else {
                        conn.send("AUTH|FAIL")
                        conn.close()
                    }
                    return
                }

                if (conn !in clients) {
                    conn.close()
                    return
                }

                ProtocolHandler.handle(message)
            }

            override fun onError(conn: WebSocket?, ex: Exception) {
                Log.e(TAG, "WebSocket error: ${ex.message}")
            }

            override fun onStart() {
                Log.d(TAG, "WebSocket server started on port $PORT")
            }
        }

        server?.start()
    }

    fun stop() {
        try {
            server?.stop()
            server = null
            clients.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}")
        }
    }

    fun sendEvent(event: String) {
        Log.d(TAG, "Broadcasting: $event")
        val deadClients = mutableSetOf<WebSocket>()
        clients.forEach { client ->
            try {
                if (client.isOpen) {
                    client.send(event)
                } else {
                    deadClients.add(client)
                }
            } catch (e: Exception) {
                deadClients.add(client)
            }
        }
        clients.removeAll(deadClients)
    }
}
