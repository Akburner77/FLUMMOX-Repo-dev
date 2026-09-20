package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

// ═══════════════════════════════════════════════════════════════
// ── MLSBD: Bangladeshi movie/series link directory ──
// WordPress site. Search → movie page → savelinks.me redirect →
// multicloudlinks page → player.php streamSrc / R2 direct.
// All remote hops go through cloudflareGet (CF-protected).
// ═══════════════════════════════════════════════════════════════

private const val MLSBD_BASE = "https://mlsbd.co"
private const val MLSBD_UA =
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private val mlsbdHeaders = mapOf(
    "User-Agent" to MLSBD_UA,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9"
)

data class MlsbdHit(val url: String, val title: String, val poster: String?)

// ── search ──
suspend fun mlsbdSearch(query: String): List<MlsbdHit> {
    val url = "$MLSBD_BASE/?s=${URLEncoder.encode(query, "UTF-8")}"
    val html = try {
        cloudflareGet(url, referer = MLSBD_BASE)
    } catch (e: Exception) {
        BCLog.e("MLSBD search failed: ${e.message}"); return emptyList()
    } ?: return emptyList()
    val doc = Jsoup.parse(html, url)

    val out = mutableListOf<MlsbdHit>()
    for (card in doc.select("div.single-post")) {
        val a = card.selectFirst("div.thumb a[href]")
            ?: card.selectFirst("div.post-desc a[href]")
            ?: continue
        val titleEl = card.selectFirst("h2.post-title")
            ?: card.selectFirst("h2")
            ?: continue
        val href = a.attr("href").takeIf { it.startsWith("http") } ?: continue
        val title = titleEl.text().trim().takeIf { it.isNotBlank() } ?: continue
        val poster = card.selectFirst("div.thumb img[src]")?.attr("src")
            ?.takeIf { it.startsWith("http") }
        out.add(MlsbdHit(href, title, poster))
    }
    BCLog.d("MLSBD search '$query' → ${out.size}")
    return out
}

// ── find best-matching movie/series page ──
suspend fun mlsbdFindPage(
    title: String, year: String, type: String, season: Int
): String? {
    val hits = mlsbdSearch(title)
    if (hits.isEmpty()) return null

    var best: MlsbdHit? = null
    var bestScore = 0
    for (h in hits) {
        if (!titleMatches(title, h.title)) continue
        var score = 1
        if (year.isNotBlank() && h.title.contains(year)) score += 2
        val l = h.title.lowercase()
        if (type == "series") {
            if (l.contains("season") || l.contains("s0") || l.contains("series")) score += 2
            if (season > 0) {
                if (pageHasSeason(h.title, season)) score += 2
                else {
                    BCLog.d("MLSBD skip wrong season: ${h.title.take(60)}")
                    continue
                }
            }
        } else {
            if (!l.contains("season") && !l.contains("series")) score += 1
        }
        if (score > bestScore) { bestScore = score; best = h }
    }
    val picked = best ?: return null
    BCLog.d("MLSBD matched '${picked.title.take(80)}' (score=$bestScore)")
    return picked.url
}

// ── resolve savelinks.me → multicloudlinks URL (CF-protected) ──
private suspend fun mlsbdResolveSavelinks(savelinksUrl: String): String? {
    return try {
        val body = cloudflareGet(savelinksUrl, referer = MLSBD_BASE) ?: return null
        Regex("""https?://[^"'\s<>]*multicloudlinks\.com/view/[A-Za-z0-9]+""")
            .find(body)?.value
            ?: Regex("""(?:window\.location|location\.href)\s*=\s*["']([^"']+)["']""")
                .find(body)?.groupValues?.get(1)
                ?.takeIf { it.contains("multicloudlinks") }
    } catch (e: Exception) {
        BCLog.d("MLSBD savelinks resolve failed: ${e.message}"); null
    }
}

// ── extract mirrors from a multicloudlinks page ──
private suspend fun mlsbdExtractFromMulticloud(
    multiUrl: String, quality: String
): List<ScrapedMirror> {
    val html = try {
        cloudflareGet(multiUrl, referer = "https://savelinks.me/")
    } catch (e: Exception) {
        BCLog.d("MLSBD multicloud fetch failed: ${e.message}"); return emptyList()
    } ?: return emptyList()
    val doc = Jsoup.parse(html, multiUrl)

    val out = mutableListOf<ScrapedMirror>()

    // 1. player.php → fetch and extract streamSrc
    val playerUrl = doc.selectFirst("a.premium-btn[href*='player.php']")?.attr("href")
    if (!playerUrl.isNullOrBlank()) {
        val stream = mlsbdExtractPlayerStream(playerUrl)
        if (stream != null) {
            out.add(ScrapedMirror(quality, "MLSBD Player", stream, "MLSBD"))
            BCLog.d("MLSBD player stream $quality → ${stream.take(80)}")
        }
    }

    // 2. R2 direct download — playable as-is
    val r2Url = doc.select("a.premium-btn[href]").firstOrNull {
        val t = it.text().lowercase()
        t.contains("turbo download") || t.contains("(r2)")
    }?.attr("href")?.takeIf { it.startsWith("http") }
    if (r2Url != null) {
        out.add(ScrapedMirror(quality, "MLSBD R2", r2Url, "MLSBD"))
        BCLog.d("MLSBD R2 $quality → ${r2Url.take(80)}")
    }

    // 3. FilePress mirror — handled by existing resolver in loadLinks
    val fpUrl = doc.select("a.premium-btn[href]").firstOrNull {
        it.text().lowercase().contains("filepress")
    }?.attr("href")?.takeIf { it.startsWith("http") }
    if (fpUrl != null && out.size < 3) {
        out.add(ScrapedMirror(quality, "MLSBD FilePress", fpUrl, "MLSBD"))
        BCLog.d("MLSBD FilePress $quality → ${fpUrl.take(80)}")
    }

    return out
}

// ── fetch player.php and extract streamSrc (CF-protected) ──
private suspend fun mlsbdExtractPlayerStream(playerUrl: String): String? {
    return try {
        val html = cloudflareGet(playerUrl, referer = "https://new2.multicloudlinks.com/")
            ?: return null
        val m = Regex("""const\s+streamSrc\s*=\s*"([^"]+)"""")
            .find(html)
        val url = m?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
        if (url == null) {
            BCLog.d("MLSBD player: no streamSrc in ${playerUrl.take(60)}")
        }
        url
    } catch (e: Exception) {
        BCLog.d("MLSBD player fetch failed: ${e.message}"); null
    }
}

// ═══════════════════════════════════════════════════════════════
// ── ENTRY POINT: raw mirror extraction for a StreamQuery ──
// ═══════════════════════════════════════════════════════════════
suspend fun mlsbdExtractRaw(q: StreamQuery): List<ScrapedMirror> {
    val pageUrl = mlsbdFindPage(q.title, q.year, q.type, q.season) ?: return emptyList()
    val pageHtml = cloudflareGet(pageUrl, referer = MLSBD_BASE) ?: return emptyList()
    val doc = Jsoup.parse(pageHtml, pageUrl)

    // walk sections: header div + following <p> siblings until next header
    data class Section(val title: String, val links: List<Element>)
    val sections = mutableListOf<Section>()
    for (secDiv in doc.select("div.post-section-title.download")) {
        val links = mutableListOf<Element>()
        var sib = secDiv.nextElementSibling()
        while (sib != null && !sib.hasClass("post-section-title")) {
            if (sib.tagName() == "p") links.addAll(sib.select("a.Dbtn[href]"))
            sib = sib.nextElementSibling()
        }
        sections.add(Section(secDiv.text(), links))
    }
    BCLog.d("MLSBD sections: ${sections.size} on page")

    // for series: filter by episode range, take first matching section
    val relevant: List<Section> = if (q.type == "series" && q.episode > 0) {
        sections.filter { s ->
            val m = Regex("""Epi-(\d+)-(\d+)""", RegexOption.IGNORE_CASE)
                .find(s.title) ?: return@filter false
            val start = m.groupValues[1].toIntOrNull() ?: return@filter false
            val end = m.groupValues[2].toIntOrNull() ?: return@filter false
            q.episode in start..end
        }.take(1)
    } else {
        sections
    }
    if (relevant.isEmpty()) {
        BCLog.d("MLSBD: no matching section for ${q.type} E${q.episode}")
        return emptyList()
    }

    // build savelinks jobs (skip 480p to reduce clutter)
    data class Job(val quality: String, val savelinks: String)
    val jobs = mutableListOf<Job>()
    for (sec in relevant) {
        for (a in sec.links) {
            val href = a.attr("href")
            if (!href.contains("savelinks.me/view")) continue
            val text = a.text().lowercase()
            val quality = when {
                text.contains("4k") || text.contains("2160") -> "2160p"
                text.contains("1080") -> "1080p"
                text.contains("720") -> "720p"
                text.contains("480") -> "480p"
                else -> continue
            }
            if (quality == "480p") continue
            jobs.add(Job(quality, href))
        }
    }
    BCLog.d("MLSBD savelinks jobs: ${jobs.size}")

    val out = mutableListOf<ScrapedMirror>()
    val seen = mutableSetOf<String>()
    for (j in jobs) {
        val key = "${j.quality}|${j.savelinks}"
        if (!seen.add(key)) continue

        val multiUrl = mlsbdResolveSavelinks(j.savelinks) ?: continue
        BCLog.d("MLSBD ${j.quality} → ${multiUrl.take(90)}")
        val mirrors = mlsbdExtractFromMulticloud(multiUrl, j.quality)
        out.addAll(mirrors)
    }

    BCLog.d("MLSBD: ${out.size} total mirrors")
    return out
}
