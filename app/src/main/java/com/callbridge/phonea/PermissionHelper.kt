package com.callbridge.phonea

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

object PermissionHelper {

    fun isBatteryOptimized(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestBatteryExemption(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            try {
                activity.startActivity(intent)
            } catch (e: Exception) {
                openAppSettings(activity)
            }
        }
    }

    fun openAppSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        activity.startActivity(intent)
    }

    fun showBatteryDialog(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("Keep CallBridge Running")
            .setMessage(
                "Battery optimization will kill CallBridge in the background, " +
                "causing calls to not be intercepted.\n\n" +
                "Tap 'Allow' on the next screen to exempt CallBridge from battery optimization."
            )
            .setPositiveButton("Fix Now") { _, _ -> requestBatteryExemption(activity) }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    fun isMiui(): Boolean {
        return try {
            !System.getProperty("ro.miui.ui.version.name").isNullOrEmpty()
        } catch (e: Exception) { false }
    }

    fun showLineageOsGuide(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("LineageOS Setup Guide")
            .setMessage(
                "On LineageOS you may also need to:\n\n" +
                "1. Settings → Battery → Battery optimization\n" +
                "   → Find CallBridge Server → Don't optimize\n\n" +
                "2. Settings → Developer options\n" +
                "   → Don't keep activities → OFF\n\n" +
                "These prevent LineageOS from killing the bridge service."
            )
            .setPositiveButton("Open App Settings") { _, _ -> openAppSettings(activity) }
            .setNegativeButton("Got it", null)
            .show()
    }
}
