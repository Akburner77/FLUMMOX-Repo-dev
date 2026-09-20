package com.flummox.bingecore

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
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
// Off-screen Turnstile solver. Attaches WebView to current window,
// translates off-screen, dispatches real Tab+Space key events to
// press the checkbox. Polls CookieManager every 150ms for
// cf_clearance. Dark mode. Images blocked for speed.
// ═══════════════════════════════════════════════════════════════
object CloudflareShield {

    // Only domains that actually serve a CF challenge.
    val GROUPS: Map<String, List<String>> = mapOf(
        "MLSBD" to listOf("mlsbd.co")
    )

    private const val K_CF_EXPIRY_PREFIX = "bingecloud_cf_expiry_"

    // Desktop UA — matches the HTTP client's UA so the cookie
    // stays valid across WebView and OkHttp.
    const val CF_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

    fun getExpiry(domain: String): Long =
        getKey<Long>(K_CF_EXPIRY_PREFIX + domain) ?: 0L

    fun clearCookieAndExpiry(domain: String) {
        Settings.clearCookieForDomain(domain)
        setKey(K_CF_EXPIRY_PREFIX + domain, 0L)
    }

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

    // ── bypass all domains sequentially ──
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

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF0F1520.toInt())
                cornerRadius = dp(ctx, 16).toFloat()
                setStroke(dp(ctx, 1), 0xFF1E2A3D.toInt())
            }
        }

        // ── top bar ──
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

        // ── WebView wrapper (visible only if solve fails after 8s) ──
        val webWrap = FrameLayout(ctx).apply {
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE  // hidden during auto-solve
        }
        val web = WebView(ctx).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = CF_UA
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            // Dark mode
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    settings.forceDark = WebSettings.FORCE_DARK_ON
                }
            } catch (_: Throwable) {}
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

        try {
            for ((idx, domain) in domains.withIndex()) {
                if (userCancelled) break
                onProgress(idx + 1, domains.size, domain)
                progressLabel.text = "${idx + 1} / ${domains.size}"
                domainLabel.text = domain
                val t0 = System.currentTimeMillis()
                BCLog.d("[CF Shield] opening $domain (${idx + 1}/${domains.size})")

                val captured = solveOne(
                    ctx, web, webWrap, domain,
                    isCancelled = { userCancelled || !dlg.isShowing }
                )
                val ms = System.currentTimeMillis() - t0
                if (captured) {
                    ok++
                    BCLog.d("[CF Shield] $domain captured in ${ms}ms")
                } else {
                    BCLog.d("[CF Shield] $domain failed/timeout after ${ms}ms")
                }
                if (idx < domains.lastIndex) delay(200)
            }
        } finally {
            try { web.stopLoading() } catch (_: Exception) {}
            try { web.destroy() } catch (_: Exception) {}
            try { dlg.dismiss() } catch (_: Exception) {}
        }
        return@withContext ok
    }

    // ── solve one domain ──
    // Strategy: WebView starts hidden. Every 150ms:
    //   1. Check for cf_clearance cookie → captured, done
    //   2. After 4s, dispatch Tab + Space (presses the checkbox)
    //   3. After 8s with no cookie, reveal the WebView so user can tap
    //   4. After 20s, timeout
    private suspend fun solveOne(
        ctx: Context,
        web: WebView,
        webWrap: FrameLayout,
        domain: String,
        isCancelled: () -> Boolean
    ): Boolean = suspendCancellableCoroutine { cont ->
        val url = "https://$domain"
        val handler = Handler(Looper.getMainLooper())
        val start = System.currentTimeMillis()
        val maxMs = 20_000L
        var revealed = false
        var pressed = 0

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
                    BCLog.d("[CF Shield] $domain cookie captured")
                    if (cont.isActive) cont.resume(true)
                    return
                }

                val elapsed = System.currentTimeMillis() - start

                // after 4s, try pressing the checkbox via real key events
                if (elapsed > 4_000 && pressed < 3 && (elapsed % 4_000 < 200)) {
                    pressed++
                    pressCheckbox(web)
                    BCLog.d("[CF Shield] $domain press #$pressed")
                }

                // after 8s, reveal the WebView so user can manually tap
                if (!revealed && elapsed > 8_000) {
                    revealed = true
                    webWrap.visibility = View.VISIBLE
                    BCLog.d("[CF Shield] $domain revealed for manual solve")
                }

                if (elapsed > maxMs) {
                    if (cont.isActive) cont.resume(false)
                    return
                }
                handler.postDelayed(this, 150L)
            }
        }
        handler.post(poll)

        cont.invokeOnCancellation {
            handler.removeCallbacks(poll)
        }
    }

    // ── dispatch Tab + Space to press the CF checkbox ──
    // Real key events carry isTrusted=true, so Cloudflare
    // treats them as user input, not a scripted click.
    private fun pressCheckbox(web: WebView) {
        try {
            web.requestFocus()
            val down1 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB)
            val up1 = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB)
            val down2 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)
            val up2 = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE)
            web.dispatchKeyEvent(down1)
            web.dispatchKeyEvent(up1)
            web.dispatchKeyEvent(down2)
            web.dispatchKeyEvent(up2)
        } catch (e: Exception) {
            BCLog.d("[CF Shield] pressCheckbox failed: ${e.message}")
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
