package com.flummox.bingecore

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.flummox.bingecloud.BCLog
import com.flummox.bingecloud.Settings
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

// ═══════════════════════════════════════════════════════════════
// ── bingecore: Cloudflare Shield ──
// One small tick-window bypass. Stores cookies with expiry.
// Groups CF-protected domains per source, so the UI shows one
// indicator per source regardless of how many domains it uses.
// ═══════════════════════════════════════════════════════════════
object CloudflareShield {

    // Domain groups per source. Add new sources here as we add them.
    val GROUPS: Map<String, List<String>> = mapOf(
        "MLSBD" to listOf("mlsbd.co", "savelinks.me")
    )

    private const val K_CF_EXPIRY_PREFIX = "bingecloud_cf_expiry_"

    // ── expiry storage ──
    fun getExpiry(domain: String): Long =
        getKey<Long>(K_CF_EXPIRY_PREFIX + domain) ?: 0L

    fun saveCookieWithExpiry(domain: String, cookie: String, expiryMs: Long) {
        Settings.saveCookieForDomain(domain, cookie)
        setKey(K_CF_EXPIRY_PREFIX + domain, expiryMs)
    }

    fun clearCookieAndExpiry(domain: String) {
        Settings.clearCookieForDomain(domain)
        setKey(K_CF_EXPIRY_PREFIX + domain, 0L)
    }

    // ── group status ──
    enum class State { EMPTY, PARTIAL, PROTECTED, WORKING }

    data class GroupStatus(
        val state: State,
        val freshCount: Int,
        val totalCount: Int,
        val earliestExpiryMs: Long,
        val hasExpired: Boolean
    )

    fun statusOf(sourceName: String): GroupStatus {
        val domains = GROUPS[sourceName]
            ?: return GroupStatus(State.EMPTY, 0, 0, 0L, false)
        val now = System.currentTimeMillis()
        var fresh = 0
        var expired = false
        var earliest = Long.MAX_VALUE
        for (d in domains) {
            val cookie = Settings.getCookieForDomain(d)
            val exp = getExpiry(d)
            val valid = !cookie.isNullOrBlank() && (exp == 0L || exp > now)
            if (valid) {
                fresh++
                if (exp in 1 until earliest) earliest = exp
            } else if (!cookie.isNullOrBlank()) {
                expired = true
            }
        }
        val state = when {
            fresh == 0 -> State.EMPTY
            fresh == domains.size -> State.PROTECTED
            else -> State.PARTIAL
        }
        return GroupStatus(
            state = state,
            freshCount = fresh,
            totalCount = domains.size,
            earliestExpiryMs = if (earliest == Long.MAX_VALUE) 0L else earliest,
            hasExpired = expired
        )
    }

    // ── bypass one domain via small tick window ──
    // Returns true if a cf_clearance cookie was captured.
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun bypassDomain(ctx: Context, domain: String): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val url = "https://$domain"
                val dlg = Dialog(ctx)
                dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(0xFF0F1520.toInt())
                        cornerRadius = dp(ctx, 16).toFloat()
                        setStroke(dp(ctx, 1), 0xFF1E2A3D.toInt())
                    }
                    setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
                }

                val webWrap = FrameLayout(ctx)
                val web = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                          
                            // center the CF widget by scrolling body
                            view?.evaluateJavascript(
                                "(function(){var h=document.body.scrollHeight;" +
                                "window.scrollTo(0,(h-window.innerHeight)/2);})();",
                                 null
                             )
                         }
                    }
                    webChromeClient = WebChromeClient()
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    loadUrl(url)
                }
                webWrap.addView(web, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                card.addView(webWrap, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                ))

                val bottom = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 4))
                }
                bottom.addView(TextView(ctx).apply {
                    text = domain
                    setTextColor(0xFF8296AD.toInt())
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                bottom.addView(Button(ctx).apply {
                    text = "✕"
                    textSize = 14f
                    setTextColor(0xFF8296AD.toInt())
                    background = null
                    minWidth = 0
                    minHeight = 0
                    setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2))
                    setOnClickListener {
                        try { web.stopLoading(); web.destroy() } catch (_: Exception) {}
                        dlg.dismiss()
                        if (cont.isActive) cont.resume(false)
                    }
                })
                card.addView(bottom, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))

                dlg.setContentView(card)
                val w = dp(ctx, 380)
                val smallH = dp(ctx, 130)
                val bigH = dp(ctx, 520)
                dlg.window?.setLayout(w, smallH)
                dlg.window?.setLayout(w, smallH)
                dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dlg.setCancelable(false)
                dlg.show()

                val handler = Handler(Looper.getMainLooper())
                val start = System.currentTimeMillis()
                val maxMs = 60_000L
                var grown = false

                val poll = object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        val cookie = CookieManager.getInstance().getCookie(url) ?: ""
                        if (cookie.contains("cf_clearance")) {
                            // default TTL — refined later once we read the Set-Cookie header
                            val exp = System.currentTimeMillis() + 24L * 60 * 60 * 1000
                            saveCookieWithExpiry(domain, cookie, exp)
                            BCLog.d("[CF Shield] $domain bypassed (cookie saved)")
                            try { web.stopLoading(); web.destroy() } catch (_: Exception) {}
                            dlg.dismiss()
                            if (cont.isActive) cont.resume(true)
                            return
                        }
                        val elapsed = System.currentTimeMillis() - start
                        if (!grown && elapsed > 4000) {
                           grown = true
                           dlg.window?.setLayout(w, bigH)
                        }
                        if (elapsed > maxMs) {
                            BCLog.d("[CF Shield] $domain timed out after 60s")
                            try { web.stopLoading(); web.destroy() } catch (_: Exception) {}
                            dlg.dismiss()
                            if (cont.isActive) cont.resume(false)
                            return
                        }
                        handler.postDelayed(this, 500)
                    }
                }
                handler.postDelayed(poll, 500)

                cont.invokeOnCancellation {
                    handler.removeCallbacks(poll)
                    try { web.stopLoading(); web.destroy() } catch (_: Exception) {}
                    dlg.dismiss()
                }
            }
        }

    // ── bypass all domains in a group, sequentially ──
    suspend fun bypassGroup(
        ctx: Context,
        sourceName: String,
        onProgress: (current: Int, total: Int, domain: String) -> Unit
    ): Int {
        val domains = GROUPS[sourceName] ?: return 0
        var ok = 0
        for ((idx, d) in domains.withIndex()) {
            onProgress(idx + 1, domains.size, d)
            if (bypassDomain(ctx, d)) ok++
            delay(200)
        }
        return ok
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
