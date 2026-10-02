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

    private fun tinymixBin(): String? {
        val path = su("command -v tinymix || ls /system/bin/tinymix /vendor/bin/tinymix /system/xbin/tinymix 2>/dev/null | head -1")
        return path.lineSequence().firstOrNull { it.startsWith("/") }
    }

    fun enableIncallMusicMixer() = Thread { setIncallMixer(1) }.start()
    fun disableIncallMusicMixer() = Thread { setIncallMixer(0) }.start()

    private fun setIncallMixer(v: Int) {
        val tm = tinymixBin() ?: run { Log.w(TAG, "tinymix not found"); return }
        for (ctl in INCALL_CONTROLS) {
            val r = su("$tm '$ctl' $v")
            Log.d(TAG, "set '$ctl'=$v -> $r")
        }
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
            "TM=\$(command -v tinymix || echo /system/bin/tinymix); \$TM 2>/dev/null | grep -i -E 'incall|voice_tx|voip|multimedia2 |voc_rec' | head -40")
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
