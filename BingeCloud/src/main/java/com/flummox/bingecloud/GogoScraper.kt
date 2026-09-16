package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import java.net.URLEncoder

// ═══════════════════════════════════════════
// ── GogoAnime scraper ──
// Search ?s= → top candidates → try each until episode found.
// ═══════════════════════════════════════════

private const val GOGO_DOMAIN = "https://gogoanime.by"

private data class GogoCandidate(val url: String, val title: String, val score: Int)

// ── score: exact > prefix > word overlap. Short queries require strong match. ──
private fun gogoScore(query: String, candidate: String): Int {
    val q = query.lowercase().trim()
    val c = candidate.lowercase().trim()
    if (q.isEmpty() || c.isEmpty()) return 0
    if (q == c) return 100
    if (c.startsWith(q)) return 30
    if (q.startsWith(c)) return 25

    val qWords = q.split(Regex("\\s+")).filter { it.isNotBlank() }
    val cWords = c.split(Regex("\\s+")).filter { it.isNotBlank() }

    // ── short query guard: 1-2 word queries must match on prefix only ──
    if (qWords.size <= 2) {
        // require the query words to appear in order at start
        val joined = cWords.take(qWords.size).joinToString(" ")
        return if (joined == q) 20 else 0
    }

    val common = qWords.intersect(cWords.toSet()).size
    if (common == 0) return 0
    val ratio = common.toFloat() / maxOf(qWords.size, cWords.size)
    return (ratio * 20).toInt()
}

private suspend fun gogoSearchCandidates(query: String): List<GogoCandidate> {
    val attempts = listOf(
        query,                                        // as-is
        query.replace(Regex("""[:!?.,']"""), ""),    // strip punctuation
    ).distinct()

    for (q in attempts) {
        val results = try {
            val doc = app.get("$GOGO_DOMAIN/?s=${URLEncoder.encode(q, "UTF-8")}").document
            val cards = doc.select("article.bs .bsx > a[href]")
            if (cards.isEmpty()) { BCLog.d("Gogo search '$q': 0 cards"); continue }
            BCLog.d("Gogo search '$q': ${cards.size} cards")

            val out = mutableListOf<GogoCandidate>()
            for (a in cards) {
                val href = a.attr("href")
                if (href.isEmpty() || !href.contains("/series/")) continue
                val title = a.attr("title").ifBlank {
                    a.selectFirst(".tt")?.text()?.trim() ?: ""
                }.trim()
                if (title.isEmpty()) continue
                val score = gogoScore(query, title)
                if (score > 0) {
                    val full = if (href.startsWith("http")) href else "$GOGO_DOMAIN$href"
                    out.add(GogoCandidate(full, title, score))
                }
            }
            if (out.isEmpty()) {
                BCLog.d("Gogo search '$q': 0 matches after scoring")
                continue
            }
            val sorted = out.sortedByDescending { it.score }
            BCLog.d("Gogo search '$q' top: ${sorted.take(3).map { "${it.score}:${it.title}" }}")
            return sorted.take(5)
        } catch (e: Exception) {
            BCLog.e("Gogo search '$q' failed: ${e.message}")
            continue
        }
    }
    return emptyList()
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
    val candidates = gogoSearchCandidates(q.title)
    if (candidates.isEmpty()) { BCLog.d("Gogo: no candidates"); return emptyList() }

    // ── try each candidate, first one with matching episode wins ──
    for (cand in candidates) {
        BCLog.d("Gogo: trying ${cand.title} (score=${cand.score})")
        val eps = gogoEpisodes(cand.url)
        if (eps.isEmpty()) {
            BCLog.d("Gogo: no episodes on ${cand.url}")
            continue
        }

        val target = if (q.episode > 0) eps.firstOrNull { it.first == q.episode }
        else eps.firstOrNull()
        if (target == null) {
            BCLog.d("Gogo: ep ${q.episode} not on ${cand.title} (${eps.size} eps)")
            continue
        }

        BCLog.d("Gogo: matched ${cand.title} · ep ${target.first}")

        val epDoc = try { app.get(target.second).document } catch (e: Exception) {
            BCLog.e("Gogo ep fetch failed: ${e.message}"); continue
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
                mirrors.add(ScrapedMirror("Auto", "Gogo $typeLabel $serverLabel", full, "GOGO"))
            }
        }

        if (mirrors.isEmpty()) {
            val iframe = epDoc.selectFirst("#player iframe")?.attr("src")
                ?: epDoc.selectFirst("div.player-embed iframe")?.attr("src")
            if (!iframe.isNullOrBlank()) {
                val full = when {
                    iframe.startsWith("//") -> "https:$iframe"
                    iframe.startsWith("/") -> GOGO_DOMAIN + iframe
                    else -> iframe
                }
                mirrors.add(ScrapedMirror("Auto", "Gogo", full, "GOGO"))
            }
        }

        if (mirrors.isNotEmpty()) {
            BCLog.d("Gogo: ${mirrors.size} mirrors — ${mirrors.map { it.mirror }}")
            return mirrors
        }
    }

    BCLog.d("Gogo: all candidates exhausted")
    return emptyList()
}
