package com.flummox.bingecore

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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
// Single WebView reused across all domains sequentially.
// Dark mode forced. Cookie captured per domain. No parallel
// because WebView is main-thread only.
// ═══════════════════════════════════════════════════════════════
object CloudflareShield {

    // Domain groups per source. Only CF-protected domains belong here.
    val GROUPS: Map<String, List<String>> = mapOf(
        "MLSBD" to listOf("mlsbd.co", "savelinks.me")
    )

    private const val K_CF_EXPIRY_PREFIX = "bingecloud_cf_expiry_"

    // ── expiry ──
    fun getExpiry(domain: String): Long =
        getKey<Long>(K_CF_EXPIRY_PREFIX + domain) ?: 0L

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
        val earliestExpiryMs: Long
    )

    fun statusOf(sourceName: String): GroupStatus {
        val domains = GROUPS[sourceName]
            ?: return GroupStatus(State.EMPTY, 0, 0, 0L)
        val now = System.currentTimeMillis()
        var fresh = 0
        var earliest = Long.MAX_VALUE
        for (d in domains) {
            val cookie = Settings.getCookieForDomain(d)
            val exp = getExpiry(d)
            val valid = !cookie.isNullOrBlank() && (exp == 0L || exp > now)
            if (valid) {
                fresh++
                if (exp in 1 until earliest) earliest = exp
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
            earliestExpiryMs = if (earliest == Long.MAX_VALUE) 0L else earliest
        )
    }

    // ── bypass all domains in one reused window ──
    // Runs sequentially. Each domain gets 30s to produce cf_clearance,
    // then moves on. Returns count of successfully captured cookies.
    suspend fun bypassGroup(
        ctx: Context,
        sourceName: String,
        onProgress: (current: Int, total: Int, domain: String) -> Unit
    ): Int = withContext(Dispatchers.Main) {
        val domains = GROUPS[sourceName] ?: return@withContext 0
        if (domains.isEmpty()) return@withContext 0

        var ok = 0
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        // ── card ──
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF0F1520.toInt())
                cornerRadius = dp(ctx, 16).toFloat()
                setStroke(dp(ctx, 1), 0xFF1E2A3D.toInt())
            }
        }

        // ── top bar (progress + close) ──
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
        }
        val progressLabel = TextView(ctx).apply {
            text = "0 / ${domains.size}"
            setTextColor(0xFF7DD3FC.toInt())
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(progressLabel)
        val domainLabel = TextView(ctx).apply {
            text = ""
            setTextColor(0xFF8296AD.toInt())
            textSize = 11f
            setPadding(dp(ctx, 6), 0, dp(ctx, 6), 0)
        }
        topBar.addView(domainLabel)
        val closeBtn = Button(ctx).apply {
            text = "✕"
            textSize = 14f
            setTextColor(0xFF8296AD.toInt())
            background = null
            minWidth = 0
            minHeight = 0
            setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
        }
        topBar.addView(closeBtn)
        card.addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ── WebView (single, reused) ──
        val webWrap = FrameLayout(ctx).apply {
            setBackgroundColor(Color.WHITE)
        }
        val web = WebView(ctx).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            // dark mode
            try {
                @Suppress("DEPRECATION")
                settings.forceDark = WebSettings.FORCE_DARK_ON
            } catch (_: Throwable) { /* API < 29 */ }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        webWrap.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        card.addView(webWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        dlg.setContentView(card)
        val w = dp(ctx, 380)
        val h = dp(ctx, 480)
        dlg.window?.setLayout(w, h)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.setCancelable(false)
        dlg.show()

        var userCancelled = false
        closeBtn.setOnClickListener {
            userCancelled = true
            try { web.stopLoading() } catch (_: Exception) {}
            dlg.dismiss()
        }

        // ── loop through domains, reuse window ──
        try {
            for ((idx, domain) in domains.withIndex()) {
                if (userCancelled) break
                onProgress(idx + 1, domains.size, domain)
                progressLabel.text = "${idx + 1} / ${domains.size}"
                domainLabel.text = domain
                BCLog.d("[CF Shield] opening $domain (${idx + 1}/${domains.size})")

                val captured = solveOne(
                    ctx, web, domain,
                    isCancelled = { userCancelled || !dlg.isShowing }
                )
                if (captured) {
                    ok++
                    BCLog.d("[CF Shield] $domain captured")
                } else {
                    BCLog.d("[CF Shield] $domain failed/timeout")
                }
                // short gap between domains so previous challenge fully clears
                if (idx < domains.lastIndex) delay(400)
            }
        } finally {
            try { web.stopLoading() } catch (_: Exception) {}
            try { web.destroy() } catch (_: Exception) {}
            try { dlg.dismiss() } catch (_: Exception) {}
        }
        return@withContext ok
    }

    // ── solve one domain in the reused WebView ──
    // Loads the URL, polls CookieManager for cf_clearance.
    // Returns true on capture, false on timeout or cancel.
    private suspend fun solveOne(
        ctx: Context,
        web: WebView,
        domain: String,
        isCancelled: () -> Boolean
    ): Boolean = suspendCancellableCoroutine { cont ->
        val url = "https://$domain"
        val handler = Handler(Looper.getMainLooper())
        val start = System.currentTimeMillis()
        val maxMs = 30_000L

        web.loadUrl(url)

        val poll = object : Runnable {
            override fun run() {
                if (!cont.isActive) return
                if (isCancelled()) {
                    if (cont.isActive) cont.resume(false)
                    return
                }
                val cookie = CookieManager.getInstance().getCookie(url) ?: ""
                if (cookie.contains("cf_clearance")) {
                    val exp = System.currentTimeMillis() + 24L * 60 * 60 * 1000
                    Settings.saveCookieForDomain(domain, cookie)
                    setKey(K_CF_EXPIRY_PREFIX + domain, exp)
                    if (cont.isActive) cont.resume(true)
                    return
                }
                if (System.currentTimeMillis() - start > maxMs) {
                    if (cont.isActive) cont.resume(false)
                    return
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(poll, 500)

        cont.invokeOnCancellation {
            handler.removeCallbacks(poll)
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
