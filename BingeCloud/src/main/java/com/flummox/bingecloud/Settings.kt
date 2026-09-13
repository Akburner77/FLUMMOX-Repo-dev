package com.flummox.bingecloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

object Settings {

    // ── Persistence keys ──
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

    // ── UI ──
    private const val BG = 0xFF0F0F14.toInt()
    private const val CARD_BG = 0xFF1A1A22.toInt()
    private const val ACCENT = 0xFF818CF8.toInt()
    private const val TEXT = 0xFFE5E7EB.toInt()
    private const val SUBTEXT = 0xFF9CA3AF.toInt()

    fun showSettingsDialog(ctx: Context, onSaved: () -> Unit) {
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(BG)
            setPadding(32, 32, 32, 32)
        }
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)

        root.addView(header(ctx, "BingeCloud Settings"))

        // ── Performance ──
        root.addView(section(ctx, "⚡  Performance"))
        root.addView(sliderRow(ctx, "Concurrency", 1, 50, getConcurrency()) { setKey(K_CONCURRENCY, it) })

        // ── Cloudflare ──
        root.addView(section(ctx, "🛡️  Cloudflare Bypass"))
        root.addView(toggleRow(ctx, "Enable bypass", isCfEnabled()) { setKey(K_CF_ENABLED, it) })
        root.addView(editRow(ctx, "Domains (comma-separated)",
            (getKey<String>(K_CF_DOMAINS) ?: DEFAULT_CF_DOMAINS.joinToString(","))) {
            setKey(K_CF_DOMAINS, it)
        })
        root.addView(buttonRow(ctx, "🌐 Open FebBox", "https://www.febbox.com"))
        root.addView(buttonRow(ctx, "🌐 Open HubCloud", "https://hubcloud.ist"))

        // ── FebBox Account ──
        root.addView(section(ctx, "🔑  FebBox Account"))
        root.addView(editRow(ctx, "Email", getFebBoxEmail(), InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) {
            setKey(K_FEBBOX_EMAIL, it)
        })
        root.addView(editRow(ctx, "Password", getFebBoxPassword(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            setKey(K_FEBBOX_PASSWORD, it)
        })
        val token = getFebBoxToken()
        root.addView(labelRow(ctx,
            if (token.isBlank()) "Token: not set"
            else "Token: ${token.take(12)}...",
            SUBTEXT))

        // ── Sources ──
        root.addView(section(ctx, "📡  Sources"))
        root.addView(toggleRow(ctx, "VegaMovies", isSrcVm()) { setKey(K_SRC_VM, it) })
        root.addView(toggleRow(ctx, "MoviesDrive", isSrcMd()) { setKey(K_SRC_MD, it) })
        root.addView(toggleRow(ctx, "HDhub4u", isSrcHdh()) { setKey(K_SRC_HDH, it) })
        root.addView(toggleRow(ctx, "FebBox", isSrcFebBox()) { setKey(K_SRC_FEBBOX, it) })

        // ── Quality ──
        root.addView(section(ctx, "🎞️  Preferred Quality"))
        val qualities = listOf("Auto", "480p", "720p", "1080p", "1440p", "2160p")
        root.addView(dropdownRow(ctx, qualities, getQualityPref()) { setKey(K_QUALITY, it) })

        // ── Home Rows ──
        root.addView(section(ctx, "🏠  Home Rows"))
        root.addView(toggleRow(ctx, "Trending Movies", isRowEnabled(K_ROW_TRENDING_MOVIES)) { setKey(K_ROW_TRENDING_MOVIES, it) })
        root.addView(toggleRow(ctx, "Trending Series", isRowEnabled(K_ROW_TRENDING_SERIES)) { setKey(K_ROW_TRENDING_SERIES, it) })
        root.addView(toggleRow(ctx, "Popular Movies", isRowEnabled(K_ROW_POPULAR_MOVIES)) { setKey(K_ROW_POPULAR_MOVIES, it) })
        root.addView(toggleRow(ctx, "Popular Series", isRowEnabled(K_ROW_POPULAR_SERIES)) { setKey(K_ROW_POPULAR_SERIES, it) })
        root.addView(toggleRow(ctx, "Top Anime", isRowEnabled(K_ROW_TOP_ANIME)) { setKey(K_ROW_TOP_ANIME, it) })
        root.addView(toggleRow(ctx, "Airing Anime", isRowEnabled(K_ROW_AIRING_ANIME)) { setKey(K_ROW_AIRING_ANIME, it) })
        root.addView(toggleRow(ctx, "Top Anime Movies", isRowEnabled(K_ROW_TOP_ANIME_MOVIES)) { setKey(K_ROW_TOP_ANIME_MOVIES, it) })
        root.addView(toggleRow(ctx, "Most Popular Anime", isRowEnabled(K_ROW_MOST_POPULAR_ANIME)) { setKey(K_ROW_MOST_POPULAR_ANIME, it) })

        AlertDialog.Builder(ctx)
            .setView(scroll)
            .setPositiveButton("Save & Close") { _, _ -> onSaved() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── UI helpers ──
    private fun header(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(TEXT)
        textSize = 22f
        setPadding(8, 8, 8, 24)
    }

    private fun section(ctx: Context, title: String): TextView = TextView(ctx).apply {
        this.text = title
        setTextColor(ACCENT)
        textSize = 15f
        setPadding(8, 28, 8, 12)
    }

    private fun labelRow(ctx: Context, text: String, color: Int = SUBTEXT): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        textSize = 13f
        setPadding(16, 6, 16, 6)
    }

    private fun toggleRow(ctx: Context, label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 14, 16, 14)
            addView(TextView(ctx).apply {
                text = label
                setTextColor(TEXT)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Switch(ctx).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, v -> onChange(v) }
            })
        }

    private fun sliderRow(
        ctx: Context, label: String, min: Int, max: Int, initial: Int,
        onChange: (Int) -> Unit
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 8, 16, 16)
        val title = TextView(ctx).apply {
            text = "$label: $initial"
            setTextColor(TEXT)
            textSize = 14f
        }
        addView(title)
        addView(SeekBar(ctx).apply {
            this.max = max - min
            progress = initial - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + min
                    title.text = "$label: $v"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
    }

    private fun editRow(
        ctx: Context, label: String, initial: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        onChange: (String) -> Unit
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 8, 16, 12)
        addView(TextView(ctx).apply {
            text = label
            setTextColor(SUBTEXT)
            textSize = 12f
            setPadding(0, 4, 0, 6)
        })
        addView(EditText(ctx).apply {
            setText(initial)
            setTextColor(TEXT)
            setBackgroundColor(CARD_BG)
            this.inputType = inputType
            setPadding(24, 16, 24, 16)
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onChange(text.toString()) }
        })
    }

    private fun buttonRow(ctx: Context, label: String, url: String): LinearLayout =
        LinearLayout(ctx).apply {
            setPadding(16, 6, 16, 6)
            addView(Button(ctx).apply {
                text = label
                setTextColor(TEXT)
                setBackgroundColor(CARD_BG)
                setOnClickListener {
                    try {
                        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                    } catch (_: Exception) {}
                }
            })
        }

    private fun dropdownRow(
        ctx: Context, options: List<String>, initial: String,
        onChange: (String) -> Unit
    ): LinearLayout = LinearLayout(ctx).apply {
        setPadding(16, 8, 16, 16)
        addView(Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(options.indexOf(initial).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    onChange(options[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
    }
}
