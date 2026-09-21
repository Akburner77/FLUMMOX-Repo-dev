package com.flummox.bingecloud

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener

// ═══════════════════════════════════════════════════════════════
// ── Cloudflare interactive solver ──
// Opens a WebView inside a full-screen dialog that starts off-screen
// for a grace period. If a protection challenge is detected (or the
// grace expires), the dialog slides into view so the user can solve
// it. When the real page loads, its HTML and the session cookie are
// returned to the caller.
// ═══════════════════════════════════════════════════════════════

data class CfResult(val html: String, val cookie: String)

object CfSolverDialog {

    private const val GRACE_MS = 5_000L
    private const val TOTAL_TIMEOUT_MS = 180_000L
    private const val OFFSCREEN_X = -12000

    private const val CF_UA =
        "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    suspend fun resolve(activity: Activity, url: String): CfResult? =
        withContext(Dispatchers.Main) { solve(activity, url) }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solve(activity: Activity, url: String): CfResult? {
        if (activity.isFinishing) return null
        if (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed) return null

        val deferred = CompletableDeferred<CfResult?>()

        val dlg = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }

        // ── UI skeleton ──
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0D14"))
        }

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F1520"))
            setPadding(28, 32, 28, 32)
        }
        val title = TextView(activity).apply {
            text = "Verifying…"
            setTextColor(Color.parseColor("#E6EDF5"))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(title)
        val cancelBtn = Button(activity).apply {
            text = "Cancel"
            textSize = 12f
            setTextColor(Color.parseColor("#F87171"))
            isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(24, 12, 24, 12)
        }
        topBar.addView(cancelBtn)
        root.addView(topBar)

        val hint = TextView(activity).apply {
            text = ""
            setTextColor(Color.parseColor("#8296AD"))
            textSize = 11f
            setBackgroundColor(Color.parseColor("#0F1520"))
            setPadding(28, 0, 28, 20)
        }
        root.addView(hint)

        val wvWrap = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val wv = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = CF_UA
            addJavascriptInterface(CfBridge { html ->
                if (!deferred.isCompleted) {
                    val cookie = try {
                        CookieManager.getInstance().getCookie(url) ?: ""
                    } catch (_: Exception) { "" }
                    deferred.complete(CfResult(html, cookie))
                }
            }, "CfBridge")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    view?.evaluateJavascript(JS_INSTALL_OBSERVER, null)
                    view?.evaluateJavascript(
                        "window.__cf_push && window.__cf_push();", null
                    )
                }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        wvWrap.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(wvWrap)

        dlg.setContentView(root)

        dlg.setOnDismissListener {
            try { wv.stopLoading() } catch (_: Exception) {}
            try { wv.destroy() } catch (_: Exception) {}
            if (!deferred.isCompleted) deferred.complete(null)
        }

        var revealed = false
        fun reveal() {
            if (revealed) return
            revealed = true
            dlg.window?.let { w ->
                val a = w.attributes
                a.x = 0
                w.attributes = a
            }
            title.text = "Complete the challenge"
            hint.text = "Tap the checkbox, then wait for the page to load."
        }

        val graceHandler = Handler(Looper.getMainLooper())
        val graceRunnable = Runnable { reveal() }

        val pollHandler = Handler(Looper.getMainLooper())
        val pollRunnable = object : Runnable {
            override fun run() {
                if (deferred.isCompleted) return
                try {
                    wv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                        val html = unwrapJsonString(raw)
                        if (html.isNotBlank() && isCfChallenge(html)) reveal()
                    }
                } catch (_: Exception) {}
                pollHandler.postDelayed(this, 700L)
            }
        }

        cancelBtn.setOnClickListener { dlg.dismiss() }

        dlg.show()

        // window is live now — push it off-screen and load
        dlg.window?.let { w ->
            w.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            w.setBackgroundDrawableResource(android.R.color.black)
            val a = w.attributes
            a.width = WindowManager.LayoutParams.MATCH_PARENT
            a.height = WindowManager.LayoutParams.MATCH_PARENT
            a.x = OFFSCREEN_X
            a.y = 0
            w.attributes = a
        }

        graceHandler.postDelayed(graceRunnable, GRACE_MS)
        pollHandler.postDelayed(pollRunnable, 500L)

        wv.loadUrl(url)

        val result = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { deferred.await() }

        graceHandler.removeCallbacks(graceRunnable)
        pollHandler.removeCallbacks(pollRunnable)
        try { if (dlg.isShowing) dlg.dismiss() } catch (_: Exception) {}
        return result
    }

    private fun unwrapJsonString(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val t = raw.trim()
        if (t == "null" || t.length < 2) return ""
        return try {
            when (val v = JSONTokener(t).nextValue()) {
                is String -> v
                else -> ""
            }
        } catch (_: Exception) { "" }
    }

    private const val JS_INSTALL_OBSERVER = """
    (function() {
        if (window.__cf_observer_installed) return;
        window.__cf_observer_installed = true;
        window.__cf_push = function() {
            try {
                var html = document.documentElement.outerHTML || "";
                CfBridge.onHtml(html);
            } catch (e) {}
        };
        try {
            var obs = new MutationObserver(function() { window.__cf_push(); });
            obs.observe(document.documentElement, { childList: true, subtree: true });
        } catch (e) {}
        setTimeout(window.__cf_push, 200);
        setTimeout(window.__cf_push, 900);
        setTimeout(window.__cf_push, 2000);
        setTimeout(window.__cf_push, 3500);
    })();
    """
}

class CfBridge(private val onHtml: (String) -> Unit) {
    @JavascriptInterface
    fun onHtml(html: String) {
        if (html.length < 800) return
        if (isCfChallenge(html)) return
        onHtml(html)
    }
}
