package com.flummox.bingecore

import com.flummox.bingecloud.BCLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

// ── bingecore: progressive resolver with ordered emission ──
// Items resolve in parallel. Results are emitted in INPUT ORDER, not
// completion order — so the caller's sorted list stays sorted in the picker.
//
// Returns as soon as EITHER:
//   • minBeforeReturn items emitted, OR
//   • softCapMs elapsed, OR
//   • all items resolved
// Remaining work continues on a shared background scope.
object ProgressiveResolver {

    private val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun <T, R> run(
        items: List<T>,
        concurrency: Int,
        softCapMs: Long = 2500L,
        minBeforeReturn: Int = 3,
        logTag: String = "Progressive",
        resolve: suspend (T) -> List<R>,
        onEmit: (R) -> Unit,
        onFail: (T, Exception) -> Unit = { _, _ -> }
    ) {
        if (items.isEmpty()) return

        val emitted = AtomicInteger(0)
        val startMs = System.currentTimeMillis()
        val sem = Semaphore(concurrency.coerceIn(1, 50))

        // ordered emission state
        val buckets = arrayOfNulls<MutableList<R>>(items.size)   // null = not done yet
        val nextToEmit = intArrayOf(0)
        val emitLock = Any()

        val job = SCOPE.launch {
            items.mapIndexed { index, item ->
                async {
                    sem.withPermit {
                        val resolved: List<R> = try {
                            resolve(item)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onFail(item, e)
                            emptyList()
                        }
                        // ── store result, then flush any consecutive completed items in order ──
                        synchronized(emitLock) {
                            buckets[index] = resolved.toMutableList()
                            while (nextToEmit[0] < items.size) {
                                val bucket = buckets[nextToEmit[0]] ?: break
                                bucket.forEach { r ->
                                    onEmit(r)
                                    emitted.incrementAndGet()
                                }
                                nextToEmit[0]++
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        while (emitted.get() < minBeforeReturn
            && job.isActive
            && System.currentTimeMillis() - startMs < softCapMs) {
            delay(80)
        }

        BCLog.d("[$logTag] resolver returned — emitted=${emitted.get()} elapsed=${System.currentTimeMillis() - startMs}ms stillRunning=${job.isActive}")
    }
}
