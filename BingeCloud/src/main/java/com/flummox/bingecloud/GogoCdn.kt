package com.flummox.bingecloud

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// ── GogoAnime embed extractor ──
// Handles vibeplayer / gogocdn / streamtape-ish embeds.
// Embed page has <video src> or a packed m3u8 in JS data-sources.
class GogoCdn : ExtractorApi() {
    override val name = "GogoCdn"
    override val mainUrl = "https://vibeplayer.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document

        // direct <video> or <source>
        val direct = doc.selectFirst("video[src]")?.attr("src")
            ?: doc.selectFirst("source[src]")?.attr("src")
        if (!direct.isNullOrBlank()) {
            emit(direct, referer, callback)
            return
        }

        // JSON payload in a script: "file":"...m3u8"
        val script = doc.select("script").joinToString("\n") { it.data() }
        val m3u8Regex = Regex(""""file"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
        m3u8Regex.find(script)?.groupValues?.getOrNull(1)?.let { link ->
            emit(link, referer, callback)
            return
        }

        // fallback: any m3u8 URL in the page
        val loose = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").find(script)?.value
        if (loose != null) {
            emit(loose, referer, callback)
            return
        }

        BCLog.e("GogoCdn: no m3u8 found at $url")
    }

    private fun emit(link: String, referer: String?, cb: (ExtractorLink) -> Unit) {
        val url = if (link.startsWith("//")) "https:$link" else link
        BCLog.d("GogoCdn OK: ${url.take(90)}")
        cb.invoke(
            newExtractorLink(
                source = name,
                name = "$name HLS",
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer ?: mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
                              }
