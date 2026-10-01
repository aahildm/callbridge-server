package com.callbridge.phonea

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQUEST_DEFAULT_DIALER = 1001
    private val REQUEST_PERMISSIONS = 1002

    private val REQUIRED_PERMISSIONS = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private var batteryDialogShown = false
    private lateinit var tvDialerStatus: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvBtListenStatus: TextView
    private lateinit var btnBattery: Button
    private val handler = Handler(Looper.getMainLooper())

    private val statusPoller = object : Runnable {
        override fun run() {
            updateConnectionStatus()
            updateBtListenStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDialerStatus     = findViewById(R.id.tvStatus)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvBtListenStatus   = findViewById(R.id.tvBtListenStatus)
        val btnSetDialer   = findViewById<Button>(R.id.btnSetDialer)
        val btnStartService = findViewById<Button>(R.id.btnStartService)
        val btnRestartApp  = findViewById<Button>(R.id.btnRestartApp)
        btnBattery         = findViewById(R.id.btnBatteryFix)

        btnSetDialer.setOnClickListener { promptSetDefaultDialer() }

        btnStartService.setOnClickListener {
            val serviceIntent = Intent(this, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            tvDialerStatus.text = "✅ Service started — ready for Phone B"
        }

        btnBattery.setOnClickListener {
            if (PermissionHelper.isBatteryOptimized(this)) {
                PermissionHelper.showBatteryDialog(this)
            } else {
                PermissionHelper.showLineageOsGuide(this)
            }
        }

        btnRestartApp.setOnClickListener {
            Toast.makeText(this, "Restarting...", Toast.LENGTH_SHORT).show()
            RestartHelper.restartApp(this)
        }

        requestMissingPermissions()
        updateDialerStatus()
        updateBatteryButton()
        updateConnectionStatus()
        updateBtListenStatus()
    }

    override fun onResume() {
        super.onResume()
        updateDialerStatus()
        updateBatteryButton()
        updateConnectionStatus()
        updateBtListenStatus()
        handler.postDelayed(statusPoller, 2000)
        if (!batteryDialogShown && PermissionHelper.isBatteryOptimized(this)) {
            batteryDialogShown = true
            PermissionHelper.showBatteryDialog(this)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusPoller)
    }

    private fun updateConnectionStatus() {
        tvConnectionStatus.text = when (ConnectionStatus.transport) {
            "BLUETOOTH" -> "✅ Phone B connected via Bluetooth"
            else -> "⚠️ No device connected"
        }
    }

    private fun updateBtListenStatus() {
        tvBtListenStatus.text = when (BluetoothServer.listenState) {
            "LISTENING" -> "🔵 Bluetooth: listening for connections"
            "BT_DISABLED" -> "⚠️ Bluetooth is OFF — turn it on"
            "NO_ADAPTER" -> "❌ Bluetooth not available on this device"
            "FAILED" -> "❌ Bluetooth failed to start"
            else -> "🔵 Bluetooth: not started yet"
        }
    }

    private fun updateBatteryButton() {
        btnBattery.text = if (PermissionHelper.isBatteryOptimized(this)) {
            "⚠️ Fix Battery Optimization"
        } else {
            "✅ Battery Optimization OK"
        }
    }

    private fun updateDialerStatus() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            tvDialerStatus.text = "⚠️ Grant permissions to continue"
            return
        }
        val telecomManager = getSystemService(TelecomManager::class.java)
        tvDialerStatus.text = if (telecomManager.defaultDialerPackage == packageName) {
            "✅ Set as default dialer"
        } else {
            "⚠️ Not set as default dialer — tap below"
        }
    }

    private fun requestMissingPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) updateDialerStatus()
    }

    private fun promptSetDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                startActivityForResult(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER),
                    REQUEST_DEFAULT_DIALER
                )
            }
        } else {
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            startActivityForResult(intent, REQUEST_DEFAULT_DIALER)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DEFAULT_DIALER) updateDialerStatus()
    }
}
