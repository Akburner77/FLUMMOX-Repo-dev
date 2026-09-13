package com.flummox.bingecloud

import android.content.Context
import android.webkit.CookieManager
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

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

private fun isChallenge(html: String): Boolean {
    val lower = html.lowercase()
    return CF_INDICATORS.any { lower.contains(it) }
}

suspend fun cloudflareGet(url: String, referer: String? = null): String? {
    // Try stored per-domain cookies first
    val domain = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
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
                    Log.d("BingeCloud", "stored cookie worked for $domain")
                    return text
                }
            }
        } catch (_: Exception) {}
    }

    // Fast path — plain HTTP GET
    try {
        val res = app.get(
            url,
            referer = referer,
            headers = mapOf("User-Agent" to CF_UA)
        )
        if (res.code in 200..299) {
            val text = res.text
            if (!isChallenge(text)) return text
            Log.d("BingeCloud", "CF challenge on $url — auto-resolving")
        } else {
            Log.d("BingeCloud", "GET $url returned ${res.code}")
        }
    } catch (e: Exception) {
        Log.w("BingeCloud", "plain GET threw for $url: ${e.message}")
    }

    // Auto WebView
    val cookies = resolveWithWebView(url)
    if (cookies.isNullOrBlank()) {
        Log.e("BingeCloud", "WebView returned no cookies for $url")
        return null
    }
    return try {
        val res = app.get(
            url,
            referer = referer,
            headers = mapOf("User-Agent" to CF_UA, "Cookie" to cookies)
        )
        res.text
    } catch (e: Exception) {
        Log.e("BingeCloud", "post-WebView GET failed for $url: ${e.message}")
        null
    }
}

suspend fun cloudflareGetDoc(url: String, referer: String? = null): Document? {
    val html = cloudflareGet(url, referer) ?: return null
    return Jsoup.parse(html, url)
}

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

        // Fallback — read directly from WebView CookieManager
        if (cookies.isNullOrBlank()) {
            cookies = CookieManager.getInstance().getCookie(url)
        }

        Log.d("BingeCloud", "WebView resolved — cookie len=${cookies?.length ?: 0}")
        cookies
    } catch (e: Exception) {
        Log.e("BingeCloud", "WebViewResolver failed: ${e.message}")
        null
    }
}
