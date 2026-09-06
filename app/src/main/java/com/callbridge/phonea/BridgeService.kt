package com.callbridge.phonea

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class BridgeService : Service() {

    private val TAG = "CallBridge-Service"
    private val CHANNEL_ID = "callbridge_channel"
    private val NOTIF_ID = 1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        SocketServer.start()
        Log.d(TAG, "BridgeService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If killed, restart automatically
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        SocketServer.stop()
        AudioBridge.stop()
        Log.d(TAG, "BridgeService stopped — will restart via START_STICKY")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CallBridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "CallBridge active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("CallBridge Active")
                .setContentText("Listening for calls on port 8765")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("CallBridge Active")
                .setContentText("Listening for calls on port 8765")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true)
                .build()
        }
    }
}
