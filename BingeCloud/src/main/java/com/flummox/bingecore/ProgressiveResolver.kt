package com.flummox.bingecore

import com.flummox.bingecloud.BCLog
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

// ── bingecore: progressive resolver ──
// Feed it a list of items. It resolves them in parallel, emits each result the
// moment it's ready, and returns control to the caller as soon as EITHER:
//   • minBeforeReturn items have been emitted, OR
//   • softCapMs elapsed, OR
//   • all items finished
// Remaining coroutines keep running on a shared background scope, so late
// results still reach the picker if the CS3 build supports live updates.
//
// Usage:
//   ProgressiveResolver.run(
//     items = myItems,
//     concurrency = 50,
//     resolve = { item -> listOf(whateverYouWantEmitted) },
//     onEmit = { result -> callback.invoke(result) }
//   )
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
        val emitted = AtomicInteger(0)
        val startMs = System.currentTimeMillis()
        val sem = Semaphore(concurrency.coerceIn(1, 50))

        val job = SCOPE.launch {
            items.map { item ->
                async {
                    sem.withPermit {
                        try {
                            val results = resolve(item)
                            results.forEach { r ->
                                onEmit(r)
                                emitted.incrementAndGet()
                            }
                        } catch (e: Exception) {
                            onFail(item, e)
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
