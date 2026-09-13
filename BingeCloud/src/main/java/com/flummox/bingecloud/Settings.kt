package com.flummox.bingecloud

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

object Settings {

    // ── Storage keys ──
    const val K_CONCURRENCY = "bingecloud_concurrency"
    const val K_CF_ENABLED = "bingecloud_cf_enabled"
    const val K_CF_DOMAINS = "bingecloud_cf_domains"
    const val K_FEBBOX_EMAIL = "bingecloud_febbox_email"
    const val K_FEBBOX_PASSWORD = "bingecloud_febbox_password"
    const val K_FEBBOX_TOKEN = "bingecloud_febbox_token"
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
    fun isCfEnabled(): Boolean = getKey<Boolean>(K_CF_ENABLED) ?: true
    fun getCfDomains(): List<String> =
        (getKey<String>(K_CF_DOMAINS) ?: "")
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { DEFAULT_CF_DOMAINS }
    fun getFebBoxEmail(): String = getKey<String>(K_FEBBOX_EMAIL) ?: ""
    fun getFebBoxPassword(): String = getKey<String>(K_FEBBOX_PASSWORD) ?: ""
    fun getFebBoxToken(): String = getKey<String>(K_FEBBOX_TOKEN) ?: ""
    fun isSrcVm(): Boolean = getKey<Boolean>(K_SRC_VM) ?: true
    fun isSrcMd(): Boolean = getKey<Boolean>(K_SRC_MD) ?: true
    fun isSrcHdh(): Boolean = getKey<Boolean>(K_SRC_HDH) ?: true
    fun isSrcFebBox(): Boolean = getKey<Boolean>(K_SRC_FEBBOX) ?: true
    fun getQualityPref(): String = getKey<String>(K_QUALITY) ?: "Auto"
    fun isRowEnabled(key: String): Boolean = getKey<Boolean>(key) ?: true

    // ── Colors ──
    private const val BG_DARK = 0xFF0B0B12.toInt()
    private const val CARD_BG = 0xFF15151E.toInt()
    private const val CARD_BG_ALT = 0xFF1C1C28.toInt()
    private const val INPUT_BG = 0xFF0F0F17.toInt()
    private const val ACCENT = 0xFF8B5CF6.toInt()
    private const val TEXT = 0xFFF1F1F3.toInt()
    private const val SUBTEXT = 0xFF8B8B99.toInt()
    private const val GREEN = 0xFF22C55E.toInt()
    private const val RED = 0xFFEF4444.toInt()
    private const val BORDER = 0xFF262633.toInt()

    // ── Helpers ──
    private fun rounded(bg: Int, radiusDp: Int, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(bg)
            cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun card(ctx: Context, emoji: String, title: String): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD_BG, 16, ctx)
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 12) }
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
        }
        header.addView(TextView(ctx).apply {
            text = emoji
            textSize = 18f
            setPadding(0, 0, dp(ctx, 10), 0)
        })
        header.addView(TextView(ctx).apply {
            text = title
            setTextColor(TEXT)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(header)
        return card
    }

    private fun toggle(ctx: Context, label: String, desc: String?, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD_BG_ALT, 12, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(ctx, 6)
                leftMargin = dp(ctx, 8); rightMargin = dp(ctx, 8)
            }
        }
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(ctx).apply {
            text = label
            setTextColor(TEXT)
            textSize = 14f
        })
        if (!desc.isNullOrBlank()) {
            textCol.addView(TextView(ctx).apply {
                text = desc
                setTextColor(SUBTEXT)
                textSize = 12f
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        row.addView(textCol)
        val sw = Switch(ctx)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onChange(v) }
        row.addView(sw)
        return row
    }

    private fun stepper(ctx: Context, label: String, min: Int, max: Int, initial: Int, onChange: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD_BG_ALT, 12, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(ctx, 6)
                leftMargin = dp(ctx, 8); rightMargin = dp(ctx, 8)
            }
        }
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(ctx).apply {
            text = label
            setTextColor(TEXT)
            textSize = 14f
        })
        textCol.addView(TextView(ctx).apply {
            text = "Number of providers run concurrently"
            setTextColor(SUBTEXT)
            textSize = 12f
            setPadding(0, dp(ctx, 2), 0, 0)
        })
        row.addView(textCol)

        var current = initial
        val valueText = TextView(ctx).apply {
            text = "$current"
            setTextColor(ACCENT)
            textSize = 22f
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
        }
        val minus = Button(ctx).apply {
            text = "−"
            textSize = 18f
            setTextColor(TEXT)
            background = rounded(CARD_BG, 20, ctx)
            minWidth = dp(ctx, 36); minHeight = dp(ctx, 36)
            setOnClickListener {
                current = (current - 1).coerceAtLeast(min)
                valueText.text = "$current"
                onChange(current)
            }
        }
        val plus = Button(ctx).apply {
            text = "+"
            textSize = 18f
            setTextColor(TEXT)
            background = rounded(CARD_BG, 20, ctx)
            minWidth = dp(ctx, 36); minHeight = dp(ctx, 36)
            setOnClickListener {
                current = (current + 1).coerceAtMost(max)
                valueText.text = "$current"
                onChange(current)
            }
        }
        row.addView(minus)
        row.addView(valueText)
        row.addView(plus)
        return row
    }

    private fun editRow(
        ctx: Context, label: String, initial: String,
        isPassword: Boolean = false,
        onChange: (String) -> Unit
    ): LinearLayout {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD_BG_ALT, 12, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(ctx, 6)
                leftMargin = dp(ctx, 8); rightMargin = dp(ctx, 8)
            }
        }
        col.addView(TextView(ctx).apply {
            text = label
            setTextColor(SUBTEXT)
            textSize = 12f
            setPadding(0, 0, 0, dp(ctx, 6))
        })
        val et = EditText(ctx).apply {
            setText(initial)
            setTextColor(TEXT)
            setHintTextColor(SUBTEXT)
            textSize = 14f
            background = rounded(INPUT_BG, 8, ctx)
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            inputType = if (isPassword)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT
        }
        et.setOnFocusChangeListener { _: View, hasFocus: Boolean ->
            if (!hasFocus) onChange(et.text.toString())
        }
        col.addView(et)
        return col
    }

    private fun openButton(ctx: Context, label: String, url: String): Button =
        Button(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(ACCENT)
            background = rounded(CARD_BG, 10, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            setOnClickListener {
                try {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                } catch (_: Exception) {}
            }
        }

    private fun spacer(ctx: Context, heightDp: Int): View =
        View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(ctx, heightDp)) }

    // ── Main dialog ──
    fun showSettingsDialog(ctx: Context, onSaved: () -> Unit) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(BG_DARK, 0, ctx)
        }

        // Header bar
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF15151E.toInt(), 0, ctx)
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 20))
        }
        header.addView(TextView(ctx).apply {
            text = "BingeCloud Settings"
            setTextColor(TEXT)
            textSize = 22f
        })
        header.addView(TextView(ctx).apply {
            text = "Configure sources, catalogs & cookies"
            setTextColor(SUBTEXT)
            textSize = 13f
            setPadding(0, dp(ctx, 4), 0, 0)
        })
        root.addView(header)

        // Scroll content
        val scroll = ScrollView(ctx)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 16), dp(ctx, 12), dp(ctx, 16))
        }
        scroll.addView(body)

        // ── Performance ──
        val perfCard = card(ctx, "⚙️", "Performance Settings")
        perfCard.addView(stepper(ctx, "Concurrency", 1, 50, getConcurrency()) { setKey(K_CONCURRENCY, it) })
        body.addView(perfCard)

        // ── Cloudflare ──
        val cfCard = card(ctx, "🛡️", "Cloudflare Bypass")
        cfCard.addView(toggle(ctx, "Enable Bypass",
            "Turn on WebView resolution for CF challenges", isCfEnabled()) { setKey(K_CF_ENABLED, it) })
        cfCard.addView(editRow(ctx, "Protected Domains (comma-separated)",
            (getKey<String>(K_CF_DOMAINS) ?: DEFAULT_CF_DOMAINS.joinToString(","))) {
            setKey(K_CF_DOMAINS, it)
        })
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
        }
        btnRow.addView(openButton(ctx, "🌐  Open FebBox", "https://www.febbox.com"))
        btnRow.addView(spacer(ctx, 1).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 8), 1)
        })
        btnRow.addView(openButton(ctx, "🌐  Open HubCloud", "https://hubcloud.ist"))
        cfCard.addView(btnRow)
        body.addView(cfCard)

        // ── FebBox ──
        val fbxCard = card(ctx, "🔑", "API Tokens")
        fbxCard.addView(TextView(ctx).apply {
            text = "FebBox Token"
            setTextColor(TEXT)
            textSize = 14f
            setPadding(dp(ctx, 20), dp(ctx, 4), dp(ctx, 20), dp(ctx, 4))
        })
        fbxCard.addView(TextView(ctx).apply {
            text = "Log in to enable ShowBox source"
            setTextColor(SUBTEXT)
            textSize = 12f
            setPadding(dp(ctx, 20), 0, dp(ctx, 20), dp(ctx, 8))
        })
        fbxCard.addView(editRow(ctx, "Email", getFebBoxEmail()) { setKey(K_FEBBOX_EMAIL, it) })
        fbxCard.addView(editRow(ctx, "Password", getFebBoxPassword(), isPassword = true) {
            setKey(K_FEBBOX_PASSWORD, it)
        })
        val tkn = getFebBoxToken()
        fbxCard.addView(TextView(ctx).apply {
            text = if (tkn.isBlank()) "Token: not set" else "Token: ${tkn.take(14)}…  ✓ Saved"
            setTextColor(if (tkn.isBlank()) SUBTEXT else GREEN)
            textSize = 12f
            setPadding(dp(ctx, 20), dp(ctx, 4), dp(ctx, 20), dp(ctx, 8))
        })
        val fbxBtns = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 4))
        }
        fbxBtns.addView(openButton(ctx, "🌐  Open FebBox", "https://www.febbox.com"))
        fbxCard.addView(fbxBtns)
        body.addView(fbxCard)

        // ── Sources ──
        val srcCard = card(ctx, "📡", "Sources")
        srcCard.addView(toggle(ctx, "VegaMovies", null, isSrcVm()) { setKey(K_SRC_VM, it) })
        srcCard.addView(toggle(ctx, "MoviesDrive", null, isSrcMd()) { setKey(K_SRC_MD, it) })
        srcCard.addView(toggle(ctx, "HDhub4u", null, isSrcHdh()) { setKey(K_SRC_HDH, it) })
        srcCard.addView(toggle(ctx, "FebBox", null, isSrcFebBox()) { setKey(K_SRC_FEBBOX, it) })
        body.addView(srcCard)

        // ── Quality ──
        val qualCard = card(ctx, "🎞️", "Preferred Quality")
        val qualityOptions = listOf("Auto", "480p", "720p", "1080p", "1440p", "2160p")
        val qualSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, qualityOptions)
            setSelection(qualityOptions.indexOf(getQualityPref()).coerceAtLeast(0))
            background = rounded(CARD_BG_ALT, 12, ctx)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(ctx, 8); rightMargin = dp(ctx, 8); bottomMargin = dp(ctx, 6)
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    setKey(K_QUALITY, qualityOptions[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        qualCard.addView(qualSpinner)
        body.addView(qualCard)

        // ── Home Rows ──
        val rowCard = card(ctx, "🏠", "Home Rows")
        rowCard.addView(toggle(ctx, "Trending Movies", null, isRowEnabled(K_ROW_TRENDING_MOVIES)) { setKey(K_ROW_TRENDING_MOVIES, it) })
        rowCard.addView(toggle(ctx, "Trending Series", null, isRowEnabled(K_ROW_TRENDING_SERIES)) { setKey(K_ROW_TRENDING_SERIES, it) })
        rowCard.addView(toggle(ctx, "Popular Movies", null, isRowEnabled(K_ROW_POPULAR_MOVIES)) { setKey(K_ROW_POPULAR_MOVIES, it) })
        rowCard.addView(toggle(ctx, "Popular Series", null, isRowEnabled(K_ROW_POPULAR_SERIES)) { setKey(K_ROW_POPULAR_SERIES, it) })
        rowCard.addView(toggle(ctx, "Top Anime", null, isRowEnabled(K_ROW_TOP_ANIME)) { setKey(K_ROW_TOP_ANIME, it) })
        rowCard.addView(toggle(ctx, "Airing Anime", null, isRowEnabled(K_ROW_AIRING_ANIME)) { setKey(K_ROW_AIRING_ANIME, it) })
        rowCard.addView(toggle(ctx, "Top Anime Movies", null, isRowEnabled(K_ROW_TOP_ANIME_MOVIES)) { setKey(K_ROW_TOP_ANIME_MOVIES, it) })
        rowCard.addView(toggle(ctx, "Most Popular Anime", null, isRowEnabled(K_ROW_MOST_POPULAR_ANIME)) { setKey(K_ROW_MOST_POPULAR_ANIME, it) })
        body.addView(rowCard)

        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setView(root)
            .setPositiveButton("Save & Close") { _, _ -> onSaved() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
