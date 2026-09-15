package com.flummox.bingecore

import com.flummox.bingecloud.BCLog
import com.flummox.bingecloud.BCCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

// ── bingecore: global speed booster ──
// 1. In-flight dedup: two callers requesting the same scrape share one job.
// 2. Provider circuit breaker: skip providers that keep timing out.
// 3. amap wrapper: parallel extraction with built-in CS3 semantics.
object SpeedBooster {

    // ── 1. in-flight scrape dedup ──
    private val inFlight = ConcurrentHashMap<String, Deferred<Any?>>()
    private val lock = Mutex()

    suspend fun <T> dedupedScrape(
        key: String,
        ttlMs: Long = 30 * 60 * 1000L,
        scrape: suspend () -> T
    ): T {
        // check disk cache first
        @Suppress("UNCHECKED_CAST")
        BCCache.get("scrape_result:$key", ttlMs)?.let { cached ->
            BCLog.d("[Booster] cache hit: $key")
            return cached as T
        }

        // check in-flight — join the same job instead of starting a new one
        lock.withLock {
            inFlight[key]?.let { existing ->
                BCLog.d("[Booster] joining in-flight: $key")
                @Suppress("UNCHECKED_CAST")
                return existing.await() as T
            }
        }

        // start new job
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.async {
            try {
                val result = scrape()
                // store result as string for disk cache
                @Suppress("UNCHECKED_CAST")
                BCCache.put("scrape_result:$key", result.toString())
                result
            } finally {
                inFlight.remove(key)
            }
        }
        inFlight[key] = job
        return job.await()
    }

    // ── 2. provider circuit breaker ──
    private val failCounts = ConcurrentHashMap<String, Int>()
    private val skipUntil = ConcurrentHashMap<String, Long>()

    fun shouldSkipProvider(name: String): Boolean {
        val until = skipUntil[name] ?: 0L
        if (System.currentTimeMillis() < until) {
            BCLog.d("[Booster] skipping $name (circuit open)")
            return true
        }
        return false
    }

    fun recordProviderResult(name: String, success: Boolean) {
        if (success) {
            failCounts.remove(name)
            skipUntil.remove(name)
        } else {
            val count = failCounts.merge(name, 1, Int::plus) ?: 1
            if (count >= 3) {
                skipUntil[name] = System.currentTimeMillis() + 5 * 60 * 1000L
                BCLog.d("[Booster] circuit open for $name (3 fails, 5min cooloff)")
            }
        }
    }

    // ── 3. amap wrapper (thin, uses CS3's built-in) ──
    suspend fun <T, R> amap(
        items: List<T>,
        concurrency: Int = 50,
        transform: suspend (T) -> R
    ): List<R> {
        val sem = kotlinx.coroutines.sync.Semaphore(concurrency)
        return CoroutineScope(SupervisorJob() + Dispatchers.IO).let { scope ->
            items.map { item ->
                scope.async {
                    sem.withPermit { transform(item) }
                }
            }.awaitAll()
        }
    }
}
