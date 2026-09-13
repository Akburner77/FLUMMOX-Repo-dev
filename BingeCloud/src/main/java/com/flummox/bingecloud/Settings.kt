package com.flummox.bingecloud

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

object Settings {

    // ── Storage keys ──
    const val K_CONCURRENCY = "bingecloud_concurrency"
    const val K_CF_DOMAINS = "bingecloud_cf_domains"
    const val K_CF_COOKIE_PREFIX = "bingecloud_cf_cookie_"
    const val K_FEBBOX_TOKEN = "bingecloud_febbox_token"
    const val K_FEBBOX_EMAIL = "bingecloud_febbox_email"
    const val K_SRC_VM = "bingecloud_src_vm"
    const val K_SRC_MD = "bingecloud_src_md"
    const val K_SRC_HDH = "bingecloud_src_hdh"
    const val K_SRC_FEBBOX = "bingecloud_src_febbox"
    const val K_QUALITY = "bingecloud_quality"
    const val K_ROW_TRENDING_MOVIES = "bingecloud_row_trending_movies"
    const val K_ROW_TRENDING_SERIES = "bingecloud_row_trending_series"
    const val K_ROW_POPULAR_MOVIES = "bingecloud_row_popular_movies"
    const val K_ROW_POPULAR_SERIES = "bingecloud_row_popular_series"
    const val K_ROW_TVDB_MOVIES = "bingecloud_row_tvdb_movies"
    const val K_ROW_TVDB_SERIES = "bingecloud_row_tvdb_series"
    const val K_ROW_TOP_ANIME = "bingecloud_row_top_anime"
    const val K_ROW_AIRING_ANIME = "bingecloud_row_airing_anime"
    const val K_ROW_UPCOMING_ANIME = "bingecloud_row_upcoming_anime"
    const val K_ROW_TOP_ANIME_MOVIES = "bingecloud_row_top_anime_movies"
    const val K_ROW_TOP_ANIME_SERIES = "bingecloud_row_top_anime_series"
    const val K_ROW_MOST_POPULAR_ANIME = "bingecloud_row_most_popular_anime"
    const val K_ROW_MOST_FAV_ANIME = "bingecloud_row_most_fav_anime"
    const val K_ROW_BEST_2020S = "bingecloud_row_best_2020s"

    val DEFAULT_CF_DOMAINS = listOf("anidao.to")

    // ── Getters ──
    fun getConcurrency(): Int = (getKey<Int>(K_CONCURRENCY) ?: 15).coerceIn(1, 50)
    fun getCfDomains(): List<String> =
        (getKey<String>(K_CF_DOMAINS) ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }
    fun getCookieForDomain(domain: String): String? =
        getKey<String>(K_CF_COOKIE_PREFIX + domain)?.takeIf { it.isNotBlank() }
    fun saveCookieForDomain(domain: String, cookie: String) {
        setKey(K_CF_COOKIE_PREFIX + domain, cookie)
        val cur = getCfDomains().toMutableSet().also { it.add(domain) }
        setKey(K_CF_DOMAINS, cur.joinToString(","))
    }
    fun clearCookieForDomain(domain: String) {
        setKey(K_CF_COOKIE_PREFIX + domain, "")
        val cur = getCfDomains().toMutableSet().also { it.remove(domain) }
        setKey(K_CF_DOMAINS, cur.joinToString(","))
    }
    fun getFebBoxToken(): String = getKey<String>(K_FEBBOX_TOKEN) ?: ""
    fun saveFebBoxToken(t: String) { setKey(K_FEBBOX_TOKEN, t) }
    fun clearFebBoxToken() { setKey(K_FEBBOX_TOKEN, "") }
    fun isSrcVm(): Boolean = getKey<Boolean>(K_SRC_VM) ?: true
    fun isSrcMd(): Boolean = getKey<Boolean>(K_SRC_MD) ?: true
    fun isSrcHdh(): Boolean = getKey<Boolean>(K_SRC_HDH) ?: true
    fun isSrcFebBox(): Boolean = getKey<Boolean>(K_SRC_FEBBOX) ?: true
    fun getQualityPref(): String = getKey<String>(K_QUALITY) ?: "Auto"
    fun isRowEnabled(key: String): Boolean = getKey<Boolean>(key) ?: true

    // ── Sky theme palette ──
    private const val BG = 0xFF0A0D14.toInt()
    private const val HEADER_TOP = 0xFF101822.toInt()
    private const val HEADER_BOTTOM = 0xFF0A0D14.toInt()
    private const val CARD = 0xFF11151F.toInt()
    private const val BORDER = 0xFF1E2430.toInt()
    private const val ROW = 0xFF161B27.toInt()
    private const val INPUT = 0xFF0C1019.toInt()
    private const val ACCENT = 0xFF38BDF8.toInt()
    private const val ACCENT_BG = 0x2238BDF8
    private const val TEXT = 0xFFF0F4F8.toInt()
    private const val SUBTEXT = 0xFF7A8798.toInt()
    private const val GREEN = 0xFF4ADE80.toInt()
    private const val RED = 0xFFF87171.toInt()

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radiusDp: Int, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    private fun cardBg(ctx: Context): GradientDrawable = GradientDrawable().apply {
        setColor(CARD)
        cornerRadius = dp(ctx, 14).toFloat()
        setStroke(dp(ctx, 1), BORDER)
    }

    private fun headerBg(ctx: Context): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(HEADER_TOP, HEADER_BOTTOM)
        ).apply { cornerRadius = 0f }

    private class Card(
        val root: LinearLayout,
        val body: LinearLayout,
        val statusBadge: TextView,
        val chevron: TextView
    )

    private fun buildCard(
        ctx: Context, emoji: String, title: String,
        subtitle: String? = null, badge: String? = null,
        badgeColor: Int = ACCENT, expanded: Boolean = false
    ): Card {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(ctx)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            isClickable = true
        }
        header.addView(TextView(ctx).apply {
            text = emoji; textSize = 18f; setPadding(0, 0, dp(ctx, 12), 0)
        })
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = title; setTextColor(TEXT); textSize = 16f
        })
        if (!subtitle.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = subtitle; setTextColor(SUBTEXT); textSize = 12f
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        header.addView(col)

        val status = TextView(ctx).apply {
            text = badge ?: ""
            setTextColor(badgeColor)
            textSize = 12f
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
            visibility = if (badge.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        header.addView(status)

        val chev = TextView(ctx).apply {
            text = if (expanded) "▾" else "▸"
            setTextColor(SUBTEXT); textSize = 14f
            setPadding(dp(ctx, 8), 0, 0, 0)
        }
        header.addView(chev)

        root.addView(header)

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 12))
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        root.addView(body)

        header.setOnClickListener {
            val showing = body.visibility == View.VISIBLE
            body.visibility = if (showing) View.GONE else View.VISIBLE
            chev.text = if (showing) "▸" else "▾"
        }

        return Card(root, body, status, chev)
    }

    // ── Row builders ──
    private fun toggleRow(
        ctx: Context, label: String, desc: String?, initial: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(ROW, 10, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 6) }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = label; setTextColor(TEXT); textSize = 14f
        })
        if (!desc.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = desc; setTextColor(SUBTEXT); textSize = 11f
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        row.addView(col)
        val sw = Switch(ctx); sw.isChecked = initial
        sw.setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onChange(v) }
        row.addView(sw)
        return row
    }

    private fun stepperRow(
        ctx: Context, label: String, desc: String?, min: Int, max: Int,
        initial: Int, onChange: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(ROW, 10, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 6) }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = label; setTextColor(TEXT); textSize = 14f
        })
        if (!desc.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = desc; setTextColor(SUBTEXT); textSize = 11f
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        row.addView(col)
        var current = initial
        val valTxt = TextView(ctx).apply {
            text = "$current"; setTextColor(ACCENT); textSize = 20f
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
        }
        fun mkBtn(sym: String, delta: Int) = Button(ctx).apply {
            text = sym; textSize = 18f; setTextColor(TEXT)
            background = bg(INPUT, 20, ctx)
            minWidth = dp(ctx, 40); minHeight = dp(ctx, 40)
            setOnClickListener {
                current = (current + delta).coerceIn(min, max)
                valTxt.text = "$current"
                onChange(current)
            }
        }
        row.addView(mkBtn("−", -1)); row.addView(valTxt); row.addView(mkBtn("+", 1))
        return row
    }

    private fun actionRow(
        ctx: Context, label: String, desc: String?,
        buttonText: String, buttonColor: Int = ACCENT,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(ROW, 10, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 6) }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = label; setTextColor(TEXT); textSize = 14f
        })
        if (!desc.isNullOrBlank()) {
            col.addView(TextView(ctx).apply {
                text = desc; setTextColor(SUBTEXT); textSize = 11f
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        row.addView(col)
        row.addView(Button(ctx).apply {
            text = buttonText; textSize = 13f
            setTextColor(buttonColor)
            background = bg(if (buttonColor == RED) 0x22F87171 else ACCENT_BG, 18, ctx)
            setPadding(dp(ctx, 16), dp(ctx, 6), dp(ctx, 16), dp(ctx, 6))
            minHeight = 0; minWidth = 0
            setOnClickListener { onClick() }
        })
        return row
    }

    private fun domainRow(
        ctx: Context, domain: String, hasCookie: Boolean,
        onOpen: () -> Unit, onClear: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(ROW, 10, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 6) }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = domain; setTextColor(TEXT); textSize = 14f
        })
        col.addView(TextView(ctx).apply {
            text = if (hasCookie) "✓ Solved" else "Not solved"
            setTextColor(if (hasCookie) GREEN else SUBTEXT)
            textSize = 11f; setPadding(0, dp(ctx, 2), 0, 0)
        })
        row.addView(col)
        row.addView(Button(ctx).apply {
            text = if (hasCookie) "Reopen" else "Open"
            textSize = 12f; setTextColor(ACCENT)
            background = bg(ACCENT_BG, 18, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6))
            minHeight = 0; minWidth = 0
            setOnClickListener { onOpen() }
        })
        if (hasCookie) {
            row.addView(Button(ctx).apply {
                text = "✕"
                textSize = 12f; setTextColor(RED)
                background = bg(0x22F87171, 18, ctx)
                setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
                minHeight = 0; minWidth = 0
                setOnClickListener { onClear() }
            })
        }
        return row
    }

    private fun labelBlock(ctx: Context, title: String, subtitle: String?): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 10))
            addView(TextView(ctx).apply {
                text = title; setTextColor(TEXT); textSize = 14f
            })
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(ctx).apply {
                    text = subtitle; setTextColor(SUBTEXT); textSize = 11f
                    setPadding(0, dp(ctx, 2), 0, 0)
                })
            }
        }

    // ── Main dialog ──
    fun showSettingsDialog(ctx: Context, onSaved: () -> Unit) {
        lateinit var dialog: AlertDialog
        val refresh: () -> Unit = {
            dialog.dismiss()
            showSettingsDialog(ctx, onSaved)
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(BG, 0, ctx)
        }

        // ── Header ──
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = headerBg(ctx)
            setPadding(dp(ctx, 22), dp(ctx, 26), dp(ctx, 22), dp(ctx, 24))
            addView(TextView(ctx).apply {
                text = "BingeCloud"; setTextColor(TEXT); textSize = 24f
            })
            addView(TextView(ctx).apply {
                text = "Configure sources, catalogs & cookies"
                setTextColor(ACCENT); textSize = 12f
                setPadding(0, dp(ctx, 4), 0, 0)
            })
        })

        val scroll = ScrollView(ctx)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 14), dp(ctx, 12), dp(ctx, 24))
        }
        scroll.addView(body)

        // ── 1. Performance ──
        run {
            val c = buildCard(ctx, "⚡", "Performance", "Control scraping speed")
            c.body.addView(stepperRow(
                ctx, "Concurrency", "Providers running in parallel",
                1, 50, getConcurrency()
            ) { setKey(K_CONCURRENCY, it) })
            body.addView(c.root)
        }

        // ── 2. Cloudflare ──
        run {
            val saved = getCfDomains()
            val c = buildCard(
                ctx, "🛡️", "Cloudflare Bypass",
                subtitle = if (saved.isEmpty()) "No domains configured"
                else "${saved.size} domain(s)",
                badge = if (saved.isNotEmpty()) "✓ ${saved.size}" else null,
                badgeColor = GREEN
            )
            c.body.addView(labelBlock(ctx, "Protected sites",
                "Open a WebView, solve the challenge, tap Save Cookies."))

            val allDomains = (DEFAULT_CF_DOMAINS + saved).distinct()
            for (domain in allDomains) {
                val has = getCookieForDomain(domain) != null
                c.body.addView(domainRow(
                    ctx, domain, has,
                    onOpen = { openCfWebView(ctx, "https://$domain", domain) },
                    onClear = {
                        clearCookieForDomain(domain); refresh()
                        Toast.makeText(ctx, "Cleared $domain", Toast.LENGTH_SHORT).show()
                    }
                ))
            }

            c.body.addView(actionRow(
                ctx, "Add custom domain", "Open any URL to solve CF",
                "Add"
            ) { openCfWebView(ctx, "https://www.febbox.com", "febbox.com") })
            body.addView(c.root)
        }

        // ── 3. FebBox ──
        run {
            val has = getFebBoxToken().isNotBlank()
            val c = buildCard(
                ctx, "🔑", "FebBox Account",
                subtitle = if (has) "Signed in" else "Not signed in",
                badge = if (has) "✓ Active" else "○ None",
                badgeColor = if (has) GREEN else SUBTEXT
            )
            c.body.addView(labelBlock(
                ctx,
                if (has) "You're signed in"
                else "Sign in to unlock ShowBox/FebBox sources",
                if (has) "Token saved — nothing else to do."
                else "A WebView will open. Log in with your FebBox account and tap Save Token."
            ))
            c.body.addView(actionRow(
                ctx, "Sign in / Refresh", "Opens febbox.com login",
                if (has) "Re-login" else "Sign in"
            ) { openFebBoxLogin(ctx, onSaved = { onSaved(); refresh() }) })
            if (has) {
                c.body.addView(actionRow(
                    ctx, "Sign out", "Removes saved session",
                    "Sign out", buttonColor = RED
                ) {
                    clearFebBoxToken(); refresh()
                    Toast.makeText(ctx, "Signed out", Toast.LENGTH_SHORT).show()
                })
            }
            body.addView(c.root)
        }

        // ── 4. Sources ──
        run {
            val on = listOf(isSrcVm(), isSrcMd(), isSrcHdh(), isSrcFebBox()).count { it }
            val c = buildCard(
                ctx, "📡", "Sources", "$on of 4 enabled",
                badge = "$on/4"
            )
            c.body.addView(toggleRow(ctx, "VegaMovies", null, isSrcVm()) { setKey(K_SRC_VM, it) })
            c.body.addView(toggleRow(ctx, "MoviesDrive", null, isSrcMd()) { setKey(K_SRC_MD, it) })
            c.body.addView(toggleRow(ctx, "HDhub4u", null, isSrcHdh()) { setKey(K_SRC_HDH, it) })
            c.body.addView(toggleRow(ctx, "FebBox", "Requires sign-in above", isSrcFebBox()) { setKey(K_SRC_FEBBOX, it) })
            body.addView(c.root)
        }

        // ── 5. Quality ──
        run {
            val cur = getQualityPref()
            val c = buildCard(ctx, "🎞️", "Preferred Quality", "Current: $cur")
            val options = listOf("Auto", "480p", "720p", "1080p", "2K (1440p)", "4K (2160p)")
            val display = options.map { it.substringBefore(" (").trim() }
            val spinner = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, options)
                val idx = display.indexOf(cur).coerceAtLeast(0)
                setSelection(idx)
                background = bg(ROW, 10, ctx)
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 4) }
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        setKey(K_QUALITY, display[pos])
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            }
            c.body.addView(spinner)
            body.addView(c.root)
        }

        // ── 6. Homepage ──
        run {
            val rowsSpec = listOf(
                Triple("Trending Movies", K_ROW_TRENDING_MOVIES, "TMDB"),
                Triple("Trending Series", K_ROW_TRENDING_SERIES, "TMDB"),
                Triple("Popular Movies", K_ROW_POPULAR_MOVIES, "TMDB"),
                Triple("Popular Series", K_ROW_POPULAR_SERIES, "TMDB"),
                Triple("TVDB Trending Movies", K_ROW_TVDB_MOVIES, "TVDB"),
                Triple("TVDB Trending Series", K_ROW_TVDB_SERIES, "TVDB"),
                Triple("Top Anime", K_ROW_TOP_ANIME, "MAL"),
                Triple("Airing Now", K_ROW_AIRING_ANIME, "MAL"),
                Triple("Upcoming Anime", K_ROW_UPCOMING_ANIME, "MAL"),
                Triple("Top Anime Movies", K_ROW_TOP_ANIME_MOVIES, "MAL"),
                Triple("Top Anime Series", K_ROW_TOP_ANIME_SERIES, "MAL"),
                Triple("Most Popular Anime", K_ROW_MOST_POPULAR_ANIME, "MAL"),
                Triple("Most Favorited Anime", K_ROW_MOST_FAV_ANIME, "MAL"),
                Triple("Best of 2020s", K_ROW_BEST_2020S, "MAL"),
            )
            val on = rowsSpec.count { isRowEnabled(it.second) }
            val c = buildCard(
                ctx, "☁️", "Homepage", "$on of ${rowsSpec.size} sections",
                badge = "$on/${rowsSpec.size}"
            )
            for ((label, key, source) in rowsSpec) {
                c.body.addView(toggleRow(ctx, label, source, isRowEnabled(key)) { setKey(key, it) })
            }
            body.addView(c.root)
        }

        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        dialog = AlertDialog.Builder(ctx)
            .setView(root)
            .setPositiveButton("Save & Close") { _, _ -> onSaved() }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    // ── Cloudflare WebView ──
    @SuppressLint("SetJavaScriptEnabled")
    private fun openCfWebView(ctx: Context, startUrl: String, domain: String) {
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(BG, 0, ctx)
        }
        val urlBar = TextView(ctx).apply {
            text = startUrl
            setTextColor(SUBTEXT); textSize = 11f
            background = bg(HEADER_TOP, 0, ctx)
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
            maxLines = 1
        }
        layout.addView(urlBar)

        val wvWrap = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    urlBar.text = url ?: startUrl
                }
            }
            webChromeClient = WebChromeClient()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            loadUrl(startUrl)
        }
        wvWrap.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        layout.addView(wvWrap)

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(HEADER_TOP, 0, ctx)
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        }
        bar.addView(Button(ctx).apply {
            text = "Close"; textSize = 13f; setTextColor(TEXT)
            background = bg(ROW, 20, ctx)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(ctx, 8) }
            setOnClickListener { dlg.dismiss() }
        })
        bar.addView(Button(ctx).apply {
            text = "🍪  Save Cookies"; textSize = 13f; setTextColor(TEXT)
            background = bg(ACCENT_BG, 20, ctx)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(ctx, 8) }
            setOnClickListener {
                val cookie = CookieManager.getInstance().getCookie(startUrl) ?: ""
                if (cookie.isBlank()) {
                    Toast.makeText(ctx, "No cookies — solve challenge first",
                        Toast.LENGTH_SHORT).show()
                } else {
                    saveCookieForDomain(domain, cookie)
                    Toast.makeText(ctx, "✓ Saved for $domain", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                }
            }
        })
        layout.addView(bar)

        dlg.setContentView(layout)
        dlg.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dlg.setOnDismissListener {
            try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
        }
        dlg.show()
    }

    // ── FebBox login WebView ──
    @SuppressLint("SetJavaScriptEnabled")
    private fun openFebBoxLogin(ctx: Context, onSaved: () -> Unit) {
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(BG, 0, ctx)
        }
        val banner = TextView(ctx).apply {
            text = "Sign in to FebBox. Your session is stored locally."
            setTextColor(SUBTEXT); textSize = 12f
            background = bg(HEADER_TOP, 0, ctx)
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        }
        layout.addView(banner)

        val wvWrap = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            loadUrl("https://www.febbox.com/login")
        }
        wvWrap.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        layout.addView(wvWrap)

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(HEADER_TOP, 0, ctx)
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        }
        bar.addView(Button(ctx).apply {
            text = "Close"; textSize = 13f; setTextColor(TEXT)
            background = bg(ROW, 20, ctx)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(ctx, 8) }
            setOnClickListener { dlg.dismiss() }
        })
        bar.addView(Button(ctx).apply {
            text = "🔑  Save Token"; textSize = 13f; setTextColor(TEXT)
            background = bg(ACCENT_BG, 20, ctx)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(ctx, 8) }
            setOnClickListener {
                val cookie = CookieManager.getInstance().getCookie("https://www.febbox.com") ?: ""
                val ui = cookie.split(";").map { it.trim() }
                    .firstOrNull { it.startsWith("ui=") }
                    ?.substringAfter("ui=")?.trim()
                if (ui.isNullOrBlank()) {
                    Toast.makeText(ctx, "Not signed in yet — complete login first",
                        Toast.LENGTH_SHORT).show()
                } else {
                    saveFebBoxToken(ui)
                    Toast.makeText(ctx, "✓ Signed in", Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                    onSaved()
                }
            }
        })
        layout.addView(bar)

        dlg.setContentView(layout)
        dlg.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dlg.setOnDismissListener {
            try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
        }
        dlg.show()
    }
}
