package com.flummox.bingecloud

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// ── GogoAnime embed extractor ──
// Receives the iframe URL from the episode page.
// Fetches the embed, finds the m3u8, emits.
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
        val doc = try { app.get(url, referer = referer).document } catch (e: Exception) {
            BCLog.e("GogoCdn fetch failed: ${e.message}"); return
        }

        // direct <video> or <source>
        val direct = doc.selectFirst("video[src]")?.attr("src")
            ?: doc.selectFirst("source[src]")?.attr("src")
        if (!direct.isNullOrBlank()) {
            val u = if (direct.startsWith("//")) "https:$direct" else direct
            BCLog.d("GogoCdn OK direct: ${u.take(90)}")
            callback.invoke(
                newExtractorLink(name, "$name HLS", u, ExtractorLinkType.M3U8) {
                    this.referer = referer ?: mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        val script = doc.select("script").joinToString("\n") { it.data() }

        // "file":"...m3u8"
        val m3u8 = Regex(""""file"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(script)?.groupValues?.getOrNull(1)
        if (!m3u8.isNullOrBlank()) {
            BCLog.d("GogoCdn OK json: ${m3u8.take(90)}")
            callback.invoke(
                newExtractorLink(name, "$name HLS", m3u8, ExtractorLinkType.M3U8) {
                    this.referer = referer ?: mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // loose scan
        val loose = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").find(script)?.value
        if (!loose.isNullOrBlank()) {
            BCLog.d("GogoCdn OK loose: ${loose.take(90)}")
            callback.invoke(
                newExtractorLink(name, "$name HLS", loose, ExtractorLinkType.M3U8) {
                    this.referer = referer ?: mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        BCLog.e("GogoCdn: no m3u8 found at $url")
    }
                         }
