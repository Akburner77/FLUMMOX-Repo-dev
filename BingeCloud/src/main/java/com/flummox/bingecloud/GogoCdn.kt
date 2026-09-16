package com.flummox.bingecloud

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder

// ═══════════════════════════════════════════
// ── GogoCdn extractor ──
// Handles gogoanime.by/player/?source=X&url=B64
// Fast path: base64-decode url= param → direct m3u8 if valid
// Fallback: fetch /player/ with gogoanime referer → parse googlevideo/m3u8
// ═══════════════════════════════════════════
class GogoCdn : ExtractorApi() {
    override val name = "GogoCdn"
    override val mainUrl = "https://gogoanime.by"
    override val requiresReferer = true

    private val M3U8_REGEX = Regex("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""")
    private val MP4_REGEX = Regex("""https?://[^"'\s\\]+\.mp4[^"'\s\\]*""")
    private val GOOGLEVIDEO_REGEX = Regex("""https?://[^"'\s\\]+googlevideo\.com/videoplayback[^"'\s\\]*""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val startMs = System.currentTimeMillis()

        // ── cache ──
        val cacheKey = "gogocdn:$url"
        BCCache.get(cacheKey, 6 * 60 * 60 * 1000L)?.let { cached ->
            BCLog.d("GogoCdn CACHE HIT (0ms): ${cached.take(80)}")
            emit(cached, callback)
            return
        }

        // ── fast path: base64-decode url= param ──
        tryInlineDecode(url)?.let { decoded ->
            BCLog.d("GogoCdn DIRECT (${System.currentTimeMillis() - startMs}ms): ${decoded.take(80)}")
            BCCache.put(cacheKey, decoded)
            emit(decoded, callback)
            return
        }

        // ── fallback: fetch player page ──
        val html = try {
            app.get(url, referer = "https://gogoanime.by/").text
        } catch (e: Exception) {
            BCLog.e("GogoCdn fetch failed: ${e.message}"); return
        }

        GOOGLEVIDEO_REGEX.find(html)?.value?.let {
            BCLog.d("GogoCdn BLOGGER (${System.currentTimeMillis() - startMs}ms): ${it.take(80)}")
            BCCache.put(cacheKey, it)
            emitBlogger(it, callback)
            return
        }

        M3U8_REGEX.find(html)?.value?.let {
            BCLog.d("GogoCdn M3U8 (${System.currentTimeMillis() - startMs}ms): ${it.take(80)}")
            BCCache.put(cacheKey, it)
            emit(it, callback)
            return
        }

        MP4_REGEX.find(html)?.value?.let {
            BCLog.d("GogoCdn MP4 (${System.currentTimeMillis() - startMs}ms): ${it.take(80)}")
            BCCache.put(cacheKey, it)
            emit(it, callback)
            return
        }

        // ── nested iframe — recurse once ──
        val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
        if (!iframe.isNullOrBlank() && iframe != url) {
            val nested = when {
                iframe.startsWith("//") -> "https:$iframe"
                iframe.startsWith("/") -> "https://gogoanime.by$iframe"
                else -> iframe
            }
            BCLog.d("GogoCdn recurse → ${nested.take(80)}")
            getUrl(nested, url, subtitleCallback, callback)
            return
        }

        BCLog.e("GogoCdn: no m3u8/mp4/googlevideo found (${System.currentTimeMillis() - startMs}ms)")
    }

    /** Try to pull a direct stream URL out of the base64-encoded url= param. */
    private fun tryInlineDecode(playerUrl: String): String? {
        val m = Regex("""[?&]url=([^&]+)""").find(playerUrl) ?: return null
        val raw = m.groupValues[1]
        val urlDecoded = try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) { raw }

        val one = try {
            String(Base64.decode(urlDecoded, Base64.DEFAULT)).trim()
        } catch (_: Exception) { null }
        if (one != null && one.startsWith("http")) return one

        if (one != null) {
            val two = try {
                String(Base64.decode(one, Base64.DEFAULT)).trim()
            } catch (_: Exception) { null }
            if (two != null && two.startsWith("http")) return two
        }
        return null
    }

    private suspend fun emit(url: String, cb: (ExtractorLink) -> Unit) {
        val type = when {
            url.contains(".m3u8") -> ExtractorLinkType.M3U8
            url.contains(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        cb.invoke(
            newExtractorLink(
                source = name,
                name = "$name HLS",
                url = url,
                type = type
            ) {
                this.referer = "https://gogoanime.by/"
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private suspend fun emitBlogger(url: String, cb: (ExtractorLink) -> Unit) {
        cb.invoke(
            newExtractorLink(
                source = name,
                name = "$name Blogger",
                url = url,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://www.blogger.com/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
