package com.flummox.bingecore

import com.flummox.bingecloud.BCLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// ── bingecore: in-flight scrape dedup ──
// Two callers asking for the same key share one job.
// First caller starts the work, second caller awaits the same result.
object SpeedBooster {

    private val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Any?>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> deduped(
        key: String,
        work: suspend () -> T
    ): T {
        // fast path: job already running — join it
        inFlight[key]?.let { existing ->
            BCLog.d("[Booster] joining in-flight: $key")
            return existing.await() as T
        }

        // start a new job
        val deferred = CompletableDeferred<Any?>()
        val prior = inFlight.putIfAbsent(key, deferred)
        if (prior != null) {
            // lost the race — another thread started it between our check and putIfAbsent
            BCLog.d("[Booster] joined (race): $key")
            return prior.await() as T
        }

        SCOPE.launch {
            try {
                val result = work()
                deferred.complete(result)
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
            } finally {
                inFlight.remove(key)
            }
        }

        BCLog.d("[Booster] started: $key")
        return deferred.await() as T
    }
}
