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

open class VCloud : ExtractorApi() {
    override val name = "V-Cloud"
    override val mainUrl = "https://vcloud.*"
    override val requiresReferer = false

    private fun extractDoubleAtob(html: String): String? {
        val regex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
        return regex.find(html)?.groupValues?.get(1)?.let {
            base64Decode(base64Decode(it))
        }
    }

    private fun extractSingleAtob(html: String): String? {
        val regex = Regex("""var\s+url\s*=\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)""")
        return regex.find(html)?.groupValues?.get(1)?.let {
            base64Decode(it)
        }
    }

    private fun extractRawUrl(html: String): String? {
        val regex = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""")
        return regex.find(html)?.groupValues?.get(1)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).document
        val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""

        val finalUrl = extractDoubleAtob(scriptTag)
            ?: extractSingleAtob(scriptTag)
            ?: extractRawUrl(scriptTag)
            ?: return

        if (!finalUrl.startsWith("http")) {
            Log.d("BingeCloud", "V-Cloud: extracted non-URL: $finalUrl")
            return
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = finalUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
