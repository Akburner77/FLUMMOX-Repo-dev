package com.flummox.bingecloud

import com.lagradost.cloudstream3.app

// ── GogoAnime scraper ──
// Only runs when q.type contains "anime".
// search → anime page → episode list → target episode page → iframe src → ScrapedMirror

private const val GOGO_DOMAIN = "https://gogoanime.by"

private suspend fun gogoFindAnimePage(query: String): String? {
    return try {
        val doc = app.get("$GOGO_DOMAIN/search.html", params = mapOf("keyword" to query)).document
        val cards = doc.select("ul.items li")
        if (cards.isEmpty()) { BCLog.d("Gogo: no search results"); return null }
        var best: String? = null
        var bestScore = 0
        for (card in cards) {
            val a = card.selectFirst("p.name a") ?: continue
            val title = a.attr("title").ifBlank { a.text() }.trim()
            val href = a.attr("href").trim()
            if (href.isEmpty()) continue
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

private suspend fun gogoEpisodes(animeUrl: String): List<Pair<Int, String>> {
    return try {
        val doc = app.get(animeUrl).document
        val animeId = doc.selectFirst("input#movie_id")?.attr("value")
            ?: doc.selectFirst("input[name=id]")?.attr("value")
            ?: run { BCLog.d("Gogo: no anime id"); return emptyList() }
        val activeTab = doc.selectFirst("#episode_page li a.active")
        val epStart = activeTab?.attr("ep_start") ?: "0"
        val epEnd = activeTab?.attr("ep_end") ?: "0"
        val epDoc = app.get(
            "$GOGO_DOMAIN/ajax/load-list-episode",
            params = mapOf("ep_start" to epStart, "ep_end" to epEnd, "id" to animeId)
        ).document
        val out = mutableListOf<Pair<Int, String>>()
        for (a in epDoc.select("li a")) {
            val href = a.attr("href").trim()
            if (href.isEmpty()) continue
            val epNum = a.selectFirst("div.play span")?.text()
                ?.removePrefix("Episode")?.trim()?.toIntOrNull() ?: continue
            val full = if (href.startsWith("http")) href else "$GOGO_DOMAIN$href"
            out.add(epNum to full)
        }
        out
    } catch (e: Exception) {
        BCLog.e("Gogo episodes failed: ${e.message}"); emptyList()
    }
}

suspend fun gogoExtractRaw(q: StreamQuery): List<ScrapedMirror> {
    if (!q.type.contains("anime", true)) return emptyList()
    val animePage = gogoFindAnimePage(q.title) ?: return emptyList()
    BCLog.d("Gogo: matched $animePage")

    val eps = gogoEpisodes(animePage)
    if (eps.isEmpty()) { BCLog.d("Gogo: no episodes"); return emptyList() }

    val target = if (q.episode > 0) eps.firstOrNull { it.first == q.episode }
    else eps.firstOrNull()
    if (target == null) { BCLog.d("Gogo: ep ${q.episode} not found"); return emptyList() }

    val epDoc = try { app.get(target.second).document } catch (e: Exception) {
        BCLog.e("Gogo ep fetch failed: ${e.message}"); return emptyList()
    }
    val iframe = epDoc.selectFirst("div.play-video iframe")?.attr("src")
        ?: epDoc.selectFirst("iframe")?.attr("src")
        ?: run { BCLog.d("Gogo: no iframe on ep page"); return emptyList() }

    val full = when {
        iframe.startsWith("//") -> "https:$iframe"
        iframe.startsWith("/") -> GOGO_DOMAIN + iframe
        else -> iframe
    }
    BCLog.d("Gogo: iframe $full")
    return listOf(ScrapedMirror("Auto", "Gogo", full, "GOGO"))
}
