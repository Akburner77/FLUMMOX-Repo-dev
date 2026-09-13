package com.flummox.bingecloud

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.WebViewResolver
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Holds the Android Context passed to BingeCloudPlugin.load().
 * Needed to invoke WebViewResolver for Cloudflare challenges.
 */
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

/**
 * Cloudflare-aware GET. Returns response body as String, or null on failure.
 * 1. Plain HTTP request first (fast path).
 * 2. If the response looks like a CF challenge, open a WebView to solve it,
 *    harvest cookies, and retry.
 */
suspend fun cloudflareGet(url: String, referer: String? = null): String? {
    // Fast path — plain request
    try {
        val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to CF_UA))
        if (res.code in 200..299) {
            val text = res.text
            if (!isChallenge(text)) return text
            Log.d("BingeCloud", "CF challenge detected on $url — switching to WebView")
        } else {
            Log.d("BingeCloud", "plain GET returned ${res.code} for $url")
        }
    } catch (e: Exception) {
        Log.w("BingeCloud", "plain GET threw for $url: ${e.message}")
    }

    // Slow path — WebView
    val ctx = BingeCloudCtx.context ?: run {
        Log.e("BingeCloud", "no Context available for WebView bypass")
        return null
    }

    val cookies = resolveWithWebView(ctx, url) ?: return null
    return try {
        val res = app.get(
            url,
            referer = referer,
            cookies = cookies,
            headers = mapOf("User-Agent" to CF_UA)
        )
        res.text
    } catch (e: Exception) {
        Log.e("BingeCloud", "post-WebView GET failed for $url: ${e.message}")
        null
    }
}

/**
 * Convenience wrapper that parses the response as a Jsoup Document.
 */
suspend fun cloudflareGetDoc(url: String, referer: String? = null): Document? {
    val html = cloudflareGet(url, referer) ?: return null
    return Jsoup.parse(html, url)
}

private suspend fun resolveWithWebView(context: Context, url: String): String? {
    return try {
        val resolver = WebViewResolver(
            url = url,
            userAgent = CF_UA,
            timeout = 20L,
            additionalUrls = listOf(
                "challenges.cloudflare.com",
                "cdn-cgi/challenge-platform",
                "turnstile"
            ),
            interceptor = object : WebViewResolver.Interceptor {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = null

                override fun onPageFinished(view: WebView, url: String) {
                    Log.d("BingeCloud", "WebView page finished: $url")
                }
            }
        )
        val cookies = resolver.resolveUsingWebView(context)
        Log.d("BingeCloud", "WebView cookies len=${cookies?.length ?: 0}")
        cookies
    } catch (e: Exception) {
        Log.e("BingeCloud", "WebViewResolver failed: ${e.message}")
        null
    }
}
