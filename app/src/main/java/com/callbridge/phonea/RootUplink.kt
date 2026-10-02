package com.callbridge.phonea

import android.util.Log
import java.io.OutputStream

/**
 * Private uplink via root: streams the client's voice straight into the modem's
 * "in-call music" channel, so the caller hears it and nothing plays out loud.
 *
 * msm8994: MultiMedia9 (pcm 0-35) feeds "Incall_Music_2 Audio Mixer", which mixes
 * into the voice TX path. We enable that mixer with tinymix and play into pcm 35
 * with the ROM's tinyplay, reading a WAV stream from stdin.
 */
object RootUplink {
    private const val TAG = "CallBridge-RootUplink"
    private const val CARD = 0
    private const val DEVICE = 35 // MultiMedia9
    private val CONTROLS = listOf(
        "Incall_Music_2 Audio Mixer MultiMedia9",
        "Incall_Music Audio Mixer MultiMedia9"
    )
    private const val OUT_RATE = 48000

    @Volatile private var proc: Process? = null
    @Volatile private var out: OutputStream? = null
    @Volatile var lastError: String = ""

    val active get() = out != null

    /** Blocking. Returns true if the stream is up and accepting audio. */
    @Synchronized
    fun start(): Boolean {
        stop()
        val tm = RootAudio.tinymixBin()
        if (tm == null) { lastError = "no tinymix"; return false }

        var ctlOk = false
        for (c in CONTROLS) {
            val r = RootAudio.su("$tm '$c' 1")
            Log.d(TAG, "set '$c' -> $r")
            if (r.isBlank() || !(r.contains("Invalid", true) || r.contains("rror", true) || r.contains("not found", true))) ctlOk = true
        }
        if (!ctlOk) { lastError = "incall mixer control missing"; return false }

        return try {
            val p = ProcessBuilder("su", "-c", "exec tinyplay /dev/stdin -D $CARD -d $DEVICE")
                .redirectErrorStream(true).start()
            val o = p.outputStream
            o.write(wavHeader()); o.flush()
            // Give tinyplay a moment to open the PCM; if it exits, it failed
            Thread.sleep(400)
            val alive = try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }
            if (!alive) {
                lastError = "tinyplay: " + p.inputStream.bufferedReader().readText().trim().take(120)
                Log.e(TAG, lastError)
                false
            } else {
                proc = p; out = o
                Thread { // drain tinyplay output so it never blocks
                    try { p.inputStream.bufferedReader().forEachLine { Log.d(TAG, "tinyplay: $it") } } catch (_: Exception) {}
                }.apply { isDaemon = true }.start()
                lastError = ""
                true
            }
        } catch (e: Exception) {
            lastError = "start: ${e.message}"; false
        }
    }

    /** Takes 16 kHz mono 16-bit PCM; writes it as 48 kHz stereo. Returns false if the stream broke. */
    fun write(pcm16kMono: ByteArray): Boolean {
        val o = out ?: return false
        val samples = pcm16kMono.size / 2
        val buf = ByteArray(samples * 3 * 2 * 2) // x3 rate, x2 channels, 2 bytes
        var j = 0
        var prev = lastSample
        for (i in 0 until samples) {
            val cur = ((pcm16kMono[2 * i + 1].toInt() shl 8) or (pcm16kMono[2 * i].toInt() and 0xFF)).toShort().toInt()
            // linear interpolation prev -> cur in 3 steps
            for (k in 1..3) {
                val v = prev + (cur - prev) * k / 3
                val lo = (v and 0xFF).toByte(); val hi = ((v shr 8) and 0xFF).toByte()
                buf[j++] = lo; buf[j++] = hi   // L
                buf[j++] = lo; buf[j++] = hi   // R
            }
            prev = cur
        }
        lastSample = prev
        return try { o.write(buf); true } catch (e: Exception) {
            lastError = "stream broke: ${e.message}"; out = null; false
        }
    }
    private var lastSample = 0

    @Synchronized
    fun stop() {
        try { out?.close() } catch (_: Exception) {}
        out = null
        proc?.destroy(); proc = null
        lastSample = 0
        val tm = RootAudio.tinymixBin() ?: return
        Thread { for (c in CONTROLS) RootAudio.su("$tm '$c' 0") }.start()
    }

    private fun wavHeader(): ByteArray {
        val ch = 2; val bits = 16
        val byteRate = OUT_RATE * ch * bits / 8
        val dataSize = 0x7FFF0000
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(dataSize + 36); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(ch.toShort())
        b.putInt(OUT_RATE); b.putInt(byteRate); b.putShort((ch * bits / 8).toShort()); b.putShort(bits.toShort())
        b.put("data".toByteArray()); b.putInt(dataSize)
        return b.array()
    }
}
