package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

data class StreamQuery(
    val title: String,
    val year: String,
    val type: String,
    val imdbId: String = "",
    val season: Int = 0,
    val episode: Int = 0
)

data class ScrapedMirror(
    val quality: String,
    val mirror: String,
    val url: String,
    val source: String
)

// ─────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────
private suspend fun resolveDomain(key: String, fallback: String): String {
    return try {
        val json = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json").text
        val live = JSONObject(json).optString(key).trim()
        if (live.startsWith("http")) live else fallback
    } catch (e: Exception) {
        fallback
    }
}

private fun normalize(s: String): String =
    s.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()

private fun titleMatches(a: String, b: String): Boolean {
    val na = normalize(a)
    val nb = normalize(b)
    if (na.isEmpty() || nb.isEmpty()) return false
    if (na.contains(nb) || nb.contains(na)) return true
    val ta = na.split(" ").toSet()
    val tb = nb.split(" ").toSet()
    val common = ta.intersect(tb)
    return common.size >= 2 && common.size.toFloat() / maxOf(ta.size, tb.size) >= 0.6f
}

private suspend fun safeGet(url: String): org.jsoup.nodes.Document? {
    return try {
        app.get(url).document
    } catch (e: Exception) {
        Log.e("BingeCloud", "HTTP failed $url: ${e.message}")
        null
    }
}

// ─────────────────────────────────────────
// VegaMovies — raw scrape
// ─────────────────────────────────────────
private suspend fun vegamoviesFindPage(title: String, year: String, type: String): String? {
    val domain = resolveDomain("vegamovies", "https://vegamovies.mq")
    return try {
        val json = app.get("$domain/search.php?q=${URLEncoder.encode(title, "UTF-8")}").text
        val hits = JSONObject(json).optJSONArray("hits") ?: return null
        var bestPath: String? = null
        var bestScore = 0
        for (i in 0 until hits.length()) {
            val doc = hits.getJSONObject(i).optJSONObject("document") ?: continue
            val postTitle = doc.optString("post_title")
            val permalink = doc.optString("permalink")
            if (postTitle.isEmpty() || permalink.isEmpty()) continue
            if (!titleMatches(title, postTitle)) continue
            var score = 1
            if (year.isNotBlank() && postTitle.contains(year)) score += 2
            val lower = postTitle.lowercase()
            if (type == "series" && (lower.contains("season") || lower.contains("series"))) score += 2
            if (type == "movie" && (lower.contains("movie") || !lower.contains("season"))) score += 1
            if (score > bestScore) { bestScore = score; bestPath = permalink }
        }
        bestPath?.let { if (it.startsWith("http")) it else "$domain$it" }
    } catch (e: Exception) {
        Log.e("BingeCloud", "VM search failed: ${e.message}")
        null
    }
}

private suspend fun vegamoviesExtractMovieRaw(pageUrl: String): List<ScrapedMirror> {
    val out = mutableListOf<ScrapedMirror>()
    val doc = safeGet(pageUrl) ?: return out
    val headers = doc.select("h3, h4, h5").filter {
        val txt = it.text()
        txt.contains(Regex("""\d{3,4}[pP]""")) && !txt.contains("Zip", true)
    }
    for (header in headers) {
        val q = Regex("""(\d{3,4}[pP])""").find(header.text())?.value ?: continue
        val nextEl = header.nextElementSibling()
        val links = if (nextEl != null && nextEl.tagName() == "p") nextEl.select("a") else header.select("a")
        val dl = links.firstOrNull { it.text().contains("Download", true) }
            ?: links.firstOrNull { it.text().contains("V-Cloud", true) }
            ?: continue
        out.add(ScrapedMirror(q, "Vega", dl.attr("href"), "VM"))
    }
    return out
}

private suspend fun vegamoviesExtractSeriesRaw(pageUrl: String, season: Int, episode: Int): List<ScrapedMirror> {
    val out = mutableListOf<ScrapedMirror>()
    val doc = safeGet(pageUrl) ?: return out
    val headers = doc.select("h3, h4, h5").filter {
        val txt = it.text()
        Regex("""(?:Season\s*|S)(\d+)""", RegexOption.IGNORE_CASE).containsMatchIn(txt) &&
            !txt.contains("Zip", true)
    }
    val target = headers.firstOrNull {
        Regex("""(?:Season\s*|S)(\d+)""", RegexOption.IGNORE_CASE)
            .find(it.text())?.groupValues?.getOrNull(1)?.toIntOrNull() == season
    } ?: return out
    val q = Regex("""(\d{3,4}[pP])""").find(target.text())?.value ?: "Unknown"
    val nextEl = target.nextElementSibling()
    val links = if (nextEl != null && nextEl.tagName() == "p") nextEl.select("a") else target.select("a")
    val dl = links.firstOrNull { it.text().contains("Download", true) } ?: return out
    out.add(ScrapedMirror(q, "Vega", dl.attr("href"), "VM"))
    return out
}

// ─────────────────────────────────────────
// MoviesDrive — raw scrape
// ─────────────────────────────────────────
private suspend fun moviesdriveFindPage(title: String, year: String, type: String): String? {
    val domain = resolveDomain("moviesdrive", "https://new4.moviesdrive.christmas")
    return try {
        val html = app.get("$domain/?s=${URLEncoder.encode(title, "UTF-8")}").text
        val doc = Jsoup.parse(html)
        val cards = doc.select("#moviesGridMain > a")
        var bestUrl: String? = null
        var bestScore = 0
        for (a in cards) {
            val alt = a.selectFirst("p.poster-title")?.text()
                ?: a.selectFirst("img")?.attr("alt") ?: continue
            val href = a.attr("href")
            if (href.isEmpty() || !titleMatches(title, alt)) continue
            var score = 1
            if (year.isNotBlank() && alt.contains(year)) score += 2
            val lower = alt.lowercase()
            if (type == "series" && (lower.contains("season") || lower.contains("series"))) score += 2
            if (type == "movie" && (lower.contains("movie") || !lower.contains("season"))) score += 1
            if (score > bestScore) { bestScore = score; bestUrl = href }
        }
        bestUrl
    } catch (e: Exception) {
        Log.e("BingeCloud", "MD search failed: ${e.message}")
        null
    }
}

private suspend fun moviesdriveExtractMovieRaw(pageUrl: String): List<ScrapedMirror> {
    val out = mutableListOf<ScrapedMirror>()
    val doc = safeGet(pageUrl) ?: return out
    val headers = doc.select("h5").filter {
        val txt = it.text()
        txt.contains(Regex("""\d{3,4}[pP]""")) && !txt.contains("Zip", true)
    }
    for (h in headers) {
        val q = Regex("""(\d{3,4}[pP])""").find(h.text())?.value ?: continue
        var cursor = h.nextElementSibling()
        var steps = 0
        while (cursor != null && steps < 4) {
            val link = cursor.selectFirst("a[href*='archive']")
                ?: cursor.selectFirst("a[href*='mdrive']")
            if (link != null && link.attr("href").isNotEmpty()) {
                out.add(ScrapedMirror(q, "MoviesDrive", link.attr("href"), "MD"))
                break
            }
            if (cursor.tagName() == "h5" &&
                Regex("""\d{3,4}[pP]""").containsMatchIn(cursor.text())) break
            cursor = cursor.nextElementSibling()
            steps++
        }
    }
    return out
}

private suspend fun moviesdriveExtractSeriesRaw(pageUrl: String, season: Int, episode: Int): List<ScrapedMirror> {
    val out = mutableListOf<ScrapedMirror>()
    val doc = safeGet(pageUrl) ?: return out
    val allH5 = doc.select("h5")
    for (i in allH5.indices) {
        val h = allH5[i]
        val txt = h.text()
        val sMatch = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(txt) ?: continue
        val s = sMatch.groupValues[1].toIntOrNull() ?: continue
        if (s != season) continue
        val q = Regex("""(\d{3,4}[pP])""").find(txt)?.value ?: continue
        var j = i + 1
        while (j < allH5.size && j < i + 4) {
            val next = allH5[j]
            val nextTxt = next.text()
            if (Regex("""Season\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(nextTxt) &&
                nextTxt.contains(Regex("""\d{3,4}[pP]"""))) break
            if (nextTxt.contains("Single Episode", true)) {
                val href = next.selectFirst("a")?.attr("href") ?: ""
                if (href.isNotEmpty()) out.add(ScrapedMirror(q, "MoviesDrive", href, "MD"))
                break
            }
            j++
        }
    }
    return out
}

// ─────────────────────────────────────────
// HDhub4u — raw scrape
// ─────────────────────────────────────────
private suspend fun hdhub4uFindPage(title: String, year: String, type: String): String? {
    val domain = resolveDomain("hdhub4u", "https://new5.hdhub4u.cl")
    return try {
        val html = app.get("$domain/?s=${URLEncoder.encode(title, "UTF-8")}").text
        val doc = Jsoup.parse(html)
        val cards = doc.select("li.thumb")
        var bestUrl: String? = null
        var bestScore = 0
        for (card in cards) {
            val alt = card.selectFirst("figcaption p")?.text()
                ?: card.selectFirst("img")?.attr("alt") ?: continue
            val href = card.selectFirst("a")?.attr("href") ?: continue
            if (href.isEmpty() || !titleMatches(title, alt)) continue
            var score = 1
            if (year.isNotBlank() && alt.contains(year)) score += 2
            val lower = alt.lowercase()
            if (type == "series" && (lower.contains("season") || lower.contains("series"))) score += 2
            if (type == "movie" && (lower.contains("movie") || !lower.contains("season"))) score += 1
            if (score > bestScore) { bestScore = score; bestUrl = href }
        }
        bestUrl
    } catch (e: Exception) {
        Log.e("BingeCloud", "HDH search failed: ${e.message}")
        null
    }
}

private suspend fun hdhub4uExtractRaw(pageUrl: String): List<ScrapedMirror> {
    val out = mutableListOf<ScrapedMirror>()
    val doc = safeGet(pageUrl) ?: return out
    val anchors = doc.select("a[href]")
    for (a in anchors) {
        val href = a.attr("href").trim()
        if (href.isEmpty() || href.startsWith("#") || !href.startsWith("http")) continue

        // Skip junk
        if (href.contains("t.me/") || href.contains("whatsapp") || href.contains("telegram")) continue
        if (href.contains("hdstream4u.com") || href.contains("greenmountmotors.com")) continue
        if (href.contains("hdhub4u.") && href.contains("/category/")) continue

        // Only accept known wrapper/stream hosts
        val isWrapper =
            href.contains("hubdrive.", true) ||
            href.contains("hubcdn.", true) ||
            href.contains("hblinks.co/archives/") ||
            href.contains("4khdhub.one/") ||
            href.contains("hubcloud.ist/drive/") ||
            href.contains("hubcloud.cx/drive/") ||
            href.contains("vcloud.")
        if (!isWrapper) continue

        val text = a.text().lowercase()
        val q = Regex("""(\d{3,4}[pP])""").find(text)?.value
            ?: if (text.contains("4k") || text.contains("2160")) "2160p" else "Unknown"

        out.add(ScrapedMirror(q, "HDhub4u", href, "HDH"))
    }
    return out
}

// ─────────────────────────────────────────
// Wrapper resolution — called from loadLinks
// ─────────────────────────────────────────
suspend fun resolveWrapper(url: String): String? {
    // Already a final HubCloud / VCloud URL — pass through
    if (url.contains("hubcloud.ist/drive/", true) || url.contains("hubcloud.cx/drive/", true)) return url
    if (url.contains("vcloud.", true)) return url

    // Known dead ends
    if (url.contains("greenmountmotors.com")) return null
    if (url.contains("hdstream4u.com")) return null

    // Fetch wrapper, look for hubcloud / vcloud anchor
    return try {
        val doc = app.get(url).document
        doc.selectFirst("a[href*='hubcloud.ist/drive/'], a[href*='hubcloud.cx/drive/']")?.attr("href")?.let { return it }
        doc.selectFirst("a[href*='vcloud.']")?.attr("href")?.let { return it }
        null
    } catch (e: Exception) {
        Log.e("BingeCloud", "resolveWrapper failed for $url: ${e.message}")
        null
    }
}

// ─────────────────────────────────────────
// Main entry — parallel across sources
// ─────────────────────────────────────────
suspend fun scrapeAllSources(q: StreamQuery): List<ScrapedMirror> {
    return coroutineScope {
        val vmJob = async {
            try {
                val page = vegamoviesFindPage(q.title, q.year, q.type) ?: return@async emptyList()
                if (q.type == "series") vegamoviesExtractSeriesRaw(page, q.season, q.episode)
                else vegamoviesExtractMovieRaw(page)
            } catch (e: Exception) {
                Log.e("BingeCloud", "VM task failed: ${e.message}"); emptyList()
            }
        }
        val mdJob = async {
            try {
                val page = moviesdriveFindPage(q.title, q.year, q.type) ?: return@async emptyList()
                if (q.type == "series") moviesdriveExtractSeriesRaw(page, q.season, q.episode)
                else moviesdriveExtractMovieRaw(page)
            } catch (e: Exception) {
                Log.e("BingeCloud", "MD task failed: ${e.message}"); emptyList()
            }
        }
        val hdJob = async {
            try {
                val page = hdhub4uFindPage(q.title, q.year, q.type) ?: return@async emptyList()
                hdhub4uExtractRaw(page)
            } catch (e: Exception) {
                Log.e("BingeCloud", "HDH task failed: ${e.message}"); emptyList()
            }
        }
        listOf(vmJob, mdJob, hdJob).awaitAll().flatten()
    }
}
