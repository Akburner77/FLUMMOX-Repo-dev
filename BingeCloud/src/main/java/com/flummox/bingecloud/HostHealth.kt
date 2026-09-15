package com.flummox.bingecloud

import android.content.Context
import java.io.File
import java.util.Timer
import java.util.TimerTask

// ── bingecore: persistent per-host reputation ──
// Records success/failure per host, provides bonus/penalty to LinkScore.
// Batch-writes to disk every 30s. Flushed on process exit via timer.
object HostHealth {

    private const val FILE_NAME = "host_health.tsv"
    private const val FLUSH_INTERVAL_MS = 30_000L
    private const val SUCCESS_WINDOW_MS = 5 * 60_000L

    private data class Health(
        var lastSuccess: Long = 0,
        var lastFail: Long = 0,
        var successCount: Int = 0,
        var failStreak: Int = 0
    )

    private val map = mutableMapOf<String, Health>()
    private val lock = Any()
    private var dirty = false
    private var file: File? = null
    private var timer: Timer? = null

    // ── lifecycle ──
    fun init(context: Context) {
        synchronized(lock) {
            file = File(context.filesDir, FILE_NAME)
            loadFromDisk()
        }
        timer?.cancel()
        timer = Timer("HostHealthFlush", true).apply {
            schedule(object : TimerTask() {
                override fun run() { flushIfDirty() }
            }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS)
        }
    }

    // ── read ──
    fun bonus(host: String): Int = synchronized(lock) {
        val h = map[host.lowercase()] ?: return 0
        val now = System.currentTimeMillis()
        var s = 0
        if (now - h.lastSuccess < SUCCESS_WINDOW_MS) s += 10
        if (h.failStreak >= 3) s -= 20
        s
    }

    // ── write ──
    fun recordSuccess(host: String) = synchronized(lock) {
        val h = map.getOrPut(host.lowercase()) { Health() }
        h.lastSuccess = System.currentTimeMillis()
        h.successCount++
        h.failStreak = 0
        dirty = true
    }

    fun recordFailure(host: String) = synchronized(lock) {
        val h = map.getOrPut(host.lowercase()) { Health() }
        h.lastFail = System.currentTimeMillis()
        h.failStreak++
        dirty = true
    }

    // ── persistence ──
    fun flushIfDirty() {
        synchronized(lock) {
            if (!dirty || file == null) return
            try {
                val sb = StringBuilder()
                for ((k, v) in map) {
                    sb.append(k).append('\t')
                        .append(v.lastSuccess).append('\t')
                        .append(v.lastFail).append('\t')
                        .append(v.successCount).append('\t')
                        .append(v.failStreak).append('\n')
                }
                file!!.writeText(sb.toString())
                dirty = false
            } catch (_: Exception) {}
        }
    }

    private fun loadFromDisk() {
        try {
            val f = file ?: return
            if (!f.exists()) return
            f.forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 5) {
                    map[p[0]] = Health(
                        lastSuccess = p[1].toLongOrNull() ?: 0,
                        lastFail = p[2].toLongOrNull() ?: 0,
                        successCount = p[3].toIntOrNull() ?: 0,
                        failStreak = p[4].toIntOrNull() ?: 0
                    )
                }
            }
        } catch (_: Exception) {}
    }
}
