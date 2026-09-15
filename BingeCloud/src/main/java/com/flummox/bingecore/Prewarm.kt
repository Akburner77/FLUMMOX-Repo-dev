package com.flummox.bingecore

import com.flummox.bingecloud.BCLog
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// ── bingecore: TLS + DNS prewarm ──
// Fires fire-and-forget HEAD requests to known hosts on plugin boot.
// By the time user actually opens a title, the TCP+TLS handshake is done.
// Saves ~300-500ms per host on the first real request.
object Prewarm {

    private val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val HOSTS = listOf(
        "https://raw.githubusercontent.com",
        "https://vegamovies.mq",
        "https://new4.moviesdrive.christmas",
        "https://new5.hdhub4u.cl",
        "https://api6.aoneroom.com",
        "https://apig.inmoviebox.com",
        "https://mdrive.lol",
        "https://hubcloud.ist",
        "https://gdflix.cfd"
    )

    fun fire() {
        SCOPE.launch {
            BCLog.d("[Prewarm] starting ${HOSTS.size} hosts")
            HOSTS.map { host ->
                SCOPE.launch {
                    try {
                        app.head(host, timeout = 5000L)
                    } catch (_: Exception) {
                        // silent — prewarm is best-effort
                    }
                }
            }
        }
    }
}
