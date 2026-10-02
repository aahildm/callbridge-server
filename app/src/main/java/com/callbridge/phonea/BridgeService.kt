package com.callbridge.phonea

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.media.AudioManager
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
        SmsSender.appContext = applicationContext
        DialHelper.appContext = applicationContext
        CallLogHelper.appContext = applicationContext
        ContactHelper.appContext = applicationContext
        ContactsSender.appContext = applicationContext
        BluetoothServer.start()
        ConnectionStatus.onChange = { transport ->
            val text = when (transport) {
                "BLUETOOTH" -> "${remoteDeviceName()} connected"
                "WIFI"      -> "${remoteDeviceName()} connected via Wi-Fi"
                else        -> "${localName()} — waiting for connection"
            }
            updateNotification(text)
        }
        Log.d(TAG, "BridgeService started (Bluetooth-only mode)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ConnectionStatus.onChange = null
        BluetoothServer.stop()
        AudioBridge.stop()
        Log.d(TAG, "BridgeService stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "CallBridge", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "CallBridge active"; setShowBadge(false) }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("CallBridge Active")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("CallBridge Active")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true).build()
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notif)
    }

    @SuppressLint("MissingPermission")
    private fun localName(): String =
        try { BluetoothAdapter.getDefaultAdapter()?.name ?: "CallBridge Server" }
        catch (e: Exception) { "CallBridge Server" }

    private fun remoteDeviceName(): String =
        BluetoothServer.connectedDeviceName ?: "Phone B"

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("CallBridge Active")
                .setContentText("${localName()} — waiting for connection")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("CallBridge Active")
                .setContentText("${localName()} — waiting for connection")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true).build()
        }
    }
}
