package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import java.net.URLEncoder

// ═══════════════════════════════════════════
// ── GogoAnime scraper ──
// Search ?s= → series page → episodes → episode page → player servers.
// Emits SUB + DUB mirrors from #w-servers.
// ═══════════════════════════════════════════

private const val GOGO_DOMAIN = "https://gogoanime.by"

private suspend fun gogoFindSeriesPage(query: String): String? {
    return try {
        val doc = app.get("$GOGO_DOMAIN/?s=${URLEncoder.encode(query, "UTF-8")}").document
        val cards = doc.select("article.bs .bsx > a[href]")
        if (cards.isEmpty()) { BCLog.d("Gogo: no search results"); return null }
        var best: String? = null
        var bestScore = 0
        for (a in cards) {
            val href = a.attr("href")
            val title = a.attr("title").ifBlank {
                a.selectFirst(".tt")?.text()?.trim() ?: ""
            }
            if (href.isEmpty() || title.isEmpty()) continue
            if (!href.contains("/series/")) continue
            if (!titleMatches(query, title)) continue
            var score = 1
            if (title.lowercase().startsWith(query.lowercase())) score += 2
            if (score > bestScore) { bestScore = score; best = href }
        }
        best?.let { if (it.startsWith("http")) it else "$GOGO_DOMAIN$it" }
    } catch (e: Exception) {
        BCLog.e("Gogo search failed: ${e.message}"); null
    }
}

private suspend fun gogoEpisodes(seriesUrl: String): List<Triple<Int, String, String>> {
    return try {
        val doc = app.get(seriesUrl).document
        val out = mutableListOf<Triple<Int, String, String>>()
        for (item in doc.select(".episodes-container .episode-item")) {
            val num = item.attr("data-episode-number").toIntOrNull() ?: continue
            val a = item.selectFirst("a[href]") ?: continue
            val href = a.attr("href")
            if (href.isEmpty()) continue
            val full = if (href.startsWith("http")) href else "$GOGO_DOMAIN$href"
            out.add(Triple(num, full, a.text().trim()))
        }
        out.sortedBy { it.first }
    } catch (e: Exception) {
        BCLog.e("Gogo episodes failed: ${e.message}"); emptyList()
    }
}

suspend fun gogoExtractRaw(q: StreamQuery): List<ScrapedMirror> {
    val seriesPage = gogoFindSeriesPage(q.title) ?: return emptyList()
    BCLog.d("Gogo: matched series $seriesPage")

    val eps = gogoEpisodes(seriesPage)
    if (eps.isEmpty()) { BCLog.d("Gogo: no episodes"); return emptyList() }
    BCLog.d("Gogo: ${eps.size} episodes")

    val target = if (q.episode > 0) eps.firstOrNull { it.first == q.episode }
    else eps.firstOrNull()
    if (target == null) { BCLog.d("Gogo: ep ${q.episode} not found"); return emptyList() }

    val epDoc = try { app.get(target.second).document } catch (e: Exception) {
        BCLog.e("Gogo ep fetch failed: ${e.message}"); return emptyList()
    }

    val mirrors = mutableListOf<ScrapedMirror>()
    for (typeBlock in epDoc.select("#w-servers .type")) {
        val typeRaw = typeBlock.attr("data-type").ifBlank { "sub" }
        val typeLabel = typeRaw.replaceFirstChar { it.uppercase() }
        for (li in typeBlock.select("li.player-type-link")) {
            val dataSrc = li.attr("data-src")
            if (dataSrc.isBlank()) continue
            val serverLabel = li.text().trim().ifBlank { "Server" }
            val full = when {
                dataSrc.startsWith("//") -> "https:$dataSrc"
                dataSrc.startsWith("/") -> GOGO_DOMAIN + dataSrc
                else -> dataSrc
            }
            val label = "Gogo $typeLabel $serverLabel"
            mirrors.add(ScrapedMirror("Auto", label, full, "GOGO"))
        }
    }

    if (mirrors.isEmpty()) {
        val iframe = epDoc.selectFirst("#player iframe")?.attr("src")
            ?: epDoc.selectFirst("div.player-embed iframe")?.attr("src")
            ?: run { BCLog.d("Gogo: no iframe on ep page"); return emptyList() }
        val full = when {
            iframe.startsWith("//") -> "https:$iframe"
            iframe.startsWith("/") -> GOGO_DOMAIN + iframe
            else -> iframe
        }
        mirrors.add(ScrapedMirror("Auto", "Gogo", full, "GOGO"))
    }

    BCLog.d("Gogo: ${mirrors.size} mirrors — ${mirrors.map { it.mirror }}")
    return mirrors
}
