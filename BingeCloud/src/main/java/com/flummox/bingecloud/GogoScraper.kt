package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup
import java.net.URLEncoder

// ═══════════════════════════════════════════
// ── GogoAnime scraper ──
// 1) ?s= WordPress-style search (with short-query fallback)
// 2) slug probe for variants / dub / spellings
// ═══════════════════════════════════════════

private const val GOGO_DOMAIN = "https://gogoanime.by"

private data class GogoCandidate(val url: String, val title: String, val score: Int)

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
// ── SLUG PROBE ──
// ═══════════════════════════════════════════
private fun slugify(t: String): String =
    t.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')

private fun slugifyNoApos(t: String): String =
    t.lowercase().replace("'", "").replace(Regex("""[^a-z0-9]+"""), "-").trim('-')

private fun gogoSlugVariants(title: String): List<String> {
    val base = slugify(title)
    val noApos = slugifyNoApos(title)
    if (base.isBlank() && noApos.isBlank()) return emptyList()
    val out = linkedSetOf<String>()
    out.add(base)
    if (noApos != base) out.add(noApos)

    // shippuden ↔ shippuuden
    out.add(base.replace("shippuden", "shippuuden"))
    out.add(base.replace("shippuuden", "shippuden"))

    // strip trailing "-season-N"
    val noSeason = base.replace(Regex("""-season-?\d+$"""), "").trim('-')
    if (noSeason.isNotBlank() && noSeason != base) out.add(noSeason)

    // strip "the-" prefix
    if (base.startsWith("the-")) out.add(base.removePrefix("the-"))

    // dub variants last
    out.add("$base-dub")
    out.add("$base-english-dub")

    return out.filter { it.isNotBlank() }.toList()
}

/** Returns candidate ONLY if page exists AND has episodes AND real title. */
private suspend fun gogoProbeSeries(slug: String): GogoCandidate? {
    val url = "$GOGO_DOMAIN/series/$slug/"
    return try {
        val res = app.get(url)
        if (res.code != 200) return null
        val html = res.text
        if (html.length < 5000) return null
        val doc = Jsoup.parse(html, url)

        // hard reject: must have episodes
        val episodeItems = doc.select(".episodes-container .episode-item")
        val looseItems = if (episodeItems.isEmpty()) doc.select(".episode-item") else episodeItems
        if (looseItems.isEmpty()) return null

        val titleEl = doc.selectFirst("h1.entry-title, .entry-title, h1")
        val titleText = titleEl?.text()?.trim().orEmpty()
        if (titleText.isBlank()) return null
        if (titleText.startsWith("Gogoanime", ignoreCase = true)) return null
        if (titleText.length < 2) return null

        BCLog.d("Gogo slug HIT: $slug → '${titleText.take(60)}' (${looseItems.size} eps)")
        GogoCandidate(url, titleText, 40)
    } catch (e: Exception) {
        BCLog.d("Gogo slug '$slug' threw: ${e.message}")
        null
    }
}

private suspend fun gogoSlugSearch(query: String, maxTries: Int = 6): List<GogoCandidate> {
    val variants = gogoSlugVariants(query).take(maxTries)
    val out = mutableListOf<GogoCandidate>()
    for (slug in variants) {
        gogoProbeSeries(slug)?.let { out.add(it) }
        if (out.size >= 3) break
    }
    return out
}

// ═══════════════════════════════════════════
// ── DIAGNOSTIC ──
// ═══════════════════════════════════════════
private fun logSearchMiss(q: String, code: Int, html: String, doc: org.jsoup.nodes.Document) {
    val aBs = doc.select("article.bs").size
    val bsx = doc.select("article.bs .bsx").size
    val bsxAny = doc.select(".bsx").size
    val seriesLinks = doc.select("a[href*='/series/']").size
    val lower = html.lowercase()
    val cf = listOf(
        "just a moment", "cf-chl", "cf_chl_opt",
        "enable javascript and cookies", "attention required! | cloudflare",
        "challenges.cloudflare.com"
    ).any { lower.contains(it) }
    BCLog.d("Gogo '$q' MISS | code=$code len=${html.length} cf=$cf")
    BCLog.d("Gogo '$q' sel | article.bs=$aBs bsx=$bsx bsxAny=$bsxAny /series/=$seriesLinks")
}

// ═══════════════════════════════════════════
// ── HTML SEARCH ──
// ═══════════════════════════════════════════
private fun queryVariants(query: String): List<String> {
    val base = query.trim()
    val stripped = base.replace(Regex("""[:!?.,;']"""), "")
    val words = base.split(Regex("\\s+")).filter { it.isNotBlank() }
    val out = linkedSetOf<String>()
    out.add(base)
    if (stripped != base) out.add(stripped)
    if (words.size > 3) out.add(words.take(3).joinToString(" "))
    if (words.size > 2) out.add(words.take(2).joinToString(" "))
    if (words.size > 1) out.add(words.first())
    return out.toList()
}

private fun parseSearchResults(doc: org.jsoup.nodes.Document, originalQuery: String): List<GogoCandidate> {
    // primary selector
    var cards = doc.select("article.bs .bsx > a[href]")
    if (cards.isEmpty()) cards = doc.select("div.bs .bsx > a[href]")
    if (cards.isEmpty()) cards = doc.select(".listupd .bsx > a[href]")
    if (cards.isEmpty()) return emptyList()

    val out = mutableListOf<GogoCandidate>()
    for (a in cards) {
        val href = a.attr("href")
        if (href.isEmpty() || !href.contains("/series/")) continue
        val title = a.attr("title").ifBlank {
            a.selectFirst(".tt")?.text()?.trim() ?: ""
        }.trim()
        if (title.isEmpty()) continue
        val score = gogoScore(originalQuery, title)
        if (score > 0) {
            val full = if (href.startsWith("http")) href else "$GOGO_DOMAIN$href"
            out.add(GogoCandidate(full, title, score))
        }
    }
    return out
}

private suspend fun gogoHtmlSearch(query: String): List<GogoCandidate> {
    val attempts = queryVariants(query)
    for (q in attempts) {
        val res = try {
            app.get("$GOGO_DOMAIN/?s=${URLEncoder.encode(q, "UTF-8")}")
        } catch (e: Exception) {
            BCLog.e("Gogo search '$q' threw: ${e.message}")
            continue
        }
        val html = res.text
        val doc = Jsoup.parse(html, "$GOGO_DOMAIN/")
        val hits = parseSearchResults(doc, query)
        if (hits.isEmpty()) {
            logSearchMiss(q, res.code, html, doc)
            continue
        }
        BCLog.d("Gogo search '$q': ${hits.size} hits (code=${res.code})")
        val sorted = hits.sortedByDescending { it.score }
        BCLog.d("Gogo search '$q' top: ${sorted.take(3).map { "${it.score}:${it.title}" }}")
        return sorted.take(6)
    }
    return emptyList()
}

// ═══════════════════════════════════════════
// ── COMBINED SEARCH ──
// ═══════════════════════════════════════════
private suspend fun gogoSearchCandidates(query: String): List<GogoCandidate> {
    val fromSearch = gogoHtmlSearch(query)
    val fromSlug = gogoSlugSearch(query).filter { s -> fromSearch.none { it.url == s.url } }

    val merged = (fromSearch + fromSlug)
        .groupBy { it.url }
        .map { (_, list) -> list.maxByOrNull { it.score }!! }
        .sortedByDescending { it.score }

    BCLog.d("Gogo merged: ${merged.size} candidates (search=${fromSearch.size} slug=${fromSlug.size})")
    return merged.take(8)
}

// ═══════════════════════════════════════════
// ── EPISODES + EXTRACT ──
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
