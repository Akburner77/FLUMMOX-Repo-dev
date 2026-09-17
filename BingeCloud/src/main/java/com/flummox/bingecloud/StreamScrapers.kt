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
import okhttp3.MediaType.Companion.toMediaTypeOrNull

// ═══════════════════════════════════════════
// ── DATA MODELS ──
// ═══════════════════════════════════════════
data class StreamQuery(
    val title: String,
    val year: String,
    val type: String,
    val imdbId: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val nextSeason: Int = 0,
    val nextEpisode: Int = 0
)

data class ScrapedMirror(
    val quality: String,
    val mirror: String,
    val url: String,
    val source: String,
    val headers: Map<String, String>? = null
)

// ═══════════════════════════════════════════
// ── DOMAIN RESOLVER (24h cached) ──
// ═══════════════════════════════════════════
private const val DOMAIN_JSON_URL = "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"
private const val DOMAIN_CACHE_TTL = 24 * 60 * 60 * 1000L

private suspend fun resolveDomain(key: String, fallback: String): String {
    val cached = BCCache.get(DOMAIN_JSON_URL, DOMAIN_CACHE_TTL)
    if (cached != null) {
        return try {
            val live = JSONObject(cached).optString(key).trim()
            if (live.startsWith("http")) live else fallback
        } catch (_: Exception) { fallback }
    }
    return try {
        val json = app.get(DOMAIN_JSON_URL).text
        BCCache.put(DOMAIN_JSON_URL, json)
        val live = JSONObject(json).optString(key).trim()
        if (live.startsWith("http")) live else fallback
    } catch (e: Exception) {
        BCLog.e("resolveDomain($key): ${e.message}")
        fallback
    }
}

// ═══════════════════════════════════════════
// ── TITLE MATCHING ──
// ═══════════════════════════════════════════
private val STOP_WORDS = setOf(
    "download", "the", "a", "an", "of", "and", "or", "in", "on", "at", "to",
    "full", "movie", "series", "episode", "episodes", "season", "complete",
    "watch", "online", "free", "hd", "web", "webdl", "webrip", "bluray",
    "hdrip", "dubbed", "dual", "audio", "hindi", "english", "korean",
    "japanese", "tamil", "telugu", "org", "esubs", "multi"
)

private fun stripQualifiers(s: String): String {
    val cleaned = s.lowercase()
        .replace(Regex("""\b(season|s)\s*\d+\b"""), " ")
        .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
        .replace(Regex("""[^a-z0-9 ]"""), " ")
    val tokens = cleaned.split(" ").map { it.trim() }
        .filter { it.isNotBlank() && it !in STOP_WORDS }
    return tokens.joinToString(" ")
}

fun titleMatches(a: String, b: String): Boolean {
    val sa = stripQualifiers(a)
    val sb = stripQualifiers(b)
    if (sa.isEmpty() || sb.isEmpty()) return false
    if (sa == sb) return true
    val ta = sa.split(" ").filter { it.isNotBlank() }.toSet()
    val tb = sb.split(" ").filter { it.isNotBlank() }.toSet()
    if (ta.isEmpty() || tb.isEmpty()) return false
    val common = ta.intersect(tb)
    if (common.isEmpty()) return false
    if (ta.size == 1 || tb.size == 1) {
        return common.size == minOf(ta.size, tb.size) && common.size == 1
    }
    val queryInCandidate = common.size.toFloat() / ta.size
    val candidateInQuery = common.size.toFloat() / tb.size
    return queryInCandidate >= 0.6f && candidateInQuery >= 0.6f
}

private fun extractSeasons(title: String): List<Int> =
    Regex("""(?i)\bseason\s*0*(\d+)\b""").findAll(title)
        .mapNotNull { it.groupValues[1].toIntOrNull() }.toList()

private fun pageHasSeason(title: String, targetSeason: Int): Boolean {
    if (targetSeason <= 0) return true
    val seasons = extractSeasons(title)
    if (seasons.isEmpty()) return true
    return seasons.contains(targetSeason)
}

// ═══════════════════════════════════════════
// ── HTTP HELPERS ──
// ═══════════════════════════════════════════
private suspend fun cachedGet(url: String): String? {
    BCCache.get(url)?.let { return it }
    return try {
        val body = app.get(url).text
        BCCache.put(url, body)
        body
    } catch (e: Exception) {
        BCLog.e("GET failed $url: ${e.message}")
        null
    }
}

private suspend fun safeGet(url: String): org.jsoup.nodes.Document? {
    val html = cachedGet(url) ?: return null
    return try { Jsoup.parse(html, url) } catch (e: Exception) {
        BCLog.e("Jsoup parse failed: ${e.message}"); null
    }
}

// ═══════════════════════════════════════════
// ── VegaMovies ──
// ═══════════════════════════════════════════
private suspend fun vegamoviesFindPage(title: String, year: String, type: String, season: Int): String? {
    val domain = resolveDomain("vegamovies", "https://vegamovies.mq")
    BCLog.d("VM: searching '$title' S$season")
    return try {
        val json = cachedGet("$domain/search.php?q=${URLEncoder.encode(title, "UTF-8")}") ?: return null
        val hits = JSONObject(json).optJSONArray("hits") ?: return null
        var bestPath: String? = null
        var bestScore = 0
        for (i in 0 until hits.length()) {
            val doc = hits.getJSONObject(i).optJSONObject("document") ?: continue
            val postTitle = doc.optString("post_title")
            val permalink = doc.optString("permalink")
            if (postTitle.isEmpty() || permalink.isEmpty()) continue
            if (!titleMatches(title, postTitle)) continue
            if (type == "series" && !pageHasSeason(postTitle, season)) {
                BCLog.d("VM: skip wrong season: $postTitle")
                continue
            }
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
// ── MoviesDrive ──
// ═══════════════════════════════════════════
private suspend fun moviesdriveFindPage(title: String, year: String, type: String, season: Int): String? {
    val domain = resolveDomain("moviesdrive", "https://new4.moviesdrive.christmas")
    BCLog.d("MD: searching '$title' S$season")
    return try {
        val url = "$domain/wp-json/wp/v2/posts?search=${URLEncoder.encode(title, "UTF-8")}&per_page=20"
        val json = cachedGet(url) ?: return null
        val arr = try { JSONArray(json) } catch (e: Exception) { return null }
        BCLog.d("MD: REST returned ${arr.length()} posts")
        var bestUrl: String? = null
        var bestScore = 0
        for (i in 0 until arr.length()) {
            val post = arr.optJSONObject(i) ?: continue
            val rendered = post.optJSONObject("title")?.optString("rendered") ?: continue
            val postTitle = Jsoup.parse(rendered).text()
            val link = post.optString("link")
            if (link.isEmpty() || !titleMatches(title, postTitle)) continue
            if (type == "series" && !pageHasSeason(postTitle, season)) {
                BCLog.d("MD: skip wrong season: $postTitle")
                continue
            }
            var score = 1
            if (year.isNotBlank() && postTitle.contains(year)) score += 2
            val lower = postTitle.lowercase()
            if (type == "series" && (lower.contains("season") || lower.contains("series"))) score += 2
            if (type == "movie" && (lower.contains("full movie") || !lower.contains("season"))) score += 1
            if (score > bestScore) { bestScore = score; bestUrl = link }
        }
        bestUrl
    } catch (e: Exception) {
        BCLog.e("MD search failed: ${e.message}"); null
    }
}

private suspend fun moviesdriveExtractMovieRaw(pageUrl: String): List<ScrapedMirror> = coroutineScope {
    val doc = safeGet(pageUrl) ?: return@coroutineScope emptyList()
    val allH5 = doc.select("h5")
    val jobs = mutableListOf<Pair<String, String>>()
    for (i in allH5.indices) {
        val q = Regex("""(\d{3,4}[pP])""").find(allH5[i].text())?.value ?: continue
        for (j in i + 1 until minOf(i + 4, allH5.size)) {
            val anchor = allH5[j].selectFirst("a[href*='mdrive.lol/archive/'], a[href*='moviesdrives']")
                ?: allH5[j].selectFirst("a[href*='archive']")
                ?: continue
            val archiveUrl = anchor.attr("href")
            if (archiveUrl.isNotEmpty()) jobs.add(q to archiveUrl)
            break
        }
    }
    val results = jobs.map { (q, url) -> async { extractFromArchivePage(url, q) } }.awaitAll().flatten()
    BCLog.d("MD: extracted ${results.size} mirrors")
    results
}

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
    val results = jobs.map { (q, url) -> async { extractFromArchivePage(url, q, episode) } }.awaitAll().flatten()
    BCLog.d("MD: series S${season}E${episode} → ${results.size}")
    results
}

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
                for (a in allH5[j].select("a[href]")) {
                    val href = a.attr("href"); val label = a.text().lowercase()
                    when {
                        href.contains("hubcloud", true) -> out.add(ScrapedMirror(quality, "HCloud", href, "MD"))
                        href.contains("gdflix", true) || label.contains("gdflix") -> out.add(ScrapedMirror(quality, "GDFlix", href, "MD"))
                    }
                }
                if (allH5[j].text().contains(Regex("""EP\s*0*\d+""", RegexOption.IGNORE_CASE))) break
            }
        } else {
            for (a in h.select("a[href]")) {
                val href = a.attr("href"); val label = a.text().lowercase()
                when {
                    href.contains("hubcloud", true) -> out.add(ScrapedMirror(quality, "HCloud", href, "MD"))
                    href.contains("gdflix", true) || label.contains("gdflix") -> out.add(ScrapedMirror(quality, "GDFlix", href, "MD"))
                }
            }
        }
    }
    return out
}

// ═══════════════════════════════════════════
// ── HDhub4u ──
// ═══════════════════════════════════════════
private suspend fun hdhub4uFindPage(title: String, year: String, type: String, season: Int): String? {
    val domain = resolveDomain("hdhub4u", "https://new5.hdhub4u.cl")
    return try {
        val html = cachedGet("$domain/?s=${URLEncoder.encode(title, "UTF-8")}") ?: return null
        val doc = Jsoup.parse(html)
        val cards = doc.select("li.thumb")
        var bestUrl: String? = null
        var bestScore = 0
        for (card in cards) {
            val alt = card.selectFirst("figcaption p")?.text()
                ?: card.selectFirst("img")?.attr("alt") ?: continue
            val href = card.selectFirst("a")?.attr("href") ?: continue
            if (href.isEmpty() || !titleMatches(title, alt)) continue
            if (type == "series" && !pageHasSeason(alt, season)) {
                BCLog.d("HDH: skip wrong season: $alt")
                continue
            }
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
        out.add(ScrapedMirror(q, "HDhub", href, "HDH"))
    }
    BCLog.d("HDH: extracted ${out.size}")
    return out
}

// ═══════════════════════════════════════════
// ── MovieBox ──
// ═══════════════════════════════════════════
private suspend fun movieboxExtractRaw(q: StreamQuery): List<ScrapedMirror> = coroutineScope {
    val results = try { mbSearch(q.title) } catch (e: Exception) { emptyList() }
    if (results.isEmpty()) return@coroutineScope emptyList()
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
    val subject = best ?: return@coroutineScope emptyList()

    val languages = try { mbLanguages(subject.subjectId) } catch (e: Exception) {
        listOf(subject.subjectId to "Original")
    }

    val allStreams = languages.map { (sid, lang) ->
        async { try { mbPlay(sid, q.season, q.episode, lang) } catch (e: Exception) { emptyList() } }
    }.awaitAll().flatten()

    BCLog.d("MB total: ${allStreams.size} streams / ${languages.size} langs")

    allStreams
        .distinctBy { it.realUrl }
        .filter { it.durationSec == 0L || it.durationSec >= 120L }
        .map {
            ScrapedMirror(
                quality = it.quality.ifBlank { "Auto" },
                mirror = prettyAudio(it.audio ?: "MovieBox"),
                url = it.realUrl,
                source = "MB",
                headers = it.signCookie?.let { c -> mapOf("Cookie" to c) }
            )
        }
}

private fun prettyAudio(raw: String): String {
    val l = raw.lowercase()
    return when {
        l.contains("original") -> "Original"
        l.contains("hindi") -> "Hindi"
        l.contains("esla") || l.contains("spanish") -> "Spanish"
        l.contains("ptbr") || l.contains("portug") -> "Portuguese"
        l.contains("english") -> "English"
        l.contains("tamil") -> "Tamil"
        l.contains("telugu") -> "Telugu"
        l.contains("kannada") -> "Kannada"
        l.contains("malayalam") -> "Malayalam"
        l.contains("bengali") -> "Bengali"
        else -> raw.replace(Regex("""(?i)\s*\(?\s*(dub|audio)\s*\)?"""), " ").trim().ifBlank { "Auto" }
    }
}

// ═══════════════════════════════════════════
// ── AniKoto ──
// ═══════════════════════════════════════════
private const val ANIKOTO_DOMAIN = "https://anikototv.to"
private const val ANIKOTO_API_SITE = "https://anikotoapi.site"
private const val ANIKOTO_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

private val anikotoBrowserHeaders = mapOf(
    "User-Agent" to ANIKOTO_UA,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.5"
)

private fun anikotoAjaxHeaders(referer: String): Map<String, String> = mapOf(
    "User-Agent" to ANIKOTO_UA,
    "X-Requested-With" to "XMLHttpRequest",
    "Accept" to "application/json, text/javascript, */*; q=0.01",
    "Referer" to referer
)

private fun anikotoResultString(json: String): String = try {
    val root = JSONObject(json)
    if (root.optInt("status") == 200) root.optString("result") else ""
} catch (_: Exception) { "" }

private fun anikotoResultUrl(json: String): String? = try {
    val root = JSONObject(json)
    if (root.optInt("status") == 200)
        root.optJSONObject("result")?.optString("url")?.takeIf { it.isNotBlank() }
    else null
} catch (_: Exception) { null }

private fun anikotoScore(query: String, candidate: String): Int {
    val q = query.lowercase().trim()
    val c = candidate.lowercase().trim()
    if (q.isEmpty() || c.isEmpty()) return 0
    if (q == c) return 100
    if (c.startsWith(q)) return 30
    if (q.startsWith(c)) return 25
    val qw = q.split(Regex("\\s+")).filter { it.isNotBlank() }
    val cw = c.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (qw.size <= 2) {
        return if (cw.take(qw.size).joinToString(" ") == q) 20 else 0
    }
    val common = qw.intersect(cw.toSet()).size
    if (common == 0) return 0
    val ratio = common.toFloat() / maxOf(qw.size, cw.size)
    return (ratio * 20).toInt()
}

private data class AnikotoSeries(val url: String, val title: String, val animeId: String, val slug: String)

private suspend fun anikotoFindSeries(title: String): AnikotoSeries? {
    val query = URLEncoder.encode(title, "UTF-8")
    val doc = try {
        app.get("$ANIKOTO_DOMAIN/filter?keyword=$query", headers = anikotoBrowserHeaders).document
    } catch (e: Exception) {
        BCLog.e("AniKoto search failed: ${e.message}"); return null
    }
    val cards = doc.select("div.ani.items > div.item")
    if (cards.isEmpty()) { BCLog.d("AniKoto: 0 cards"); return null }

    var best: AnikotoSeries? = null
    var bestScore = 0
    for (card in cards) {
        val titleEl = card.selectFirst("a.name.d-title")
            ?: card.selectFirst("a[title]")
            ?: card.selectFirst("a[href*='/watch/']") ?: continue
        var href = titleEl.attr("href")
        if (href.isBlank()) href = card.selectFirst("div.poster a, a")?.attr("href") ?: ""
        val candTitle = titleEl.text().trim().ifBlank { titleEl.attr("title").trim() }
        if (href.isBlank() || candTitle.isBlank()) continue
        val score = anikotoScore(title, candTitle)
        if (score > bestScore) {
            bestScore = score
            val full = if (href.startsWith("http")) href else "$ANIKOTO_DOMAIN$href"
            val slug = full.substringAfterLast("/").trimEnd('/')
            best = AnikotoSeries(full, candTitle, "", slug)
        }
    }
    if (best == null) { BCLog.d("AniKoto: no match"); return null }

    // fetch animeId from series page
    val seriesHtml = try {
        app.get(best.url, headers = anikotoBrowserHeaders).text
    } catch (_: Exception) { "" }
    val seriesDoc = Jsoup.parse(seriesHtml, best.url)
    val animeId = seriesDoc.selectFirst("#watch-main")?.attr("data-id")?.takeIf { it.isNotBlank() }
        ?: seriesDoc.selectFirst("[data-id]")?.attr("data-id")?.takeIf { it.isNotBlank() }
        ?: Regex("""data-id=["'](\d+)["']""").find(seriesHtml)?.groupValues?.get(1)
        ?: ""
    best = best.copy(animeId = animeId)
    BCLog.d("AniKoto matched '${best.title}' (score=$bestScore, animeId=$animeId, slug=${best.slug})")
    return best
}

private suspend fun anikotoGetServerIds(series: AnikotoSeries, episode: Int): String? {
    // PRIMARY: try anikotoapi.site first (working third-party API)
    val apiEpisodesUrl = "$ANIKOTO_API_SITE/episodes/${series.animeId}"
    try {
        val apiRes = app.get(apiEpisodesUrl, headers = anikotoBrowserHeaders).text
        BCLog.d("AniKoto API ep list: len=${apiRes.length} head=${apiRes.take(200)}")
        val apiJson = try { JSONObject(apiRes) } catch (_: Exception) { null }
        if (apiJson != null) {
            val arr = apiJson.optJSONArray("episodes") ?: apiJson.optJSONArray("data")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val ep = arr.optJSONObject(i) ?: continue
                    val num = ep.optInt("number", ep.optInt("episode", -1))
                    if (num == episode) {
                        val ids = ep.optString("serverIds").ifBlank { ep.optString("servers") }
                        if (ids.isNotBlank()) {
                            BCLog.d("AniKoto API hit: ep=$num ids=${ids.take(40)}")
                            return ids
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        BCLog.d("AniKoto API episodes failed: ${e.message}")
    }

    // FALLBACK: direct scrape
    if (series.animeId.isBlank()) return null
    val listJson = try {
        anikotoResultString(app.get("$ANIKOTO_DOMAIN/ajax/episode/list/${series.animeId}", headers = anikotoAjaxHeaders(series.url)).text)
    } catch (e: Exception) {
        BCLog.e("AniKoto ep list failed: ${e.message}"); return null
    }
    if (listJson.isBlank()) return null
    val listDoc = Jsoup.parse(listJson)
    val allEp = listDoc.select("a[data-ids]")
    if (allEp.isEmpty()) return null
    val epEl = allEp.firstOrNull { it.attr("data-num").toIntOrNull() == episode }
        ?: allEp.firstOrNull() ?: return null
    return epEl.attr("data-ids").takeIf { it.isNotBlank() }
}

private suspend fun anikotoResolvePlayerUrl(linkId: String, referer: String): String? {
    val encoded = android.net.Uri.encode(linkId)
    val endpoints = listOf(
        "$ANIKOTO_DOMAIN/ajax/server/$encoded",
        "$ANIKOTO_DOMAIN/ajax/server/$encoded/",
        "$ANIKOTO_DOMAIN/ajax/server?id=$encoded",
        "$ANIKOTO_DOMAIN/ajax/server?linkId=$encoded",
        "$ANIKOTO_DOMAIN/ajax/links/$encoded",
        "$ANIKOTO_DOMAIN/ajax/sources/$encoded",
        "$ANIKOTO_DOMAIN/ajax/embed/$encoded",
        "$ANIKOTO_DOMAIN/ajax/get-server?id=$encoded"
    )
    for (ep in endpoints) {
        try {
            val raw = app.get(ep, headers = anikotoAjaxHeaders(referer)).text
            if (raw.contains("\"message\"")) continue
            val viaResult = anikotoResultUrl(raw)
            if (viaResult != null) {
                BCLog.d("AniKoto OK via $ep")
                return viaResult
            }
            try {
                val obj = JSONObject(raw)
                obj.optString("url").takeIf { it.isNotBlank() }?.let { return it }
                obj.optJSONObject("data")?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }
    // POST fallback
    for (body in listOf("id=$encoded", "linkId=$encoded", "server=$encoded")) {
        try {
            val raw = app.post(
                "$ANIKOTO_DOMAIN/ajax/server",
                headers = anikotoAjaxHeaders(referer).toMutableMap().apply { put("Content-Type", "application/x-www-form-urlencoded") },
                requestBody = okhttp3.RequestBody.create("application/x-www-form-urlencoded".toMediaTypeOrNull(), body)
            ).text
            if (raw.contains("\"message\"")) continue
            val viaResult = anikotoResultUrl(raw)
            if (viaResult != null) return viaResult
        } catch (_: Exception) {}
    }
    return null
}

private suspend fun anikotoExtractRaw(q: StreamQuery): List<ScrapedMirror> {
    val series = anikotoFindSeries(q.title) ?: return emptyList()
    val serverIds = anikotoGetServerIds(series, q.episode) ?: run {
        BCLog.d("AniKoto: no serverIds"); return emptyList()
    }
    val listJson = try {
        anikotoResultString(app.get("$ANIKOTO_DOMAIN/ajax/server/list?servers=${android.net.Uri.encode(serverIds)}", headers = anikotoAjaxHeaders(series.url)).text)
    } catch (e: Exception) {
        BCLog.e("AniKoto server list failed: ${e.message}"); return emptyList()
    }
    if (listJson.isBlank()) return emptyList()
    val doc = Jsoup.parse(listJson)

    val entries = mutableListOf<Pair<String, String>>()
    for (block in doc.select("div.type")) {
        val sType = block.attr("data-type").ifBlank { "sub" }
        for (li in block.select("li")) {
            val linkId = listOf("data-link-id", "data-id", "data-sv", "data-server", "data-embed")
                .firstNotNullOfOrNull { li.attr(it).takeIf { v -> v.isNotBlank() } }
                ?: continue
            val name = li.text().trim().ifBlank { "Server" }
            entries.add(linkId to "AniKoto ${anikotoServerTypeLabel(sType)} $name")
        }
    }
    BCLog.d("AniKoto: ${entries.size} servers")
    if (entries.isEmpty()) return emptyList()

    val mirrors = coroutineScope {
        entries.map { (linkId, label) ->
            async {
                val playerUrl = anikotoResolvePlayerUrl(linkId, series.url) ?: return@async null
                val full = when {
                    playerUrl.startsWith("//") -> "https:$playerUrl"
                    playerUrl.startsWith("/") -> "$ANIKOTO_DOMAIN$playerUrl"
                    else -> playerUrl
                }
                ScrapedMirror("Auto", label, full, "ANIKOTO")
            }
        }.awaitAll().filterNotNull()
    }
    BCLog.d("AniKoto: ${mirrors.size} mirrors")
    return mirrors
}

// ═══════════════════════════════════════════
// ── Wrapper resolution ──
// ═══════════════════════════════════════════
suspend fun resolveWrapper(url: String): String? {
    if (url.contains("hubcloud.ist/drive/", true) || url.contains("hubcloud.cx/drive/", true)) return url
    if (url.contains("vcloud.", true)) return url
    if (url.contains("gdflix", true)) return url
    if (url.contains("greenmountmotors.com") || url.contains("hdstream4u.com")) return null
    val doc = cloudflareGetDoc(url) ?: return null
    doc.selectFirst("a[href*='hubcloud.ist/drive/'], a[href*='hubcloud.cx/drive/']")?.attr("href")?.let { return it }
    doc.selectFirst("a[href*='vcloud.']")?.attr("href")?.let { return it }
    doc.selectFirst("a[href*='gdflix']")?.attr("href")?.let { return it }
    return null
}

// ═══════════════════════════════════════════
// ── Entry point ──
// ═══════════════════════════════════════════
private const val PER_SOURCE_TIMEOUT_MS = 25000L

suspend fun scrapeAllSources(q: StreamQuery): List<ScrapedMirror> {
    BCLog.section("scrapeAllSources: ${q.title} (${q.year}) ${q.type} S${q.season}E${q.episode}")
    return coroutineScope {
        val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<ScrapedMirror>?>>()

        if (Settings.isSrcVm()) jobs.add(async {
            kotlinx.coroutines.withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                try {
                    com.flummox.bingecore.SpeedBooster.deduped("vm:${q.cacheKey()}") {
                        val page = vegamoviesFindPage(q.title, q.year, q.type, q.season) ?: return@deduped emptyList()
                        if (q.type == "series") vegamoviesExtractSeriesRaw(page, q.season, q.episode)
                        else vegamoviesExtractMovieRaw(page)
                    }
                } catch (e: Exception) { BCLog.e("VM task failed: ${e.message}"); emptyList() }
            } ?: run { BCLog.d("VM timeout"); emptyList() }
        })
        if (Settings.isSrcMd()) jobs.add(async {
            kotlinx.coroutines.withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                try {
                    com.flummox.bingecore.SpeedBooster.deduped("md:${q.cacheKey()}") {
                        val page = moviesdriveFindPage(q.title, q.year, q.type, q.season) ?: return@deduped emptyList()
                        if (q.type == "series") moviesdriveExtractSeriesRaw(page, q.season, q.episode)
                        else moviesdriveExtractMovieRaw(page)
                    }
                } catch (e: Exception) { BCLog.e("MD task failed: ${e.message}"); emptyList() }
            } ?: run { BCLog.d("MD timeout"); emptyList() }
        })
        if (Settings.isSrcHdh()) jobs.add(async {
            kotlinx.coroutines.withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                try {
                    com.flummox.bingecore.SpeedBooster.deduped("hdh:${q.cacheKey()}") {
                        val page = hdhub4uFindPage(q.title, q.year, q.type, q.season) ?: return@deduped emptyList()
                        hdhub4uExtractRaw(page)
                    }
                } catch (e: Exception) { BCLog.e("HDH task failed: ${e.message}"); emptyList() }
            } ?: run { BCLog.d("HDH timeout"); emptyList() }
        })
        if (Settings.isSrcGogo()) jobs.add(async {
            kotlinx.coroutines.withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                try { gogoExtractRaw(q) } catch (e: Exception) { BCLog.e("Gogo task failed: ${e.message}"); emptyList() }
            } ?: run { BCLog.d("Gogo timeout"); emptyList() }
        })
        if (Settings.isSrcMovieBox()) jobs.add(async {
            kotlinx.coroutines.withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                try { movieboxExtractRaw(q) } catch (e: Exception) { BCLog.e("MB task failed: ${e.message}"); emptyList() }
            } ?: run { BCLog.d("MB timeout"); emptyList() }
        })
        if (Settings.isSrcAnikoto()) jobs.add(async {
            kotlinx.coroutines.withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                try {
                    com.flummox.bingecore.SpeedBooster.deduped("anikoto:${q.cacheKey()}") {
                        anikotoExtractRaw(q)
                    }
                } catch (e: Exception) { BCLog.e("AniKoto task failed: ${e.message}"); emptyList() }
            } ?: run { BCLog.d("AniKoto timeout"); emptyList() }
        })

        if (jobs.isEmpty()) return@coroutineScope emptyList()
        val all = jobs.awaitAll().filterNotNull().flatten()
        val vm = all.count { it.source == "VM" }
        val md = all.count { it.source == "MD" }
        val hdh = all.count { it.source == "HDH" }
        val mb = all.count { it.source == "MB" }
        val gogo = all.count { it.source == "GOGO" }
        val ak = all.count { it.source == "ANIKOTO" }
        BCLog.d("sources done — VM=$vm MD=$md HDH=$hdh MB=$mb GOGO=$gogo ANIKOTO=$ak total=${all.size}")
        all
    }
}
