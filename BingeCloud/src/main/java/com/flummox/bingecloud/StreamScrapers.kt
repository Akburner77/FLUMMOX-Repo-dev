package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.nodes.Element
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
        fallback
    }
}

private fun normalize(s: String): String {
    return s.lowercase()
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
}

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

// ═══════════════════════════════════════════
//  VegaMovies
// ═══════════════════════════════════════════
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
            val inner = followAndFindMirrors(dl.attr("href"), "VM")
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
        val inner = followAndFindMirrors(dl.attr("href"), "VM")
        inner.forEach { out.add(it.copy(quality = qText)) }
    } catch (e: Exception) {
        Log.e("BingeCloud", "VM series extract failed: ${e.message}")
    }
    return out
}

private suspend fun followAndFindMirrors(downloadUrl: String, sourceTag: String): List<ScrapedMirror> {
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
                text.contains("v-cloud") || text.contains("vcloud") ->
                    out.add(ScrapedMirror("", "V-Cloud", href, sourceTag))
                text.contains("hubcloud") ->
                    out.add(ScrapedMirror("", "HubCloud", href, sourceTag))
            }
        }
    } catch (e: Exception) {
        Log.e("BingeCloud", "followAndFindMirrors failed: ${e.message}")
    }
    return out
}

// ═══════════════════════════════════════════
//  MoviesDrive
// ═══════════════════════════════════════════
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
        val headers = doc.select("h5").filter {
            val txt = it.text()
            txt.contains(Regex("""\d{3,4}[pP]""")) && !txt.contains("Zip", true)
        }

        val pairs = mutableListOf<Pair<String, String>>()
        for (h in headers) {
            val qText = Regex("""(\d{3,4}[pP])""").find(h.text())?.value ?: continue
            var cursor = h.nextElementSibling()
            var steps = 0
            var archiveUrl: String? = null
            while (cursor != null && steps < 4) {
                val link = cursor.selectFirst("a[href*='archive']")
                    ?: cursor.selectFirst("a[href*='mdrive']")
                if (link != null && link.attr("href").isNotEmpty()) {
                    archiveUrl = link.attr("href")
                    break
                }
                if (cursor.tagName() == "h5" &&
                    Regex("""\d{3,4}[pP]""").containsMatchIn(cursor.text())) break
                cursor = cursor.nextElementSibling()
                steps++
            }
            if (!archiveUrl.isNullOrEmpty()) pairs.add(qText to archiveUrl)
        }

        Log.d("BingeCloud", "MD movie: ${pairs.size} archives to fetch")

        coroutineScope {
            pairs.map { pair ->
                async {
                    val quality = pair.first
                    val archiveUrl = pair.second
                    try {
                        val archive = app.get(archiveUrl).document
                        val hubs = archive.select("a[href*='hubcloud'], a[href*='gdflix'], a[href*='gdirect']")
                        hubs.forEach { a ->
                            val href = a.attr("href")
                            val label = when {
                                href.contains("hubcloud", true) -> "HubCloud"
                                href.contains("gdflix", true) -> "GDFlix"
                                href.contains("gdirect", true) -> "G-Direct"
                                else -> "Direct"
                            }
                            out.add(ScrapedMirror(quality, label, href, "MD"))
                        }
                    } catch (e: Exception) {
                        Log.e("BingeCloud", "MD archive failed: ${e.message}")
                    }
                }
            }.awaitAll()
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
        val allH5 = doc.select("h5")

        val pairs = mutableListOf<Pair<String, String>>()
        for (i in allH5.indices) {
            val h = allH5[i]
            val txt = h.text()
            val sMatch = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(txt) ?: continue
            val s = sMatch.groupValues[1].toIntOrNull() ?: continue
            if (s != season) continue
            val qText = Regex("""(\d{3,4}[pP])""").find(txt)?.value ?: continue

            var j = i + 1
            while (j < allH5.size && j < i + 4) {
                val next = allH5[j]
                val nextTxt = next.text()
                if (Regex("""Season\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(nextTxt) &&
                    nextTxt.contains(Regex("""\d{3,4}[pP]"""))) break
                if (nextTxt.contains("Single Episode", true)) {
                    val href = next.selectFirst("a")?.attr("href") ?: ""
                    if (href.isNotEmpty()) pairs.add(qText to href)
                    break
                }
                j++
            }
        }

        Log.d("BingeCloud", "MD series S${season}E${episode}: ${pairs.size} archives")

        coroutineScope {
            pairs.map { pair ->
                async {
                    val quality = pair.first
                    val archiveUrl = pair.second
                    try {
                        val archive = app.get(archiveUrl).document
                        val epHeaders = archive.select("h5").filter {
                            Regex("""Ep\s*0*(\d+)""", RegexOption.IGNORE_CASE).containsMatchIn(it.text())
                        }
                        for (eph in epHeaders) {
                            val epNum = Regex("""Ep\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                                .find(eph.text())?.groupValues?.get(1)?.toIntOrNull() ?: continue
                            if (epNum != episode) continue
                            var next = eph.nextElementSibling()
                            var step = 0
                            while (next != null && step < 3) {
                                val link = next.selectFirst("a[href*='hubcloud'], a[href*='gdflix'], a[href*='gdirect']")
                                if (link != null) {
                                    val href = link.attr("href")
                                    val label = when {
                                        href.contains("hubcloud", true) -> "HubCloud"
                                        href.contains("gdflix", true) -> "GDFlix"
                                        href.contains("gdirect", true) -> "G-Direct"
                                        else -> "Direct"
                                    }
                                    out.add(ScrapedMirror(quality, label, href, "MD"))
                                    break
                                }
                                if (next.tagName() == "h5" &&
                                    Regex("""Ep\s*0*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(next.text())) break
                                next = next.nextElementSibling()
                                step++
                            }
                            break
                        }
                    } catch (e: Exception) {
                        Log.e("BingeCloud", "MD archive failed: ${e.message}")
                    }
                }
            }.awaitAll()
        }
    } catch (e: Exception) {
        Log.e("BingeCloud", "MD series extract failed: ${e.message}")
    }
    return out
}

// ═══════════════════════════════════════════
//  Entry
// ═══════════════════════════════════════════
suspend fun scrapeAllSources(q: StreamQuery): List<ScrapedMirror> {
    val results = mutableListOf<ScrapedMirror>()

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
