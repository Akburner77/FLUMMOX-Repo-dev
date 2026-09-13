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
import android.widget.EditText
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
    const val K_ROW_TOP_ANIME = "bingecloud_row_top_anime"
    const val K_ROW_AIRING_ANIME = "bingecloud_row_airing_anime"
    const val K_ROW_TOP_ANIME_MOVIES = "bingecloud_row_top_anime_movies"
    const val K_ROW_MOST_POPULAR_ANIME = "bingecloud_row_most_popular_anime"

    val DEFAULT_CF_DOMAINS = listOf(
        "febbox.com",
        "hubcloud.ist",
        "hubcloud.cx",
        "vcloud.fit"
    )

    // ── Getters ──
    fun getConcurrency(): Int = (getKey<Int>(K_CONCURRENCY) ?: 15).coerceIn(1, 50)
    fun getCfDomains(): List<String> =
        (getKey<String>(K_CF_DOMAINS) ?: "")
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
    fun getCookieForDomain(domain: String): String? =
        getKey<String>(K_CF_COOKIE_PREFIX + domain)?.takeIf { it.isNotBlank() }
    fun saveCookieForDomain(domain: String, cookie: String) {
        setKey(K_CF_COOKIE_PREFIX + domain, cookie)
        val current = getCfDomains().toMutableSet()
        current.add(domain)
        setKey(K_CF_DOMAINS, current.joinToString(","))
    }
    fun clearCookieForDomain(domain: String) {
        setKey(K_CF_COOKIE_PREFIX + domain, "")
        val current = getCfDomains().toMutableSet()
        current.remove(domain)
        setKey(K_CF_DOMAINS, current.joinToString(","))
    }
    fun getFebBoxToken(): String = getKey<String>(K_FEBBOX_TOKEN) ?: ""
    fun getFebBoxEmail(): String = getKey<String>(K_FEBBOX_EMAIL) ?: ""
    fun saveFebBoxToken(token: String) { setKey(K_FEBBOX_TOKEN, token) }
    fun saveFebBoxEmail(email: String) { setKey(K_FEBBOX_EMAIL, email) }
    fun clearFebBoxToken() { setKey(K_FEBBOX_TOKEN, "") }
    fun isSrcVm(): Boolean = getKey<Boolean>(K_SRC_VM) ?: true
    fun isSrcMd(): Boolean = getKey<Boolean>(K_SRC_MD) ?: true
    fun isSrcHdh(): Boolean = getKey<Boolean>(K_SRC_HDH) ?: true
    fun isSrcFebBox(): Boolean = getKey<Boolean>(K_SRC_FEBBOX) ?: true
    fun getQualityPref(): String = getKey<String>(K_QUALITY) ?: "Auto"
    fun isRowEnabled(key: String): Boolean = getKey<Boolean>(key) ?: true

    // ── Colors ──
    private const val BG_DARK = 0xFF0A0A0F.toInt()
    private const val HEADER_BG = 0xFF14141C.toInt()
    private const val CARD_BG = 0xFF16161F.toInt()
    private const val ROW_BG = 0xFF1D1D28.toInt()
    private const val INPUT_BG = 0xFF0E0E15.toInt()
    private const val ACCENT = 0xFFA78BFA.toInt()
    private const val ACCENT_BG = 0x33A78BFA
    private const val TEXT = 0xFFF4F4F6.toInt()
    private const val SUBTEXT = 0xFF8E8EA0.toInt()
    private const val GREEN = 0xFF34D399.toInt()
    private const val RED = 0xFFF87171.toInt()
    private const val AMBER = 0xFFFBBF24.toInt()

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radiusDp: Int, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    // ── Card with collapsible body ──
    private class Card(
        val root: LinearLayout,
        val header: LinearLayout,
        val body: LinearLayout,
        val statusBadge: TextView,
        val chevron: TextView
    )

    private fun buildCard(
        ctx: Context,
        emoji: String,
        title: String,
        subtitle: String? = null,
        expanded: Boolean = false
    ): Card {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(CARD_BG, 16, ctx)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(HEADER_BG, 16, ctx)
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            isClickable = true
        }
        header.addView(TextView(ctx).apply {
            text = emoji
            textSize = 20f
            setPadding(0, 0, dp(ctx, 12), 0)
        })
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(ctx).apply {
            text = title
            setTextColor(TEXT)
            textSize = 16f
        })
        if (!subtitle.isNullOrBlank()) {
            titleCol.addView(TextView(ctx).apply {
                text = subtitle
                setTextColor(SUBTEXT)
                textSize = 12f
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        header.addView(titleCol)

        val statusBadge = TextView(ctx).apply {
            text = ""
            setTextColor(GREEN)
            textSize = 12f
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
            visibility = View.GONE
        }
        header.addView(statusBadge)

        val chevron = TextView(ctx).apply {
            text = if (expanded) "▾" else "▸"
            setTextColor(SUBTEXT)
            textSize = 16f
            setPadding(dp(ctx, 8), 0, 0, 0)
        }
        header.addView(chevron)

        root.addView(header)

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 8), dp(ctx, 12), dp(ctx, 8), dp(ctx, 12))
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        root.addView(body)

        header.setOnClickListener {
            val showing = body.visibility == View.VISIBLE
            body.visibility = if (showing) View.GONE else View.VISIBLE
            chevron.text = if (showing) "▸" else "▾"
        }

        return Card(root, header, body, statusBadge, chevron)
    }

    // ── Row builders ──
    private fun toggleRow(
        ctx: Context, label: String, desc: String?, initial: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(ROW_BG, 12, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
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
                text = desc; setTextColor(SUBTEXT); textSize = 12f
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        row.addView(col)
        val sw = Switch(ctx)
        sw.isChecked = initial
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
            background = bg(ROW_BG, 12, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
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
                text = desc; setTextColor(SUBTEXT); textSize = 12f
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        row.addView(col)

        var current = initial
        val valueText = TextView(ctx).apply {
            text = "$current"; setTextColor(ACCENT); textSize = 22f
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
        }
        fun mkBtn(symbol: String, delta: Int) = Button(ctx).apply {
            text = symbol; textSize = 20f; setTextColor(TEXT)
            background = bg(INPUT_BG, 24, ctx)
            minWidth = dp(ctx, 44); minHeight = dp(ctx, 44)
            setOnClickListener {
                current = (current + delta).coerceIn(min, max)
                valueText.text = "$current"
                onChange(current)
            }
        }
        row.addView(mkBtn("−", -1))
        row.addView(valueText)
        row.addView(mkBtn("+", 1))
        return row
    }

    private fun actionRow(
        ctx: Context, label: String, desc: String?,
        buttonText: String, onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(ROW_BG, 12, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
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
                text = desc; setTextColor(SUBTEXT); textSize = 12f
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        row.addView(col)
        row.addView(Button(ctx).apply {
            text = buttonText
            textSize = 13f
            setTextColor(ACCENT)
            background = bg(ACCENT_BG, 20, ctx)
            setPadding(dp(ctx, 16), dp(ctx, 6), dp(ctx, 16), dp(ctx, 6))
            minHeight = 0
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
            background = bg(ROW_BG, 12, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
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
            text = if (hasCookie) "✓ Saved" else "Not solved"
            setTextColor(if (hasCookie) GREEN else SUBTEXT)
            textSize = 11f
            setPadding(0, dp(ctx, 2), 0, 0)
        })
        row.addView(col)
        row.addView(Button(ctx).apply {
            text = if (hasCookie) "Reopen" else "Open"
            textSize = 12f
            setTextColor(ACCENT)
            background = bg(ACCENT_BG, 20, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6))
            minHeight = 0; minWidth = 0
            setOnClickListener { onOpen() }
        })
        if (hasCookie) {
            row.addView(Button(ctx).apply {
                text = "✕"
                textSize = 12f
                setTextColor(RED)
                background = bg(0x22F87171, 20, ctx)
                setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
                minHeight = 0; minWidth = 0
                setOnClickListener { onClear() }
            })
        }
        return row
    }

    // ── Main dialog ──
    fun showSettingsDialog(ctx: Context, onSaved: () -> Unit) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(BG_DARK, 0, ctx)
        }

        // Header
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(HEADER_BG, 0, ctx)
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 20))
            addView(TextView(ctx).apply {
                text = "BingeCloud"
                setTextColor(TEXT); textSize = 24f
            })
            addView(TextView(ctx).apply {
                text = "Extension Settings"
                setTextColor(ACCENT); textSize = 13f
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        })

        val scroll = ScrollView(ctx)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 24))
        }
        scroll.addView(body)

        // ── 1. Performance ──
        run {
            val c = buildCard(ctx, "⚡", "Performance",
                "Control scraping speed")
            c.body.addView(stepperRow(
                ctx, "Concurrency",
                "Providers running in parallel", 1, 50, getConcurrency()
            ) { setKey(K_CONCURRENCY, it) })
            body.addView(c.root)
        }

        // ── 2. Cloudflare ──
        run {
            val savedDomains = getCfDomains()
            val c = buildCard(ctx, "🛡️", "Cloudflare Bypass",
                if (savedDomains.isEmpty()) "No cookies saved"
                else "${savedDomains.size} domain(s) solved")

            c.statusBadge.apply {
                if (savedDomains.isNotEmpty()) {
                    text = "✓ ${savedDomains.size}"
                    visibility = View.VISIBLE
                }
            }

            c.body.addView(TextView(ctx).apply {
                text = "Tap any domain to open a WebView. Solve the challenge, then tap Save Cookies."
                setTextColor(SUBTEXT); textSize = 12f
                setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 10))
            })

            val allDomains = (DEFAULT_CF_DOMAINS + savedDomains).distinct()
            for (domain in allDomains) {
                val hasCookie = getCookieForDomain(domain) != null
                c.body.addView(domainRow(
                    ctx, domain, hasCookie,
                    onOpen = { openWebViewDialog(ctx, "https://$domain", domain) },
                    onClear = {
                        clearCookieForDomain(domain)
                        onSaved()
                        Toast.makeText(ctx, "Cleared $domain", Toast.LENGTH_SHORT).show()
                    }
                ))
            }

            c.body.addView(actionRow(
                ctx, "Add custom domain", "Opens WebView to another URL",
                "Open"
            ) {
                openWebViewDialog(ctx, "https://www.febbox.com", "febbox.com")
            })
            body.addView(c.root)
        }

        // ── 3. FebBox Account ──
        run {
            val hasToken = getFebBoxToken().isNotBlank()
            val c = buildCard(ctx, "🔑", "FebBox Account",
                if (hasToken) "Logged in" else "Not logged in")

            c.statusBadge.apply {
                text = if (hasToken) "✓ Active" else "○ None"
                setTextColor(if (hasToken) GREEN else SUBTEXT)
                visibility = View.VISIBLE
            }

            c.body.addView(TextView(ctx).apply {
                text = if (hasToken)
                    "Token: ${getFebBoxToken().take(20)}…"
                else
                    "Log in through the WebView. Token saves automatically."
                setTextColor(if (hasToken) GREEN else SUBTEXT)
                textSize = 12f
                setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 10))
            })

            c.body.addView(actionRow(
                ctx, "Login / Refresh",
                "Opens febbox.com in a WebView",
                if (hasToken) "Re-login" else "Log in"
            ) {
                openFebBoxLoginDialog(ctx) {
                    onSaved()
                    Toast.makeText(ctx, "FebBox token saved", Toast.LENGTH_SHORT).show()
                }
            })

            if (hasToken) {
                c.body.addView(actionRow(
                    ctx, "Clear session",
                    "Removes the saved token",
                    "Clear"
                ) {
                    clearFebBoxToken()
                    onSaved()
                    Toast.makeText(ctx, "Token cleared", Toast.LENGTH_SHORT).show()
                })
            }
            body.addView(c.root)
        }

        // ── 4. Sources ──
        run {
            val on = listOf(isSrcVm(), isSrcMd(), isSrcHdh(), isSrcFebBox()).count { it }
            val c = buildCard(ctx, "📡", "Sources",
                "$on of 4 enabled")
            c.statusBadge.apply {
                text = "$on/4"
                setTextColor(ACCENT)
                visibility = View.VISIBLE
            }
            c.body.addView(toggleRow(ctx, "VegaMovies", null, isSrcVm()) { setKey(K_SRC_VM, it) })
            c.body.addView(toggleRow(ctx, "MoviesDrive", null, isSrcMd()) { setKey(K_SRC_MD, it) })
            c.body.addView(toggleRow(ctx, "HDhub4u", null, isSrcHdh()) { setKey(K_SRC_HDH, it) })
            c.body.addView(toggleRow(ctx, "FebBox", "Requires login above", isSrcFebBox()) { setKey(K_SRC_FEBBOX, it) })
            body.addView(c.root)
        }

        // ── 5. Preferred Quality ──
        run {
            val c = buildCard(ctx, "🎞️", "Preferred Quality",
                "Current: ${getQualityPref()}")
            val options = listOf("Auto", "480p", "720p", "1080p", "1440p", "2160p (4K)")
            val spinner = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, options)
                val currentIdx = options.indexOfFirst { it.startsWith(getQualityPref().substringBefore(" ")) }
                setSelection(currentIdx.coerceAtLeast(0))
                background = bg(ROW_BG, 12, ctx)
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 4) }
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        val clean = options[pos].substringBefore(" ").trim()
                        setKey(K_QUALITY, clean)
                        c.header.findViewById<TextView>(0) // no-op
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            }
            c.body.addView(spinner)
            body.addView(c.root)
        }

        // ── 6. Home Rows ──
        run {
            val on = listOf(
                K_ROW_TRENDING_MOVIES, K_ROW_TRENDING_SERIES,
                K_ROW_POPULAR_MOVIES, K_ROW_POPULAR_SERIES,
                K_ROW_TOP_ANIME, K_ROW_AIRING_ANIME,
                K_ROW_TOP_ANIME_MOVIES, K_ROW_MOST_POPULAR_ANIME
            ).count { isRowEnabled(it) }
            val c = buildCard(ctx, "🏠", "Home Rows", "$on of 8 visible")
            c.statusBadge.apply {
                text = "$on/8"; setTextColor(ACCENT); visibility = View.VISIBLE
            }
            c.body.addView(toggleRow(ctx, "Trending Movies", null, isRowEnabled(K_ROW_TRENDING_MOVIES)) { setKey(K_ROW_TRENDING_MOVIES, it) })
            c.body.addView(toggleRow(ctx, "Trending Series", null, isRowEnabled(K_ROW_TRENDING_SERIES)) { setKey(K_ROW_TRENDING_SERIES, it) })
            c.body.addView(toggleRow(ctx, "Popular Movies", null, isRowEnabled(K_ROW_POPULAR_MOVIES)) { setKey(K_ROW_POPULAR_MOVIES, it) })
            c.body.addView(toggleRow(ctx, "Popular Series", null, isRowEnabled(K_ROW_POPULAR_SERIES)) { setKey(K_ROW_POPULAR_SERIES, it) })
            c.body.addView(toggleRow(ctx, "Top Anime", null, isRowEnabled(K_ROW_TOP_ANIME)) { setKey(K_ROW_TOP_ANIME, it) })
            c.body.addView(toggleRow(ctx, "Airing Anime", null, isRowEnabled(K_ROW_AIRING_ANIME)) { setKey(K_ROW_AIRING_ANIME, it) })
            c.body.addView(toggleRow(ctx, "Top Anime Movies", null, isRowEnabled(K_ROW_TOP_ANIME_MOVIES)) { setKey(K_ROW_TOP_ANIME_MOVIES, it) })
            c.body.addView(toggleRow(ctx, "Most Popular Anime", null, isRowEnabled(K_ROW_MOST_POPULAR_ANIME)) { setKey(K_ROW_MOST_POPULAR_ANIME, it) })
            body.addView(c.root)
        }

        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        AlertDialog.Builder(ctx)
            .setView(root)
            .setPositiveButton("Save & Close") { _, _ -> onSaved() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── WebView dialog ──
    @SuppressLint("SetJavaScriptEnabled")
    private fun openWebViewDialog(ctx: Context, startUrl: String, domain: String) {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(BG_DARK, 0, ctx)
        }

        // Toolbar
        val urlBar = TextView(ctx).apply {
            text = startUrl
            setTextColor(SUBTEXT); textSize = 12f
            background = bg(HEADER_BG, 0, ctx)
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            maxLines = 1
        }
        layout.addView(urlBar)

        // WebView container
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
            settings.cacheMode = WebSettings.LOAD_DEFAULT
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
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        layout.addView(wvWrap)

        // Bottom bar
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(HEADER_BG, 0, ctx)
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        }
        bar.addView(Button(ctx).apply {
            text = "Close"
            textSize = 13f
            setTextColor(TEXT)
            background = bg(ROW_BG, 20, ctx)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(ctx, 8) }
            setOnClickListener { dialog.dismiss() }
        })
        bar.addView(Button(ctx).apply {
            text = "🍪  Save Cookies"
            textSize = 13f
            setTextColor(TEXT)
            background = bg(ACCENT_BG, 20, ctx)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(ctx, 8) }
            setOnClickListener {
                val cookie = CookieManager.getInstance().getCookie(startUrl) ?: ""
                if (cookie.isBlank()) {
                    Toast.makeText(ctx, "No cookies yet — solve the challenge first",
                        Toast.LENGTH_SHORT).show()
                } else {
                    saveCookieForDomain(domain, cookie)
                    Toast.makeText(ctx, "✓ Saved for $domain",
                        Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        })
        layout.addView(bar)

        dialog.setContentView(layout)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.setOnDismissListener {
            try {
                wv.stopLoading()
                wv.destroy()
            } catch (_: Exception) {}
        }
        dialog.show()
    }

    // ── FebBox login WebView ──
    @SuppressLint("SetJavaScriptEnabled")
    private fun openFebBoxLoginDialog(ctx: Context, onTokenSaved: () -> Unit) {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(BG_DARK, 0, ctx)
        }

        val banner = TextView(ctx).apply {
            text = "Log in to febbox.com. Token saves automatically when you tap Extract Token."
            setTextColor(SUBTEXT); textSize = 12f
            background = bg(HEADER_BG, 0, ctx)
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
            loadUrl("https://www.febbox.com/")
        }
        wvWrap.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        layout.addView(wvWrap)

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(HEADER_BG, 0, ctx)
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        }
        bar.addView(Button(ctx).apply {
            text = "Close"
            textSize = 13f
            setTextColor(TEXT)
            background = bg(ROW_BG, 20, ctx)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(ctx, 8) }
            setOnClickListener { dialog.dismiss() }
        })
        bar.addView(Button(ctx).apply {
            text = "🔑  Extract Token"
            textSize = 13f
            setTextColor(TEXT)
            background = bg(ACCENT_BG, 20, ctx)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(ctx, 8) }
            setOnClickListener {
                val cookie = CookieManager.getInstance().getCookie("https://www.febbox.com") ?: ""
                val uiToken = cookie.split(";")
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("ui=") }
                    ?.substringAfter("ui=")
                    ?.trim()
                if (uiToken.isNullOrBlank()) {
                    Toast.makeText(ctx,
                        "Not logged in yet — complete login first",
                        Toast.LENGTH_SHORT).show()
                } else {
                    saveFebBoxToken(uiToken)
                    onTokenSaved()
                    dialog.dismiss()
                }
            }
        })
        layout.addView(bar)

        dialog.setContentView(layout)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.setOnDismissListener {
            try {
                wv.stopLoading(); wv.destroy()
            } catch (_: Exception) {}
        }
        dialog.show()
    }
}
