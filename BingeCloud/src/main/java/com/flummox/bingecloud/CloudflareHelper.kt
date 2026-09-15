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
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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

// ── challenge detection ──
private fun isChallenge(html: String): Boolean {
    val lower = html.lowercase()
    return CF_INDICATORS.any { lower.contains(it) }
}

// ── main CF-aware GET ──
suspend fun cloudflareGet(url: String, referer: String? = null): String? {
    val startMs = System.currentTimeMillis()
    val domain = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }

    // ── 1. try stored per-domain cookie ──
    val storedCookie = if (domain.isNotEmpty()) Settings.getCookieForDomain(domain) else null
    if (!storedCookie.isNullOrBlank()) {
        try {
            val res = app.get(
                url,
                referer = referer,
                headers = mapOf("User-Agent" to CF_UA, "Cookie" to storedCookie)
            )
            if (res.code in 200..299) {
                val text = res.text
                if (!isChallenge(text)) {
                    BCLog.d("[CF] stored cookie worked for $domain (${System.currentTimeMillis() - startMs}ms)")
                    return text
                }
            }
        } catch (_: Exception) {}
    }

    // ── 2. fast plain HTTP GET ──
    try {
        val res = app.get(
            url,
            referer = referer,
            headers = mapOf("User-Agent" to CF_UA)
        )
        if (res.code in 200..299) {
            val text = res.text
            if (!isChallenge(text)) {
                BCLog.d("[CF] plain GET ok for $domain (${System.currentTimeMillis() - startMs}ms)")
                return text
            }
            BCLog.d("[CF] challenge detected on $domain — starting WebView (${System.currentTimeMillis() - startMs}ms)")
        } else {
            BCLog.d("[CF] plain GET returned ${res.code} for $domain")
        }
    } catch (e: Exception) {
        BCLog.d("[CF] plain GET threw for $domain: ${e.message}")
    }

    // ── 3. WebView fallback (SLOW — this is the 5-20s killer) ──
    val wvStart = System.currentTimeMillis()
    val cookies = resolveWithWebView(url)
    val wvDuration = System.currentTimeMillis() - wvStart
    if (cookies.isNullOrBlank()) {
        BCLog.e("[CF] WebView returned no cookies for $domain (took ${wvDuration}ms)")
        return null
    }
    BCLog.d("[CF] WebView resolved for $domain in ${wvDuration}ms (cookie len=${cookies.length})")

    return try {
        val res = app.get(
            url,
            referer = referer,
            headers = mapOf("User-Agent" to CF_UA, "Cookie" to cookies)
        )
        res.text
    } catch (e: Exception) {
        BCLog.e("[CF] post-WebView GET failed for $domain: ${e.message}")
        null
    }
}

suspend fun cloudflareGetDoc(url: String, referer: String? = null): Document? {
    val html = cloudflareGet(url, referer) ?: return null
    return Jsoup.parse(html, url)
}

// ── WebView-based cookie acquisition ──
private suspend fun resolveWithWebView(url: String): String? {
    return try {
        val host = try {
            URI(url).host ?: ""
        } catch (e: Exception) {
            ""
        }
        val interceptRegex = if (host.isNotEmpty())
            Regex(".*${Regex.escape(host)}.*")
        else
            Regex(".*")

        val resolver = WebViewResolver(
            interceptUrl = interceptRegex,
            additionalUrls = listOf(
                Regex(".*challenges\\.cloudflare\\.com.*"),
                Regex(".*cdn-cgi/challenge-platform.*")
            ),
            userAgent = CF_UA,
            useOkhttp = false,
            timeout = 20_000L
        )

        val (finalRequest, additional) = resolver.resolveUsingWebView(url)
        var cookies = finalRequest?.header("Cookie")
            ?: additional.firstOrNull()?.header("Cookie")

        // ── fallback: read directly from WebView CookieManager ──
        if (cookies.isNullOrBlank()) {
            cookies = CookieManager.getInstance().getCookie(url)
        }

        BCLog.d("[CF] WebView cookie len=${cookies?.length ?: 0}")
        cookies
    } catch (e: Exception) {
        BCLog.e("[CF] WebViewResolver failed: ${e.message}")
        null
    }
}
