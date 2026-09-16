package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

// ═══════════════════════════════════════════
// ── GogoAnime scraper ──
// Primary: WP REST /wp-json/wp/v2/search?search=<romaji>
// Aliases: AniList GraphQL (English → romaji/synonyms)
// Fallback: slug probe
// ═══════════════════════════════════════════

private const val GOGO_DOMAIN = "https://gogoanime.by"
private const val ANILIST_URL = "https://graphql.anilist.co"
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

private data class GogoCandidate(val url: String, val title: String, val score: Int)

// ═══════════════════════════════════════════
// ── SCORING ──
// ═══════════════════════════════════════════
private fun gogoScore(query: String, candidate: String): Int {
    val q = query.lowercase().trim()
    val c = candidate.lowercase().trim()
    if (q.isEmpty() || c.isEmpty()) return 0
    if (q == c) return 100
    if (c.startsWith(q)) return 30
    if (q.startsWith(c)) return 25

    val qWords = q.split(Regex("\\s+")).filter { it.isNotBlank() }
    val cWords = c.split(Regex("\\s+")).filter { it.isNotBlank() }

    if (qWords.size <= 2) {
        val joined = cWords.take(qWords.size).joinToString(" ")
        return if (joined == q) 20 else 0
    }

    val common = qWords.intersect(cWords.toSet()).size
    if (common == 0) return 0
    val ratio = common.toFloat() / maxOf(qWords.size, cWords.size)
    return (ratio * 20).toInt()
}

// ═══════════════════════════════════════════
// ── ANILIST: English → romaji/synonym aliases ──
// ═══════════════════════════════════════════
private suspend fun anilistAltTitles(query: String): List<String> {
    val cacheKey = "anilist:$query"
    BCCache.get(cacheKey, 24 * 60 * 60 * 1000L)?.let { cached ->
        return cached.split("|").filter { it.isNotBlank() }
    }

    return try {
    val gqlQuery = "query(" + '$' + "s: String){" +
        "Media(search: " + '$' + "s, type: ANIME){" +
        "title{romaji english native} synonyms}}"
    val bodyJson = JSONObject().apply {
        put("query", gqlQuery)
        put("variables", JSONObject().apply { put("s", query) })
    }.toString()

    val res = app.post(
        ANILIST_URL,
        requestBody = bodyJson.toRequestBody(JSON_MEDIA),
        headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )
    )
        if (res.code !in 200..299) {
            BCLog.d("AniList ${res.code} for '$query'")
            return emptyList()
        }

        val media = JSONObject(res.text)
            .optJSONObject("data")?.optJSONObject("Media")
        if (media == null) {
            BCCache.put(cacheKey, "")
            return emptyList()
        }

        val titles = linkedSetOf<String>()
        media.optJSONObject("title")?.let { t ->
            listOf("romaji", "english", "native").forEach { k ->
                t.optString(k).takeIf { it.isNotBlank() }?.let { titles.add(it) }
            }
        }
        media.optJSONArray("synonyms")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { titles.add(it) }
            }
        }

        val list = titles.toList()
        BCCache.put(cacheKey, list.joinToString("|"))
        BCLog.d("AniList '$query' → ${list.take(4)}")
        list
    } catch (e: Exception) {
        BCLog.e("AniList failed for '$query': ${e.message}")
        emptyList()
    }
}

// ═══════════════════════════════════════════
// ── WP REST SEARCH ──
// ═══════════════════════════════════════════
private suspend fun gogoRestSearch(q: String): List<Pair<String, String>> {
    val cacheKey = "gogo:rest:$q"
    BCCache.get(cacheKey, 60 * 60 * 1000L)?.let { cached ->
        return cached.split("\n").mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size == 2) parts[0] to parts[1] else null
        }
    }

    val url = "$GOGO_DOMAIN/wp-json/wp/v2/search?search=${URLEncoder.encode(q, "UTF-8")}&per_page=20&subtype=series"
    return try {
        val res = app.get(url)
        if (res.code !in 200..299) {
            BCLog.d("Gogo REST '$q' code=${res.code}")
            return emptyList()
        }
        val arr = JSONArray(res.text)
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("subtype") != "series") continue
            val title = o.optString("title").takeIf { it.isNotBlank() } ?: continue
            val link = o.optString("url").takeIf { it.isNotBlank() } ?: continue
            out.add(title to link)
        }
        BCCache.put(cacheKey, out.joinToString("\n") { "${it.first}\t${it.second}" })
        BCLog.d("Gogo REST '$q': ${out.size} series")
        out
    } catch (e: Exception) {
        BCLog.e("Gogo REST '$q' failed: ${e.message}")
        emptyList()
    }
}

// ═══════════════════════════════════════════
// ── SLUG PROBE (fallback) ──
// ═══════════════════════════════════════════
private fun slugify(t: String): String =
    t.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')

private fun gogoSlugVariants(title: String): List<String> {
    val base = slugify(title)
    if (base.isBlank()) return emptyList()
    val out = linkedSetOf<String>()
    out.add(base)
    out.add(base.replace("shippuden", "shippuuden"))
    val noSeason = base.replace(Regex("""-season-?\d+$"""), "").trim('-')
    if (noSeason.isNotBlank() && noSeason != base) out.add(noSeason)
    return out.filter { it.isNotBlank() }.take(3).toList()
}

private suspend fun gogoProbeSeries(slug: String): GogoCandidate? {
    val url = "$GOGO_DOMAIN/series/$slug/"
    return try {
        val res = app.get(url)
        if (res.code != 200) return null
        val html = res.text
        if (html.length < 5000) return null
        val doc = Jsoup.parse(html, url)
        val eps = doc.select(".episodes-container .episode-item")
            .ifEmpty { doc.select(".episode-item") }
        if (eps.isEmpty()) return null
        val titleText = doc.selectFirst("h1.entry-title, .entry-title, h1")?.text()?.trim().orEmpty()
        if (titleText.isBlank()) return null
        if (titleText.startsWith("Gogoanime", ignoreCase = true)) return null
        GogoCandidate(url, titleText, 40)
    } catch (_: Exception) { null }
}

private suspend fun gogoSlugFallback(query: String): List<GogoCandidate> {
    val variants = gogoSlugVariants(query)
    for (slug in variants) {
        gogoProbeSeries(slug)?.let { return listOf(it) }
    }
    return emptyList()
}

// ═══════════════════════════════════════════
// ── COMBINED SEARCH ──
// ═══════════════════════════════════════════
private suspend fun gogoSearchCandidates(query: String): List<GogoCandidate> {
    // 1. Get aliases: original + AniList romaji/english/synonyms
    val aliases = (listOf(query) + anilistAltTitles(query))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    // 2. WP REST search for each alias
    val all = mutableListOf<GogoCandidate>()
    for (alias in aliases) {
        val hits = gogoRestSearch(alias)
        if (hits.isEmpty()) continue
        for ((title, link) in hits) {
            // score against ALL aliases; take best match across aliases
            val best = aliases.maxOf { a -> gogoScore(a, title) }
            if (best > 0) all.add(GogoCandidate(link, title, best))
        }
    }

    // 3. Dedupe by URL, keep max score, sort
    val merged = all.groupBy { it.url }
        .map { (_, l) -> l.maxByOrNull { it.score }!! }
        .sortedByDescending { it.score }
        .take(8)

    if (merged.isNotEmpty()) {
        BCLog.d("Gogo merged: ${merged.size} (aliases=${aliases.size}) top: ${merged.take(3).map { "${it.score}:${it.title}" }}")
        return merged
    }

    // 4. Fallback: slug probe on original query
    val fromSlug = gogoSlugFallback(query)
    if (fromSlug.isNotEmpty()) {
        BCLog.d("Gogo slug fallback: ${fromSlug.size}")
        return fromSlug
    }

    BCLog.d("Gogo: no candidates (aliases tried: $aliases)")
    return emptyList()
}

// ═══════════════════════════════════════════
// ── EPISODES ──
// ═══════════════════════════════════════════
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

// ═══════════════════════════════════════════
// ── ENTRY ──
// ═══════════════════════════════════════════
suspend fun gogoExtractRaw(q: StreamQuery): List<ScrapedMirror> {
    val candidates = gogoSearchCandidates(q.title)
    if (candidates.isEmpty()) { BCLog.d("Gogo: no candidates"); return emptyList() }

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
