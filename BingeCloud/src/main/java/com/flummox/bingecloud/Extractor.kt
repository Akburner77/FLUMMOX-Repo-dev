/*
 * FLUMMOX Repo — CloudStream 3 Extension Repository
 * Copyright (C) 2026 FlummoxGamer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.flummox.bingecloud

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI

fun base64Decode(str: String): String {
    return try {
        String(Base64.decode(str, Base64.DEFAULT))
    } catch (e: Exception) {
        ""
    }
}

fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (e: Exception) {
        url
    }
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
    } catch (e: Exception) {
        baseUrl
    }
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
            } else {
                return null
            }
            loopCount++
        } catch (e: Exception) {
            return null
        }
    }
    return currentUrl
}

open class VCloud(var sourceTag: String = "VC") : ExtractorApi() {
    override val name: String = "V-Cloud"
    override val mainUrl: String = "https://vcloud.*"
    override val requiresReferer = false

    fun extractPxlUrl(html: String): String? {
        val regex = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")
        return regex.find(html)?.groupValues?.get(1)
    }

    fun extractDoubleAtob(html: String): String? {
        val regex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
        return regex.find(html)?.groupValues?.get(1)?.let {
            base64Decode(base64Decode(it))
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var baseUrl = getBaseUrl(url)
        val latestBaseUrl = if (url.contains("hubcloud")) {
            getLatestBaseUrl(baseUrl, "hubcloud")
        } else {
            getLatestBaseUrl(baseUrl, "vcloud")
        }

        var newUrl = url
        if (baseUrl != latestBaseUrl) {
            newUrl = url.replace(baseUrl, latestBaseUrl)
            baseUrl = latestBaseUrl
        }

        val doc = cloudflareGetDoc(newUrl) ?: return
        var link = if (newUrl.contains("/video/")) {
            doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        } else {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            if (newUrl.contains("vcloud")) {
                extractDoubleAtob(scriptTag) ?: ""
            } else {
                Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
            }
        }

        if (!link.startsWith("https://")) link = baseUrl + link

        val document = cloudflareGetDoc(link) ?: return
        val header = document.select("div.card-header").text()
        val quality = getIndexQuality(header)
        val qualityText = Regex("""(\d{3,4}[pP])""").find(header)?.value ?: "${quality}p"

        suspend fun myCallback(link: String, server: String = "") {
            val serverClean = server.trim('[', ']').trim()
                .replace(Regex("""\b\d{3,4}[pP]\b"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()
            val label = buildString {
                append(qualityText)
                if (sourceTag.isNotBlank()) {
                    append(" · ")
                    append(sourceTag)
                }
                if (serverClean.isNotBlank()) {
                    append(" · ")
                    append(serverClean)
                }
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = link,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                }
            )
        }

        document.select("h2 a.btn").amap {
            val href = it.attr("href")
            val text = it.text()
            when {
                text.contains("FSL Server") -> myCallback(href, "[FSL Server]")
                text.contains("FSLv2") -> myCallback(href, "[FSLv2 Server]")
                text.contains("Mega Server") -> myCallback(href, "[Mega Server]")
                text.contains("Download File") -> myCallback(href)
                text.contains("BuzzServer") -> {
                    val dlink = app.get("$href/download", referer = href, allowRedirects = false)
                        .headers["hx-redirect"] ?: ""
                    val bUrl = getBaseUrl(href)
                    if (dlink != "") myCallback(bUrl + dlink, "[BuzzServer]")
                }
                href.contains("pixeldra") -> {
                    val pixelLink = extractPxlUrl(document.toString()) ?: return@amap
                    val baseUrlLink = getBaseUrl(pixelLink)
                    val finalURL = if (pixelLink.contains("download", true)) {
                        pixelLink
                    } else {
                        "$baseUrlLink/api/file/${pixelLink.substringAfterLast("/")}?download"
                    }
                    myCallback(finalURL, "[Pixeldrain]")
                }
                text.contains("Server : 10Gbps") -> {
                    var redirectUrl = resolveFinalUrl(href) ?: return@amap
                    if (redirectUrl.contains("link=")) redirectUrl = redirectUrl.substringAfter("link=")
                    myCallback(redirectUrl, "[Download]")
                }
                text.contains("Gofile") -> loadExtractor(href, "", subtitleCallback, callback)
                else -> Log.d("BingeCloud", "V-Cloud: no server matched for: $text")
            }
        }
    }
}

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
        for (p in patterns) {
            p.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val finalUrl = resolveFinalUrl(url) ?: url
        val driveId = extractDriveId(finalUrl) ?: extractDriveId(url)
        if (driveId == null) {
            Log.e("BingeCloud", "G-Direct: no Drive ID")
            return
        }
        val directUrl = "https://drive.google.com/uc?export=download&id=$driveId&confirm=t"
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name (Drive)",
                url = directUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://drive.google.com/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

open class Filepress : ExtractorApi() {
    override val name = "Filepress"
    override val mainUrl = "https://filepress.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
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
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name ${row.text().take(40)}",
                                url = href,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
            val directLinks = doc.select("a[href*='drive.google.com'], a[href*='.mkv'], a[href*='.mp4']")
            for (a in directLinks) {
                val href = a.attr("href")
                if (href.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(name, name, href, ExtractorLinkType.VIDEO) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("BingeCloud", "Filepress failed: ${e.message}")
        }
    }
}
