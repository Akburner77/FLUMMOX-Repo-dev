package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
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

private suspend fun resolveDomain(key: String, fallback: String): String {
    return try {
        val json = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json").text
        val live = JSONObject(json).optString(key).trim()
        if (live.startsWith("http")) live else fallback
    } catch (e: Exception) {
        BCLog.e("resolveDomain($key): ${e.message}")
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
        BCLog.e("GET failed $url: ${e.message}")
        null
    }
}

// ═══════════════════════════════════════════
// VegaMovies
// ═══════════════════════════════════════════
private suspend fun vegamoviesFindPage(title: String, year: String, type: String): String? {
    val domain = resolveDomain("vegamovies", "https://vegamovies.mq")
    BCLog.d("VM: searching '$title' on $domain")
    return try {
        val json = app.get("$domain/search.php?q=${URLEncoder.encode(title, "UTF-8")}").text
        val hits = JSONObject(json).optJSONArray("hits") ?: run {
            BCLog.e("VM: no 'hits' in search response")
            return null
        }
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
        if (bestPath != null) BCLog.d("VM: matched ${bestPath} (score=$bestScore)")
        else BCLog.d("VM: no title match out of ${hits.length()} hits")
        bestPath?.let { if (it.startsWith("http")) it else "$domain$it" }
    } catch (e: Exception) {
        BCLog.e("VM search failed: ${e.message}")
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
    BCLog.d("VM: extracted ${out.size} mirrors")
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
    } ?: run {
        BCLog.e("VM: no season $season header found")
        return out
    }
    val q = Regex("""(\d{3,4}[pP])""").find(target.text())?.value ?: "Unknown"
    val nextEl = target.nextElementSibling()
    val links = if (nextEl != null && nextEl.tagName() == "p") nextEl.select("a") else target.select("a")
    val dl = links.firstOrNull { it.text().contains("Download", true) } ?: return out
    out.add(ScrapedMirror(q, "Vega", dl.attr("href"), "VM"))
    BCLog.d("VM: series S${season}E${episode} -> ${out.size} mirrors")
    return out
}

// ═══════════════════════════════════════════
// MoviesDrive — NEW search.html + h5 > a extraction
// ═══════════════════════════════════════════
private suspend fun moviesdriveFindPage(title: String, year: String, type: String): String? {
    val domain = resolveDomain("moviesdrive", "https://new4.moviesdrive.christmas")
    BCLog.d("MD: searching '$title' via search.html on $domain")
    return try {
        val html = app.get("$domain/search.html?q=${URLEncoder.encode(title, "UTF-8")}").text
        val doc = Jsoup.parse(html)
        val cards = doc.select("#moviesGridMain > a")
        BCLog.d("MD: ${cards.size} result cards")
        if (cards.isEmpty()) {
            BCLog.e("MD: selector '#moviesGridMain > a' returned 0")
        }
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
        if (bestUrl != null) BCLog.d("MD: matched $bestUrl (score=$bestScore)")
        else BCLog.d("MD: no title match")
        bestUrl?.let { if (it.startsWith("http")) it else "$domain$it" }
    } catch (e: Exception) {
        BCLog.e("MD search failed: ${e.message}")
        null
    }
}

/** Movie extraction — follow each "Download" button, parse intermediate page for hubcloud/gdflix/gdlink */
private suspend fun moviesdriveExtractMovieRaw(pageUrl: String): List<ScrapedMirror> {
    val out = mutableListOf<ScrapedMirror>()
    val doc = safeGet(pageUrl) ?: return out
    val buttons = doc.select("h5 > a")
    BCLog.d("MD: ${buttons.size} download buttons on movie page")
    for (button in buttons) {
        val href = button.attr("href")
        if (href.isEmpty()) continue
        val parentText = button.parent()?.text() ?: button.text()
        val q = Regex("""(\d{3,4}[pP])""").find(parentText)?.value ?: "Unknown"
        val intermediateDoc = safeGet(href) ?: continue
        val innerLinks = intermediateDoc.select("a").filter {
            val h = it.attr("href")
            h.contains("hubcloud", true) || h.contains("gdflix", true) || h.contains("gdlink", true)
        }
        BCLog.d("MD: q=$q intermediate ${href.take(60)} -> ${innerLinks.size} inner links")
        for (link in innerLinks) {
            val source = link.attr("href")
            if (source.isEmpty()) continue
            out.add(ScrapedMirror(q, "MoviesDrive", source, "MD"))
        }
    }
    BCLog.d("MD: extracted ${out.size} mirrors")
    return out
}

/** Series extraction — walk "h5 > a" buttons, follow episode page, parse episode spans */
private suspend fun moviesdriveExtractSeriesRaw(pageUrl: String, season: Int, episode: Int): List<ScrapedMirror> {
    val out = mutableListOf<ScrapedMirror>()
    val doc = safeGet(pageUrl) ?: return out
    val buttons = doc.select("h5 > a").filter { !it.text().contains("Zip", true) }
    BCLog.d("MD: ${buttons.size} season/series buttons")

    for (button in buttons) {
        val titleElement = button.parent()?.previousElementSibling()
        val mainTitle = titleElement?.text() ?: button.parent()?.text() ?: ""
        val realSeason = Regex("""(?:Season\s*|S)(\d+)""", RegexOption.IGNORE_CASE)
            .find(mainTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        if (realSeason != season) continue

        val episodePageUrl = button.attr("href")
        if (episodePageUrl.isEmpty()) continue
        val epDoc = safeGet(episodePageUrl) ?: continue

        var elements = epDoc.select("span:matches((?i)(Ep))")
        if (elements.isEmpty()) {
            elements = epDoc.select("a:matches((?i)(HubCloud|GDFlix))")
        }
        BCLog.d("MD: S$season episode page -> ${elements.size} elements")

        var currentEp = 1
        for (el in elements) {
            if (el.tagName() == "span") {
                currentEp = Regex("""Ep\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(el.toString())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: currentEp
                var sibling = el.parent()?.nextElementSibling()
                while (sibling != null) {
                    val txt = sibling.text()
                    if (txt.contains("HubCloud", true) || txt.contains("gdflix", true) || txt.contains("gdlink", true)) {
                        val aTag = sibling.selectFirst("a")
                        val epUrl = aTag?.attr("href") ?: ""
                        if (epUrl.isNotEmpty() && currentEp == episode) {
                            val q = Regex("""(\d{3,4}[pP])""").find(mainTitle)?.value ?: "Unknown"
                            out.add(ScrapedMirror(q, "MoviesDrive", epUrl, "MD"))
                        }
                        sibling = sibling.nextElementSibling()
                    } else break
                }
                currentEp++
            } else {
                val epUrl = el.attr("href")
                if (epUrl.isNotEmpty() && currentEp == episode) {
                    val q = Regex("""(\d{3,4}[pP])""").find(mainTitle)?.value ?: "Unknown"
                    out.add(ScrapedMirror(q, "MoviesDrive", epUrl, "MD"))
                }
                currentEp++
            }
        }
    }
    BCLog.d("MD: series S${season}E${episode} -> ${out.size} mirrors")
    return out
}

// ═══════════════════════════════════════════
// HDhub4u
// ═══════════════════════════════════════════
private suspend fun hdhub4uFindPage(title: String, year: String, type: String): String? {
    val domain = resolveDomain("hdhub4u", "https://new5.hdhub4u.cl")
    BCLog.d("HDH: searching '$title' on $domain")
    return try {
        val html = app.get("$domain/?s=${URLEncoder.encode(title, "UTF-8")}").text
        val doc = Jsoup.parse(html)
        val cards = doc.select("li.thumb")
        BCLog.d("HDH: ${cards.size} result cards")
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
        if (bestUrl != null) BCLog.d("HDH: matched $bestUrl (score=$bestScore)")
        else BCLog.d("HDH: no title match")
        bestUrl
    } catch (e: Exception) {
        BCLog.e("HDH search failed: ${e.message}")
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
        if (href.contains("t.me/") || href.contains("whatsapp") || href.contains("telegram")) continue
        if (href.contains("hdstream4u.com") || href.contains("greenmountmotors.com")) continue
        if (href.contains("hdhub4u.") && href.contains("/category/")) continue

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
    BCLog.d("HDH: extracted ${out.size} mirrors")
    return out
}

// ═══════════════════════════════════════════
// MovieBox — native API
// ═══════════════════════════════════════════
private suspend fun movieboxFindSubject(title: String, year: String, type: String): MBSubject? {
    val results = try { mbSearch(title) } catch (e: Exception) {
        BCLog.e("MB search failed: ${e.message}")
        return null
    }
    BCLog.d("MB: search returned ${results.size} results")
    if (results.isEmpty()) return null

    val expectedType = if (type == "series") 2 else 1
    var best: MBSubject? = null
    var bestScore = 0
    for (s in results) {
        if (!titleMatches(title, s.title)) continue
        var score = 1
        if (year.isNotBlank() && s.year?.toString()?.contains(year) == true) score += 2
        if (s.type == expectedType) score += 2
        if (score > bestScore) { bestScore = score; best = s }
    }
    if (best != null) BCLog.d("MB: matched '${best.title}' (${best.year}) id=${best.subjectId}")
    else BCLog.d("MB: no title match")
    return best
}

private suspend fun movieboxExtractRaw(q: StreamQuery): List<ScrapedMirror> {
    val subject = movieboxFindSubject(q.title, q.year, q.type) ?: return emptyList()
    val streams = try {
        mbPlay(subject.subjectId, q.season, q.episode)
    } catch (e: Exception) {
        BCLog.e("MB play failed: ${e.message}")
        emptyList()
    }
    BCLog.d("MB: play returned ${streams.size} streams")
    return streams.map { s ->
        ScrapedMirror(
            quality = s.quality.ifBlank { "Auto" },
            mirror = "MovieBox",
            url = s.url,
            source = "MB"
        )
    }
}

// ═══════════════════════════════════════════
// Wrapper resolution
// ═══════════════════════════════════════════
suspend fun resolveWrapper(url: String): String? {
    if (url.contains("hubcloud.ist/drive/", true) || url.contains("hubcloud.cx/drive/", true)) return url
    if (url.contains("vcloud.", true)) return url
    if (url.contains("greenmountmotors.com")) return null
    if (url.contains("hdstream4u.com")) return null

    val doc = cloudflareGetDoc(url)
    if (doc == null) {
        BCLog.e("resolveWrapper: fetch failed for $url")
        return null
    }
    doc.selectFirst("a[href*='hubcloud.ist/drive/'], a[href*='hubcloud.cx/drive/']")?.attr("href")?.let { return it }
    doc.selectFirst("a[href*='vcloud.']")?.attr("href")?.let { return it }
    return null
}

// ═══════════════════════════════════════════
// Entry
// ═══════════════════════════════════════════
suspend fun scrapeAllSources(q: StreamQuery): List<ScrapedMirror> {
    BCLog.section("scrapeAllSources: ${q.title} (${q.year}) ${q.type} S${q.season}E${q.episode}")
    return coroutineScope {
        val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<ScrapedMirror>>>()

        if (Settings.isSrcVm()) {
            jobs.add(async {
                try {
                    val page = vegamoviesFindPage(q.title, q.year, q.type) ?: return@async emptyList()
                    if (q.type == "series") vegamoviesExtractSeriesRaw(page, q.season, q.episode)
                    else vegamoviesExtractMovieRaw(page)
                } catch (e: Exception) {
                    BCLog.e("VM task failed: ${e.message}"); emptyList()
                }
            })
        }
        if (Settings.isSrcMd()) {
            jobs.add(async {
                try {
                    val page = moviesdriveFindPage(q.title, q.year, q.type) ?: return@async emptyList()
                    if (q.type == "series") moviesdriveExtractSeriesRaw(page, q.season, q.episode)
                    else moviesdriveExtractMovieRaw(page)
                } catch (e: Exception) {
                    BCLog.e("MD task failed: ${e.message}"); emptyList()
                }
            })
        }
        if (Settings.isSrcHdh()) {
            jobs.add(async {
                try {
                    val page = hdhub4uFindPage(q.title, q.year, q.type) ?: return@async emptyList()
                    hdhub4uExtractRaw(page)
                } catch (e: Exception) {
                    BCLog.e("HDH task failed: ${e.message}"); emptyList()
                }
            })
        }
        if (Settings.isSrcMovieBox()) {
            jobs.add(async {
                try {
                    movieboxExtractRaw(q)
                } catch (e: Exception) {
                    BCLog.e("MB task failed: ${e.message}"); emptyList()
                }
            })
        }

        if (jobs.isEmpty()) {
            BCLog.d("no sources enabled")
            return@coroutineScope emptyList()
        }
        val all = jobs.awaitAll().flatten()
        val vm = all.count { it.source == "VM" }
        val md = all.count { it.source == "MD" }
        val hdh = all.count { it.source == "HDH" }
        val mb = all.count { it.source == "MB" }
        BCLog.d("sources done — VM=$vm MD=$md HDH=$hdh MB=$mb total=${all.size}")
        all
    }
}

suspend fun isHubcloudAlive(url: String): Boolean {
    return try {
        val html = app.get(url, timeout = 2500L).text
        html.contains("card-header", true) ||
            html.contains("File Size", true) ||
            html.contains("btn-success", true) ||
            html.contains("btn-danger", true)
    } catch (e: Exception) {
        false
    }
}
