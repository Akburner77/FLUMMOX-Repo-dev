package com.flummox.bingecloud

import android.content.Context
import android.webkit.CookieManager
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

// ── context holder for CF WebView ──
object BingeCloudCtx {
    var context: Context? = null
}

private const val CF_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

private val CF_INDICATORS = listOf(
    "just a moment",
    "checking your browser",
    "cf-challenge",
    "cf_chl_opt",
    "cf_chl_prog",
    "enable javascript and cookies to continue",
    "attention required! | cloudflare",
    "challenges.cloudflare.com"
)

private fun isChallenge(html: String): Boolean {
    val lower = html.lowercase()
    return CF_INDICATORS.any { lower.contains(it) }
}

// ── main CF-aware GET ──
suspend fun cloudflareGet(url: String, referer: String? = null): String? {
    val startMs = System.currentTimeMillis()
    val domain = try { URI(url).host ?: "" } catch (_: Exception) { "" }

    // ── 1. stored per-domain cookie ──
    val storedCookie = if (domain.isNotEmpty()) Settings.getCookieForDomain(domain) else null
    if (!storedCookie.isNullOrBlank()) {
        try {
            val res = app.get(
                url, referer = referer,
                headers = mapOf("User-Agent" to CF_UA, "Cookie" to storedCookie)
            )
            if (res.code in 200..299 && !isChallenge(res.text)) {
                BCLog.d("[CF] stored cookie worked for $domain (${System.currentTimeMillis() - startMs}ms)")
                return res.text
            }
        } catch (_: Exception) {}
    }

    // ── 2. plain GET ──
    try {
        val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to CF_UA))
        if (res.code in 200..299 && !isChallenge(res.text)) {
            BCLog.d("[CF] plain GET ok for $domain (${System.currentTimeMillis() - startMs}ms)")
            return res.text
        }
        BCLog.d("[CF] plain GET returned ${res.code} for $domain")
    } catch (e: Exception) {
        BCLog.d("[CF] plain GET threw for $domain: ${e.message}")
    }

    // ── 3. WebViewResolver interceptor ──
    return try {
        val interceptor = WebViewResolver(
            interceptUrl = Regex(".*"),
            additionalUrls = emptyList(),
            userAgent = CF_UA,
            timeout = 30_000L
        )
        val res = app.get(
            url,
            referer = referer,
            headers = mapOf("User-Agent" to CF_UA),
            interceptor = interceptor
        )
        val cookieNow = CookieManager.getInstance().getCookie(url)
        BCLog.d("[CF] post-interceptor cookie len=${cookieNow?.length ?: 0}")
        if (res.code in 200..299 && !isChallenge(res.text)) {
            if (!cookieNow.isNullOrBlank() && domain.isNotEmpty()) {
                Settings.saveCookieForDomain(domain, cookieNow)
            }
            BCLog.d("[CF] post-interceptor ok for $domain")
            res.text
        } else {
            BCLog.d("[CF] post-interceptor GET ${res.code} for $domain")
            null
        }
    } catch (e: Exception) {
        BCLog.e("[CF] interceptor failed for $domain: ${e.message}")
        null
    }
}

suspend fun cloudflareGetDoc(url: String, referer: String? = null): Document? {
    val html = cloudflareGet(url, referer) ?: return null
    return Jsoup.parse(html, url)
}
