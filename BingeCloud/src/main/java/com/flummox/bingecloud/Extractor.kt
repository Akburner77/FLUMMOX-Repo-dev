/*
 * FLUMMOX Repo — CloudStream 3 Extension Repository
 * Copyright (C) 2026 FlummoxGamer
 * GPL-3.0-or-later
 */

package com.flummox.bingecloud

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI

// ══════════════════════════════════════════════════════════════
// ── UTIL ──
// ══════════════════════════════════════════════════════════════
fun base64Decode(str: String): String {
    return try { String(Base64.decode(str, Base64.DEFAULT)) } catch (e: Exception) { "" }
}

fun getBaseUrl(url: String): String {
    return try { URI(url).let { "${it.scheme}://${it.host}" } } catch (e: Exception) { url }
}

fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val lower = str.lowercase()
    return when {
        lower.contains("8k") -> 4320
        lower.contains("4k") -> 2160
        lower.contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

suspend fun getLatestBaseUrl(baseUrl: String, source: String): String {
    return try {
        val dynamicUrls = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json")
            .parsedSafe<Map<String, String>>()
        dynamicUrls?.get(source)?.takeIf { it.isNotBlank() } ?: baseUrl
    } catch (e: Exception) { baseUrl }
}

suspend fun resolveFinalUrl(startUrl: String): String? {
    var currentUrl = startUrl
    var loopCount = 0
    val maxRedirects = 7
    while (loopCount < maxRedirects) {
        try {
            val res = app.head(currentUrl, allowRedirects = false, timeout = 2500L)
            if (res.code == 200 || res.code in 300..399) {
                val location = res.headers["Location"]
                if (location.isNullOrEmpty()) break
                currentUrl = location
            } else return null
            loopCount++
        } catch (e: Exception) { return null }
    }
    return currentUrl
}

// ══════════════════════════════════════════════════════════════
// ── SERVER NAME SHORTENING ──
// ══════════════════════════════════════════════════════════════
private val TRAILING_QUALITY_REGEX = Regex("""[\s·•\-]*\d{3,4}[pP]?\s*$""")

private fun cleanServerName(raw: String): String =
    raw.replace(TRAILING_QUALITY_REGEX, "").trim()

private fun shortenServer(raw: String): String {
    val clean = cleanServerName(raw)
    if (clean.isEmpty()) return clean
    val l = clean.lowercase()
    return when {
        l.contains("hubcloud") -> "HCloud"
        l.contains("gdflix") -> "GDFlix"
        l.contains("vcloud") -> "VCloud"
        l.contains("vega") -> "Vega"
        l.contains("hdhub4u") || l.contains("hdhub") -> "HDhub"
        l.contains("pixeldrain") || l.contains("pixelserver") -> "Pixel"
        l.contains("10gbps") || l.contains("10 gbps") -> "10Gbps"
        l.contains("fsl") -> "FSL"
        l.contains("gdirect") -> "GDrive"
        l.contains("gdrive") || l.contains("google drive") -> "GDrive"
        l.contains("filepress") -> "FPress"
        l.contains("r2.dev") || l.contains("cloudflarestorage") -> "R2"
        l.contains("buzzserver") -> "Buzz"
        l.contains("mega.nz") || l.contains("mega ") -> "Mega"
        l.contains("gofile") -> "Gofile"
        l.contains("dropbox") -> "Dropbox"
        l.contains("onedrive") -> "OneDrive"
        l.contains("download [") -> {
            Regex("""\[([^\]]+)\]""").find(clean)?.groupValues?.get(1)
                ?.split(":")?.firstOrNull()?.trim()?.ifBlank { "Server" } ?: "Server"
        }
        else -> clean
    }
}

private fun isDirectFile(url: String): Boolean {
    val l = url.lowercase()
    return l.endsWith(".mp4") || l.endsWith(".mkv") || l.endsWith(".webm")
        || l.endsWith(".m3u8") || l.contains(".m3u8?")
        || l.endsWith(".mpd") || l.contains(".mpd?")
        || l.contains("cloudflarestorage.com")
        || l.contains("r2.dev")
}

private fun linkTypeFor(url: String): ExtractorLinkType {
    val l = url.lowercase()
    return when {
        l.contains(".m3u8") -> ExtractorLinkType.M3U8
        l.contains(".mpd") -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }
}

// ── dead hosts: don't waste time on them ──
private val DEAD_HOSTS = setOf(
    "gdflix.dev"   // 429 rate-limited, WebView can't solve it
)

// ══════════════════════════════════════════════════════════════
// ── VCLOUD EXTRACTOR ──
// ══════════════════════════════════════════════════════════════
open class VCloud(
    var sourceTag: String = "VC",
    var mirrorLabel: String = "",
    var qualityLabel: String = "",
    var emojiPrefix: String = ""
) : ExtractorApi() {
    override val name: String = "V-Cloud"
    override val mainUrl: String = "https://vcloud.*"
    override val requiresReferer = false

    private fun displayName(subServer: String): String {
        val q = qualityLabel.ifBlank { "Auto" }
        val raw = subServer.ifBlank { mirrorLabel }.ifBlank { sourceTag }
        val s = shortenServer(raw).ifBlank { "VCloud" }
        return "$emojiPrefix$q •$sourceTag $s"
    }

    fun extractPxlUrl(html: String): String? {
        val regex = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")
        return regex.find(html)?.groupValues?.get(1)
    }

    fun extractDoubleAtob(html: String): String? {
        val regex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
        return regex.find(html)?.groupValues?.get(1)?.let { base64Decode(base64Decode(it)) }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val startMs = System.currentTimeMillis()

        // ── skip dead hosts ──
        if (DEAD_HOSTS.any { url.contains(it, true) }) {
            BCLog.d("VCloud skip dead host: ${url.take(60)}")
            return
        }

        // ── extraction cache ──
        val cacheKey = "vcloud:${sourceTag}:${qualityLabel}:$url"
        BCCache.get(cacheKey, 30 * 60 * 1000L)?.let { cached ->
            BCLog.d("VCloud CACHE HIT: $sourceTag $qualityLabel (0ms)")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = displayName(mirrorLabel),
                    url = cached,
                    type = linkTypeFor(cached)
                ) {
                    this.referer = "https://hubcloud.ist/"
                }
            )
            return
        }

        // ── direct file — no extraction needed ──
        if (isDirectFile(url)) {
            BCLog.d("VCloud DIRECT: $sourceTag $qualityLabel (${System.currentTimeMillis() - startMs}ms)")
            BCCache.put(cacheKey, url)
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = displayName("Direct"),
                    url = url,
                    type = linkTypeFor(url)
                ) {
                    this.referer = url
                }
            )
            return
        }

        val doc = cloudflareGetDoc(url) ?: return

        val gamerxyt = doc.selectFirst("script:containsData(hubcloud.php)")?.toString()
            ?.let { Regex("""var\s+url\s*=\s*['"](https?://[^'"]+)['"]""").find(it)?.groupValues?.get(1) }

        if (gamerxyt != null && !gamerxyt.contains(".m3u8")) {
            val finalDoc = cloudflareGetDoc(gamerxyt) ?: return
            val finalAnchors = finalDoc.select("a[href].btn, a[href][id]")
            for (a in finalAnchors) {
                val href = a.attr("href")
                if (href.startsWith("http") &&
                    (href.contains("cloudflarestorage", true) || href.contains("r2.dev", true) ||
                     href.contains("gpdl.hubcloud", true) || href.contains("pixeldrain", true) ||
                     href.contains("busycdn", true) || href.contains("video-downloads", true))) {
                    val serverName = a.text().ifBlank { "HubCloud" }.trim()
                    val display = displayName(serverName)
                    BCLog.d("VCloud OK: $display (${System.currentTimeMillis() - startMs}ms)")
                    BCCache.put(cacheKey, href)
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = display,
                            url = href,
                            type = if (href.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://hubcloud.ist/"
                        }
                    )
                }
            }
            return
        }

        val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
        val link = if (url.contains("vcloud", true)) extractDoubleAtob(scriptTag) ?: ""
            else Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
        if (link.isEmpty()) {
            BCLog.d("VCloud no-match: $sourceTag $qualityLabel ${url.take(80)} (${System.currentTimeMillis() - startMs}ms)")
            return
        }
        val resolved = if (!link.startsWith("http")) getBaseUrl(url) + link else link
        val display = displayName("VCloud")
        BCLog.d("VCloud OK: $display (${System.currentTimeMillis() - startMs}ms)")
        BCCache.put(cacheKey, resolved)
        callback.invoke(
            newExtractorLink(
                source = name,
                name = display,
                url = resolved,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = url
            }
        )
    }
}

// ══════════════════════════════════════════════════════════════
// ── GDIRECT EXTRACTOR (Google Drive) ──
// ══════════════════════════════════════════════════════════════
open class GDirect : ExtractorApi() {
    override val name = "G-Direct"
    override val mainUrl = "https://gdirect.*"
    override val requiresReferer = false

    private fun extractDriveId(url: String): String? {
        val patterns = listOf(
            Regex("""/file/d/([a-zA-Z0-9_-]+)"""),
            Regex("""[?&]id=([a-zA-Z0-9_-]+)"""),
            Regex("""/d/([a-zA-Z0-9_-]+)"""),
            Regex("""^([a-zA-Z0-9_-]{25,})$""")
        )
        for (p in patterns) p.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    override suspend fun getUrl(url: String, referer: String?,
                                subtitleCallback: (SubtitleFile) -> Unit,
                                callback: (ExtractorLink) -> Unit) {
        val finalUrl = resolveFinalUrl(url) ?: url
        val driveId = extractDriveId(finalUrl) ?: extractDriveId(url)
        if (driveId == null) { Log.e("BingeCloud", "G-Direct: no Drive ID"); return }
        val directUrl = "https://drive.google.com/uc?export=download&id=$driveId&confirm=t"
        callback.invoke(newExtractorLink(name, "$name (Drive)", directUrl, ExtractorLinkType.VIDEO) {
            this.referer = "https://drive.google.com/"
            this.quality = Qualities.Unknown.value
        })
    }
}

// ══════════════════════════════════════════════════════════════
// ── FILEPRESS EXTRACTOR ──
// ══════════════════════════════════════════════════════════════
open class Filepress : ExtractorApi() {
    override val name = "Filepress"
    override val mainUrl = "https://filepress.*"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?,
                                subtitleCallback: (SubtitleFile) -> Unit,
                                callback: (ExtractorLink) -> Unit) {
        try {
            val doc = app.get(url).document
            val rows = doc.select("tr, .file-row, .list-group-item")
            for (row in rows) {
                val anchor = row.selectFirst("a[href]") ?: continue
                val href = anchor.attr("href")
                val text = anchor.text().lowercase()
                if (href.startsWith("http") && (text.contains("download") || text.contains("gdflix"))) {
                    if (href.contains("gdflix", true) && href != url) {
                        getUrl(href, url, subtitleCallback, callback)
                    } else {
                        callback.invoke(newExtractorLink(name, "$name ${row.text().take(40)}", href, ExtractorLinkType.VIDEO) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        })
                    }
                }
            }
            val directLinks = doc.select("a[href*='drive.google.com'], a[href*='.mkv'], a[href*='.mp4']")
            for (a in directLinks) {
                val href = a.attr("href")
                if (href.startsWith("http")) {
                    callback.invoke(newExtractorLink(name, name, href, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("BingeCloud", "Filepress failed: ${e.message}")
        }
    }
}
