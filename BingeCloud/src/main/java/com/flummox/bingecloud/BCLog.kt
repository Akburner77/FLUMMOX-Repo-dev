package com.flummox.bingecloud

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer logger for BingeCloud.
 * Keeps the last 800 lines, thread-safe, shown in Settings → Debug Logs.
 */
object BCLog {

    private const val MAX_LINES = 800
    private const val TAG = "BingeCloud"

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

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

    /** Returns the entire log, newest last. */
    fun all(): String = synchronized(lock) {
        if (buffer.isEmpty()) "(no logs yet)"
        else buffer.joinToString("\n")
    }

    fun count(): Int = synchronized(lock) { buffer.size }

    fun clear() = synchronized(lock) { buffer.clear() }
}
