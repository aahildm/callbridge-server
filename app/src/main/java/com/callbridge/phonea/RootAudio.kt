package com.callbridge.phonea

import android.util.Base64
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Root-only helpers for forcing the Qualcomm "in-call music" uplink path, which lets
 * app audio be mixed straight into the call's transmit side without any speaker.
 *
 * The exact mixer control names vary by chipset, so probe() collects what this phone has.
 */
object RootAudio {
    private const val TAG = "CallBridge-RootAudio"

    // Known Qualcomm control names for the incall-music uplink mixer (msm8994 / msm8996 / later)
    private val INCALL_CONTROLS = listOf(
        "Incall_Music Audio Mixer MultiMedia2",
        "Incall_Music_2 Audio Mixer MultiMedia9",
        "Incall_Music Audio Mixer MultiMedia1"
    )

    fun su(cmd: String, timeoutSec: Long = 8): String {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(timeoutSec, TimeUnit.SECONDS)
            out.trim()
        } catch (e: Exception) {
            "ERR: ${e.message}"
        }
    }

    @Volatile private var cachedTinymix: String? = null
    private var bundledTinymix: String? = null

    /** Unpack the tinymix binary bundled in the APK (built in CI) so root can run it. */
    fun init(context: android.content.Context) {
        try {
            val f = java.io.File(context.filesDir, "tinymix")
            context.assets.open("tinymix").use { inp -> f.outputStream().use { inp.copyTo(it) } }
            f.setExecutable(true, false)
            bundledTinymix = f.absolutePath
        } catch (e: Exception) { Log.w(TAG, "No bundled tinymix: ${e.message}") }
    }

    fun tinymixBin(): String? {
        cachedTinymix?.let { return it }
        bundledTinymix?.let { cachedTinymix = it; return it }
        val path = su("command -v tinymix || ls /system/bin/tinymix /vendor/bin/tinymix /system/xbin/tinymix 2>/dev/null | head -1")
        return path.lineSequence().firstOrNull { it.startsWith("/") }?.also { cachedTinymix = it }
    }

    fun disableIncallMusicMixer() = Thread { setIncallMixer(0) }.start()

    /** Blocking. Returns true if at least one known incall-music control was set successfully. */
    @Synchronized
    fun setIncallMixer(v: Int): Boolean {
        val tm = tinymixBin() ?: run { Log.w(TAG, "tinymix not found"); return false }
        var ok = false
        for (ctl in INCALL_CONTROLS) {
            val r = su("$tm '$ctl' $v")
            Log.d(TAG, "set '$ctl'=$v -> $r")
            if (!r.contains("Invalid", true) && !r.contains("ERR", true) && !r.contains("not found", true)
                && !r.contains("Failed", true)) ok = true
        }
        return ok
    }

    /** Collect audio-hardware info and send it to the client (which copies it to clipboard). */
    fun probeAndSend() {
        val sb = StringBuilder()
        fun section(title: String, cmd: String) {
            sb.append("=== ").append(title).append(" ===\n").append(su(cmd)).append("\n\n")
        }
        section("root", "id")
        section("platform", "getprop ro.board.platform; getprop ro.build.version.release; getprop ro.build.display.id")
        section("tinymix bin", "command -v tinymix tinyplay tinypcminfo; ls /system/bin/tiny* /vendor/bin/tiny* 2>/dev/null")
        section("pcm devices", "cat /proc/asound/pcm")
        section("incall/voice mixer ctls",
            "${tinymixBin() ?: "tinymix"} 2>&1 | grep -i -E 'incall|voice|farend|multimedia9|multimedia2 ' | head -60")
        section("mixer_paths incall",
            "for f in /vendor/etc/mixer_paths*.xml /system/etc/mixer_paths*.xml /system/vendor/etc/mixer_paths*.xml; do [ -f \$f ] && echo \"# \$f\" && grep -i -A4 'incall-music' \$f | head -30; done")
        section("policy incall",
            "for f in /vendor/etc/audio_policy_configuration.xml /system/etc/audio_policy_configuration.xml /vendor/etc/audio_policy.conf /system/etc/audio_policy.conf; do [ -f \$f ] && echo \"# \$f\" && grep -i -B2 -A6 'incall' \$f | head -40; done")
        section("audio props", "getprop | grep -i -E 'incall|voice|audio' | head -30")

        val text = sb.toString().take(12000)
        val b64 = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        BluetoothServer.sendEvent("AUDIOPROBE|$b64")
    }
}
