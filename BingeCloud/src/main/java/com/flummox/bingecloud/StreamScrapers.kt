package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

// ── Data passed from load() to loadLinks() ──
data class StreamQuery(
    val title: String,
    val year: String,
    val type: String,          // "movie" or "series"
    val imdbId: String = "",
    val season: Int = 0,
    val episode: Int = 0
)

data class ScrapedMirror(
    val quality: String,       // "1080p" etc
    val mirror: String,        // "V-Cloud" / "HubCloud" / "G-Direct"
    val url: String,
    val source: String         // "VM" or "MD"
)

// ── Domain resolution (shared) ──
private suspend fun resolveDomain(key: String, fallback: String): String {
    return try {
        val json = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json").text
        val live = JSONObject(json).optString(key).trim()
        if (live.startsWith("http")) live else fallback
    } catch (e: Exception) {
        fallback
    }
}

// ── Title normalizer for matching ──
private fun normalize(s: String): String {
    return s.lowercase()
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
}

private fun titleMatches(a: String, b: String): Boolean {
    val na = normalize(a)
    val nb = normalize(b)
    if (na.isEmpty() || nb.isEmpty()) return false
    // Full containment OR high token overlap
    if (na.contains(nb) || nb.contains(na)) return true
    val ta = na.split(" ").toSet()
    val tb = nb.split(" ").toSet()
    val common = ta.intersect(tb)
    return common.size >= 2 && common.size.toFloat() / maxOf(ta.size, tb.size) >= 0.6f
}

// ═══════════════════════════════════════════════════════
//  VegaMovies scraper
// ═══════════════════════════════════════════════════════
private suspend fun vegamoviesFindPage(title: String, year: String, type: String): String? {
    val domain = resolveDomain("vegamovies", "https://vegamovies.mq")
    return try {
        val json = app.get("$domain/search.php?q=${URLEncoder.encode(title, "UTF-8")}").text
        val obj = JSONObject(json)
        val hits = obj.optJSONArray("hits") ?: return null
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
            if (score > bestScore) {
                bestScore = score
                bestPath = permalink
            }
        }
        bestPath?.let { if (it.startsWith("http")) it else "$domain$it" }
    } catch (e: Exception) {
        Log.e("BingeCloud", "VM search failed: ${e.message}")
        null
    }
}

private suspend fun vegamoviesExtractMovie(pageUrl: String): List<ScrapedMirror> {
    val out = mutableListOf<ScrapedMirror>()
    try {
        val doc = app.get(pageUrl).document
        val qualityHeaders = doc.select("h3, h4, h5").filter {
            val txt = it.text()
            txt.contains(Regex("""\d{3,4}[pP]""")) && !txt.contains("Zip", true)
        }
        for (header in qualityHeaders) {
            val qText = Regex("""(\d{3,4}[pP])""").find(header.text())?.value ?: continue
            val nextEl = header.nextElementSibling()
            val links = if (nextEl != null && nextEl.tagName() == "p") nextEl.select("a") else header.select("a")
            val dl = links.firstOrNull { it.text().contains("Download", true) }
                ?: links.firstOrNull { it.text().contains("V-Cloud", true) }
                ?: continue
            val inner = followAndFindMirrors(dl.attr("href"))
            inner.forEach { out.add(it.copy(quality = qText)) }
        }
    } catch (e: Exception) {
        Log.e("BingeCloud", "VM movie extract failed: ${e.message}")
    }
    return out
}

private suspend fun vegamoviesExtractSeries(pageUrl: String, season: Int, episode: Int): List<ScrapedMirror> {
    val out = mutableListOf<ScrapedMirror>()
    try {
        val doc = app.get(pageUrl).document
        // Find season header matching our target season
        val headers = doc.select("h3, h4, h5").filter {
            val txt = it.text()
            Regex("""(?:Season\s*|S)(\d+)""", RegexOption.IGNORE_CASE).containsMatchIn(txt) &&
                !txt.contains("Zip", true)
        }
        val targetHeader = headers.firstOrNull {
            val s = Regex("""(?:Season\s*|S)(\d+)""", RegexOption.IGNORE_CASE)
                .find(it.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
            s == season
        } ?: return out
        val qText = Regex("""(\d{3,4}[pP])""").find(targetHeader.text())?.value ?: "Unknown"
        val nextEl = targetHeader.nextElementSibling()
        val links = if (nextEl != null && nextEl.tagName() == "p") nextEl.select("a") else targetHeader.select("a")
        val dl = links.firstOrNull { it.text().contains("Download", true) } ?: return out
        // VegaMovies bundles whole seasons — the download link leads to a page with all episode mirrors
        val inner = followAndFindMirrors(dl.attr("href"))
        inner.forEach { out.add(it.copy(quality = qText)) }
    } catch (e: Exception) {
        Log.e("BingeCloud", "VM series extract failed: ${e.message}")
    }
    return out
}

// Given a download button URL, follow it and extract mirror links
private suspend fun followAndFindMirrors(downloadUrl: String): List<ScrapedMirror> {
    val out = mutableListOf<ScrapedMirror>()
    try {
        val doc = app.get(downloadUrl).document
        val candidates = doc.select("a, button, [onclick], [data-href]")
        for (elem in candidates) {
            var href = elem.attr("href").trim()
            if (href.isEmpty() || href == "#") href = elem.attr("data-href").trim()
            if (href.isEmpty() || href == "#") {
                val onclick = elem.attr("onclick")
                if (onclick.isNotEmpty()) {
                    href = Regex("""['"](https?://[^'"]+)['"]""").find(onclick)?.groupValues?.get(1) ?: ""
                }
            }
            if (href.isEmpty() || href.startsWith("#")) continue
            val text = elem.text().lowercase()
            when {
                text.contains("v-cloud") || text.contains("vcloud") -> out.add(ScrapedMirror("", "V-Cloud", href, "VM"))
                text.contains("hubcloud") -> out.add(ScrapedMirror("", "HubCloud", href, "VM"))
            }
        }
    } catch (e: Exception) {
        Log.e("BingeCloud", "followAndFindMirrors failed: ${e.message}")
    }
    return out
}

// ═══════════════════════════════════════════════════════
//  MoviesDrive scraper
// ═══════════════════════════════════════════════════════
private suspend fun moviesdriveFindPage(title: String, year: String, type: String): String? {
    val domain = resolveDomain("moviesdrive", "https://new4.moviesdrive.christmas")
    return try {
        val html = app.get("$domain/?s=${URLEncoder.encode(title, "UTF-8")}").text
        val doc = org.jsoup.Jsoup.parse(html)
        val cards = doc.select("#moviesGridMain > a")
        var bestUrl: String? = null
        var bestScore = 0
        for (a in cards) {
            val alt = a.selectFirst("p.poster-title")?.text()
                ?: a.selectFirst("img")?.attr("alt") ?: continue
            val href = a.attr("href")
            if (href.isEmpty()) continue
            if (!titleMatches(title, alt)) continue
            var score = 1
            if (year.isNotBlank() && alt.contains(year)) score += 2
            val lower = alt.lowercase()
            if (type == "series" && (lower.contains("season") || lower.contains("series"))) score += 2
            if (type == "movie" && (lower.contains("movie") || !lower.contains("season"))) score += 1
            if (score > bestScore) {
                bestScore = score
                bestUrl = href
            }
        }
        bestUrl
    } catch (e: Exception) {
        Log.e("BingeCloud", "MD search failed: ${e.message}")
        null
    }
}

private suspend fun moviesdriveExtractMovie(pageUrl: String): List<ScrapedMirror> {
    val out = mutableListOf<ScrapedMirror>()
    try {
        val doc = app.get(pageUrl).document
        // Quality headers: <h5>...1080p...</h5> followed by <a href="mdrive.lol/archive/NNNN/">
        val headers = doc.select("h5").filter {
            val txt = it.text()
            txt.contains(Regex("""\d{3,4}[pP]""")) && !txt.contains("Zip", true)
        }
        for (h in headers) {
            val qText = Regex("""(\d{3,4}[pP])""").find(h.text())?.value ?: continue
            val next = h.nextElementSibling()
            val archiveUrl = when {
                next != null && next.tagName() == "a" -> next.attr("href")
                next != null && next.tagName() == "h5" -> next.selectFirst("a")?.attr("href") ?: ""
                else -> h.selectFirst("a")?.attr("href") ?: ""
            }
            if (archiveUrl.isEmpty()) continue
            // Load archive page and get all hubcloud links
            val archive = app.get(archiveUrl).document
            archive.select("a[href*='hubcloud']").forEach { a ->
                out.add(ScrapedMirror(qText, "HubCloud", a.attr("href"), "MD"))
            }
        }
    } catch (e: Exception) {
        Log.e("BingeCloud", "MD movie extract failed: ${e.message}")
    }
    return out
}

private suspend fun moviesdriveExtractSeries(pageUrl: String, season: Int, episode: Int): List<ScrapedMirror> {
    val out = mutableListOf<ScrapedMirror>()
    try {
        val doc = app.get(pageUrl).document
        // Season headers: <h5><strong><span>Season N</span> ... <span>1080p...</span></strong></h5>
        // Followed by <h5><a href="mdrive.lol/archive/NNNN/">1080p Single Episode</a></h5>
        val seasonHeaders = doc.select("h5").filter {
            val txt = it.text()
            Regex("""Season\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(txt) &&
                Regex("""\d{3,4}[pP]""").containsMatchIn(txt)
        }

        for (sh in seasonHeaders) {
            val hTxt = sh.text()
            val s = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(hTxt)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            if (s != season) continue
            val qText = Regex("""(\d{3,4}[pP])""").find(hTxt)?.value ?: "Unknown"

            // Walk forward for the matching archive link
            var cursor: Element? = sh.nextElementSibling()
            var archiveUrl: String? = null
            var hops = 0
            while (cursor != null && hops < 6) {
                val txt = cursor.text()
                if (txt.contains("Single Episode", true)) {
                    archiveUrl = cursor.selectFirst("a")?.attr("href")
                    if (!archiveUrl.isNullOrEmpty()) break
                }
                // Stop if we hit the next season header
                if (Regex("""Season\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(txt) &&
                    cursor !== sh) break
                cursor = cursor.nextElementSibling()
                hops++
            }
            if (archiveUrl.isNullOrEmpty()) continue

            // Archive page: <h5>Ep## – ...</h5> + <a href="hubcloud.ist/drive/XXXX">HubCloud</a>
            val archive = app.get(archiveUrl).document
            val epHeaders = archive.select("h5").filter {
                Regex("""Ep\s*0*(\d+)""", RegexOption.IGNORE_CASE).containsMatchIn(it.text())
            }
            for (eph in epHeaders) {
                val epNum = Regex("""Ep\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(eph.text())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
                if (epNum != episode) continue
                // Find hubcloud link after this header
                var next = eph.nextElementSibling()
                var step = 0
                while (next != null && step < 3) {
                    val link = next.selectFirst("a[href*='hubcloud']")
                    if (link != null) {
                        out.add(ScrapedMirror(qText, "HubCloud", link.attr("href"), "MD"))
                        break
                    }
                    if (next.tagName() == "h5" &&
                        Regex("""Ep\s*0*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(next.text())) break
                    next = next.nextElementSibling()
                    step++
                }
                break
            }
        }
    } catch (e: Exception) {
        Log.e("BingeCloud", "MD series extract failed: ${e.message}")
    }
    return out
}

// ═══════════════════════════════════════════════════════
//  Public entry
// ═══════════════════════════════════════════════════════
suspend fun scrapeAllSources(q: StreamQuery): List<ScrapedMirror> {
    val results = mutableListOf<ScrapedMirror>()

    // VegaMovies
    try {
        val vmPage = vegamoviesFindPage(q.title, q.year, q.type)
        if (vmPage != null) {
            val vmLinks = if (q.type == "series")
                vegamoviesExtractSeries(vmPage, q.season, q.episode)
            else
                vegamoviesExtractMovie(vmPage)
            results.addAll(vmLinks)
        }
    } catch (e: Exception) {
        Log.e("BingeCloud", "VM scrape failed: ${e.message}")
    }

    // MoviesDrive
    try {
        val mdPage = moviesdriveFindPage(q.title, q.year, q.type)
        if (mdPage != null) {
            val mdLinks = if (q.type == "series")
                moviesdriveExtractSeries(mdPage, q.season, q.episode)
            else
                moviesdriveExtractMovie(mdPage)
            results.addAll(mdLinks)
        }
    } catch (e: Exception) {
        Log.e("BingeCloud", "MD scrape failed: ${e.message}")
    }

    Log.d("BingeCloud", "Scraped ${results.size} mirrors for ${q.title} (${q.year}) ${q.type} S${q.season}E${q.episode}")
    return results
}
