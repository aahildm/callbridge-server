package com.callbridge.phonea

import android.app.Activity
import android.content.Intent

/** Force-closes and relaunches the app cleanly for a fresh connection state. */
object RestartHelper {

    fun restartApp(activity: Activity) {
        val packageManager = activity.packageManager
        val intent = packageManager.getLaunchIntentForPackage(activity.packageName)
        val mainIntent = Intent.makeRestartActivityTask(intent?.component)
        activity.startActivity(mainIntent)
        Runtime.getRuntime().exit(0)
    }
}
