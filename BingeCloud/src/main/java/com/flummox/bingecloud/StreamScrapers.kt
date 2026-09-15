package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
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
    val source: String,
    val headers: Map<String, String>? = null
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
            BCLog.e("VM: no 'hits' in search response"); return null
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
        if (bestPath != null) BCLog.d("VM: matched ${bestPath}")
        bestPath?.let { if (it.startsWith("http")) it else "$domain$it" }
    } catch (e: Exception) {
        BCLog.e("VM search failed: ${e.message}"); null
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
    } ?: return out
    val q = Regex("""(\d{3,4}[pP])""").find(target.text())?.value ?: "Unknown"
    val nextEl = target.nextElementSibling()
    val links = if (nextEl != null && nextEl.tagName() == "p") nextEl.select("a") else target.select("a")
    val dl = links.firstOrNull { it.text().contains("Download", true) } ?: return out
    out.add(ScrapedMirror(q, "Vega", dl.attr("href"), "VM"))
    return out
}

// ═══════════════════════════════════════════
// MoviesDrive — WP REST search + h5 > a extraction
// ═══════════════════════════════════════════
private suspend fun moviesdriveFindPage(title: String, year: String, type: String): String? {
    val domain = resolveDomain("moviesdrive", "https://new4.moviesdrive.christmas")
    BCLog.d("MD: WP REST search '$title' on $domain")
    return try {
        val url = "$domain/wp-json/wp/v2/posts?search=${URLEncoder.encode(title, "UTF-8")}&per_page=20"
        val json = app.get(url).text
        val arr = try { JSONArray(json) } catch (e: Exception) {
            BCLog.e("MD: REST parse failed"); return null
        }
        BCLog.d("MD: REST returned ${arr.length()} posts")
        var bestUrl: String? = null
        var bestScore = 0
        for (i in 0 until arr.length()) {
            val post = arr.optJSONObject(i) ?: continue
            val rendered = post.optJSONObject("title")?.optString("rendered") ?: continue
            val postTitle = Jsoup.parse(rendered).text()
            val link = post.optString("link")
            if (link.isEmpty()) continue
            if (!titleMatches(title, postTitle)) continue
            var score = 1
            if (year.isNotBlank() && postTitle.contains(year)) score += 2
            val lower = postTitle.lowercase()
            if (type == "series" && (lower.contains("season") || lower.contains("series"))) score += 2
            if (type == "movie" && (lower.contains("full movie") || !lower.contains("season"))) score += 1
            if (score > bestScore) { bestScore = score; bestUrl = link }
        }
        if (bestUrl != null) BCLog.d("MD: matched $bestUrl")
        else BCLog.d("MD: no title match")
        bestUrl
    } catch (e: Exception) {
        BCLog.e("MD search failed: ${e.message}"); null
    }
}

/** Movie extraction — detail page has h5 > a pointing to mdrive.lol/archive/NNN. */
private suspend fun moviesdriveExtractMovieRaw(pageUrl: String): List<ScrapedMirror> = coroutineScope {
    val doc = safeGet(pageUrl) ?: return@coroutineScope emptyList()
    val allH5 = doc.select("h5")
    val jobs = mutableListOf<Pair<String, String>>()
    for (i in allH5.indices) {
        val txt = allH5[i].text()
        val q = Regex("""(\d{3,4}[pP])""").find(txt)?.value ?: continue
        for (j in i + 1 until minOf(i + 4, allH5.size)) {
            val anchor = allH5[j].selectFirst("a[href*='mdrive.lol/archive/'], a[href*='moviesdrives']")
                ?: allH5[j].selectFirst("a[href*='archive']")
                ?: continue
            val archiveUrl = anchor.attr("href")
            if (archiveUrl.isNotEmpty()) jobs.add(q to archiveUrl)
            break
        }
    }
    val results = jobs.map { (q, archiveUrl) ->
        async { extractFromArchivePage(archiveUrl, q) }
    }.awaitAll().flatten()
    BCLog.d("MD: extracted ${results.size} mirrors")
    results
}

/** Series extraction — same detail structure, filter by season if labeled. */
private suspend fun moviesdriveExtractSeriesRaw(pageUrl: String, season: Int, episode: Int): List<ScrapedMirror> = coroutineScope {
    val doc = safeGet(pageUrl) ?: return@coroutineScope emptyList()
    val allH5 = doc.select("h5")
    val jobs = mutableListOf<Pair<String, String>>()
    for (i in allH5.indices) {
        val txt = allH5[i].text()
        val q = Regex("""(\d{3,4}[pP])""").find(txt)?.value ?: continue
        val sMatch = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(txt)
        if (sMatch != null) {
            val s = sMatch.groupValues[1].toIntOrNull() ?: 0
            if (s != season) continue
        }
        if (txt.contains("Zip", true)) continue
        for (j in i + 1 until minOf(i + 4, allH5.size)) {
            val anchor = allH5[j].selectFirst("a[href*='mdrive.lol/archive/']")
                ?: allH5[j].selectFirst("a[href*='archive']")
                ?: continue
            val archiveUrl = anchor.attr("href")
            if (archiveUrl.isNotEmpty()) jobs.add(q to archiveUrl)
            break
        }
    }
    val results = jobs.map { (q, archiveUrl) ->
        async { extractFromArchivePage(archiveUrl, q, episode) }
    }.awaitAll().flatten()
    BCLog.d("MD: series S${season}E${episode} → ${results.size} mirrors")
    results
}

/** mdrive.lol/archive/NNN page — extract EP + HubCloud/GDFlix links. */
private suspend fun extractFromArchivePage(archiveUrl: String, quality: String, targetEp: Int = 0): List<ScrapedMirror> {
    val out = mutableListOf<ScrapedMirror>()
    val doc = safeGet(archiveUrl) ?: return out
    val allH5 = doc.select("h5")
    for (i in allH5.indices) {
        val h = allH5[i]
        val txt = h.text()
        val epMatch = Regex("""EP\s*0*(\d+)""", RegexOption.IGNORE_CASE).find(txt)
        if (epMatch != null) {
            val epNum = epMatch.groupValues[1].toIntOrNull() ?: 0
            if (targetEp > 0 && epNum != targetEp) continue
            for (j in i + 1 until minOf(i + 4, allH5.size)) {
                val anchors = allH5[j].select("a[href]")
                for (a in anchors) {
                    val href = a.attr("href")
                    val label = a.text().lowercase()
                    when {
                        href.contains("hubcloud", true) -> out.add(ScrapedMirror(quality, "HubCloud", href, "MD"))
                        href.contains("gdflix", true) || label.contains("gdflix") -> out.add(ScrapedMirror(quality, "GDFlix", href, "MD"))
                    }
                }
                if (allH5[j].text().contains(Regex("""EP\s*0*\d+""", RegexOption.IGNORE_CASE))) break
            }
        } else {
            val anchors = h.select("a[href]")
            for (a in anchors) {
                val href = a.attr("href")
                val label = a.text().lowercase()
                when {
                    href.contains("hubcloud", true) -> out.add(ScrapedMirror(quality, "HubCloud", href, "MD"))
                    href.contains("gdflix", true) || label.contains("gdflix") -> out.add(ScrapedMirror(quality, "GDFlix", href, "MD"))
                }
            }
        }
    }
    return out
}

// ═══════════════════════════════════════════
// HDhub4u
// ═══════════════════════════════════════════
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
        BCLog.e("HDH search failed: ${e.message}"); null
    }
}

private suspend fun hdhub4uExtractRaw(pageUrl: String): List<ScrapedMirror> {
    val out = mutableListOf<ScrapedMirror>()
    val doc = safeGet(pageUrl) ?: return out
    for (a in doc.select("a[href]")) {
        val href = a.attr("href").trim()
        if (href.isEmpty() || href.startsWith("#") || !href.startsWith("http")) continue
        if (href.contains("t.me/") || href.contains("whatsapp") || href.contains("telegram")) continue
        if (href.contains("hdstream4u.com") || href.contains("greenmountmotors.com")) continue
        val isWrapper =
            href.contains("hubdrive.", true) || href.contains("hubcdn.", true) ||
            href.contains("hblinks.co/archives/") || href.contains("4khdhub.one/") ||
            href.contains("hubcloud.ist/drive/") || href.contains("hubcloud.cx/drive/") ||
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
// MovieBox
// ═══════════════════════════════════════════
private suspend fun movieboxExtractRaw(q: StreamQuery): List<ScrapedMirror> {
    val results = try { mbSearch(q.title) } catch (e: Exception) { emptyList() }
    if (results.isEmpty()) return emptyList()
    val expectedType = if (q.type == "series") 2 else 1
    var best: MBSubject? = null
    var bestScore = 0
    for (s in results) {
        if (!titleMatches(q.title, s.title)) continue
        var score = 1
        if (q.year.isNotBlank() && s.year?.toString()?.contains(q.year) == true) score += 2
        if (s.type == expectedType) score += 2
        if (score > bestScore) { bestScore = score; best = s }
    }
    val subject = best ?: return emptyList()
    val streams = try { mbPlay(subject.subjectId, q.season, q.episode) } catch (e: Exception) { emptyList() }
    return streams.map {
        ScrapedMirror(
            quality = it.quality.ifBlank { "Auto" },
            mirror = "MovieBox",
            url = it.url,
            source = "MB",
            headers = it.signCookie?.let { c -> mapOf("Cookie" to c) }
        )
    }
}

// ═══════════════════════════════════════════
// Wrapper resolution — passes through to loadLinks
// ═══════════════════════════════════════════
suspend fun resolveWrapper(url: String): String? {
    if (url.contains("hubcloud.ist/drive/", true) || url.contains("hubcloud.cx/drive/", true)) return url
    if (url.contains("vcloud.", true)) return url
    if (url.contains("gdflix", true)) return url
    if (url.contains("greenmountmotors.com") || url.contains("hdstream4u.com")) return null

    val doc = cloudflareGetDoc(url)
    if (doc == null) {
        BCLog.e("resolveWrapper: fetch failed for $url"); return null
    }
    doc.selectFirst("a[href*='hubcloud.ist/drive/'], a[href*='hubcloud.cx/drive/']")?.attr("href")?.let { return it }
    doc.selectFirst("a[href*='vcloud.']")?.attr("href")?.let { return it }
    doc.selectFirst("a[href*='gdflix']")?.attr("href")?.let { return it }
    return null
}

// ═══════════════════════════════════════════
// Entry
// ═══════════════════════════════════════════
suspend fun scrapeAllSources(q: StreamQuery): List<ScrapedMirror> {
    BCLog.section("scrapeAllSources: ${q.title} (${q.year}) ${q.type} S${q.season}E${q.episode}")
    return coroutineScope {
        val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<ScrapedMirror>>>()

        if (Settings.isSrcVm()) jobs.add(async {
            try {
                val page = vegamoviesFindPage(q.title, q.year, q.type) ?: return@async emptyList()
                if (q.type == "series") vegamoviesExtractSeriesRaw(page, q.season, q.episode)
                else vegamoviesExtractMovieRaw(page)
            } catch (e: Exception) { BCLog.e("VM task failed: ${e.message}"); emptyList() }
        })
        if (Settings.isSrcMd()) jobs.add(async {
            try {
                val page = moviesdriveFindPage(q.title, q.year, q.type) ?: return@async emptyList()
                if (q.type == "series") moviesdriveExtractSeriesRaw(page, q.season, q.episode)
                else moviesdriveExtractMovieRaw(page)
            } catch (e: Exception) { BCLog.e("MD task failed: ${e.message}"); emptyList() }
        })
        if (Settings.isSrcHdh()) jobs.add(async {
            try {
                val page = hdhub4uFindPage(q.title, q.year, q.type) ?: return@async emptyList()
                hdhub4uExtractRaw(page)
            } catch (e: Exception) { BCLog.e("HDH task failed: ${e.message}"); emptyList() }
        })
        if (Settings.isSrcMovieBox()) jobs.add(async {
            try { movieboxExtractRaw(q) } catch (e: Exception) { BCLog.e("MB task failed: ${e.message}"); emptyList() }
        })

        if (jobs.isEmpty()) return@coroutineScope emptyList()
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
    val lower = url.lowercase()
    if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".m3u8")
        || lower.contains(".m3u8?") || lower.endsWith(".mpd")
        || lower.contains("drive.google.com")) return true

    val checkable = url.contains("hubcloud", true)
        || url.contains("gdflix", true)
        || url.contains("vcloud", true)
        || url.contains("gamerxyt", true)
    if (!checkable) return true

    return try {
        val html = app.get(url, timeout = 1500L).text
        val lower2 = html.lowercase()
        if (lower2.contains("just a moment") || lower2.contains("checking your browser")) return true
        lower2.contains("card-header") || lower2.contains("file size") ||
            lower2.contains("btn-success") || lower2.contains("btn-danger") ||
            lower2.contains("download") || lower2.contains("gdflix") ||
            lower2.contains("hubcloud") || lower2.contains("atob(")
    } catch (e: Exception) { false }
}
