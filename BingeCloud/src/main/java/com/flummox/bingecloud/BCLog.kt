package com.flummox.bingecloud

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer logger for BingeCloud.
 * Keeps the last 800 lines, thread-safe, shown in Settings → Debug Logs.
 * Tokens, cookies, JWTs are stripped by allSanitized() before display/share/save.
 */
object BCLog {

    private const val MAX_LINES = 800
    private const val TAG = "BingeCloud"

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val RX_JWT = Regex("""eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+""")
    private val RX_BEARER = Regex("""(?i)(bearer\s+)\S+""")
    private val RX_SIGN_COOKIE = Regex("""(?i)("signCookie"\s*:\s*")[^"]+""")
    private val RX_COOKIE = Regex("""(?i)(cookie["']?\s*[:=]\s*["']?)[^"\r\n]+""")
    private val RX_CF = Regex("""(?i)(cloudfront-(?:policy|signature|key-pair-id)=)[^;&"\s]+""")

    private fun sanitize(input: String): String {
        var s = input
        s = RX_JWT.replace(s) { "<JWT>" }
        s = RX_BEARER.replace(s) { m -> m.groupValues[1] + "<BEARER>" }
        s = RX_SIGN_COOKIE.replace(s) { m -> m.groupValues[1] + "<COOKIE>" }
        s = RX_COOKIE.replace(s) { m -> m.groupValues[1] + "<COOKIE>" }
        s = RX_CF.replace(s) { m -> m.groupValues[1] + "<CF>" }
        return s
    }

    fun d(message: String) {
        val line = "[${timeFormat.format(Date())}] $message"
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        Log.d(TAG, message)
    }

    fun e(message: String) {
        val line = "[${timeFormat.format(Date())}] ✗ $message"
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        Log.e(TAG, message)
    }

    fun section(title: String) {
        val line = "───── $title ─────"
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        Log.d(TAG, line)
    }

    /** Raw — internal use only. */
    fun all(): String = synchronized(lock) {
        if (buffer.isEmpty()) "(no logs yet)"
        else buffer.joinToString("\n")
    }

    /** Sanitized — safe for display, copy, share, save. */
    fun allSanitized(): String = sanitize(all())

    fun count(): Int = synchronized(lock) { buffer.size }

    fun clear() = synchronized(lock) { buffer.clear() }
}
