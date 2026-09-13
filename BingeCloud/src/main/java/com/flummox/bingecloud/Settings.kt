package com.flummox.bingecloud

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

data class RowSpec(
    val key: String,
    val type: String,
    val catalogId: String,
    val name: String,
    val defaultGenre: String? = null,
    val sourceLabel: String
)

object Settings {

    // ── Storage keys ──
    const val K_CONCURRENCY = "bingecloud_concurrency"
    const val K_CF_DOMAINS = "bingecloud_cf_domains"
    const val K_CF_COOKIE_PREFIX = "bingecloud_cf_cookie_"
    const val K_FEBBOX_TOKEN = "bingecloud_febbox_token"
    const val K_SRC_VM = "bingecloud_src_vm"
    const val K_SRC_MD = "bingecloud_src_md"
    const val K_SRC_HDH = "bingecloud_src_hdh"
    const val K_SRC_FEBBOX = "bingecloud_src_febbox"
    const val K_QUALITY = "bingecloud_quality"
    const val K_PREFILTER = "bingecloud_prefilter"
    const val K_ROW_ORDER = "bingecloud_row_order"
    const val K_ROW_TRENDING_MOVIES = "bingecloud_row_trending_movies"
    const val K_ROW_TRENDING_SERIES = "bingecloud_row_trending_series"
    const val K_ROW_POPULAR_MOVIES = "bingecloud_row_popular_movies"
    const val K_ROW_POPULAR_SERIES = "bingecloud_row_popular_series"
    const val K_ROW_TVDB_MOVIES = "bingecloud_row_tvdb_movies"
    const val K_ROW_TVDB_SERIES = "bingecloud_row_tvdb_series"
    const val K_ROW_TVDB_GENRES_MOVIES = "bingecloud_row_tvdb_genres_movies"
    const val K_ROW_TVDB_GENRES_SERIES = "bingecloud_row_tvdb_genres_series"
    const val K_ROW_TOP_ANIME = "bingecloud_row_top_anime"
    const val K_ROW_AIRING_ANIME = "bingecloud_row_airing_anime"
    const val K_ROW_UPCOMING_ANIME = "bingecloud_row_upcoming_anime"
    const val K_ROW_TOP_ANIME_MOVIES = "bingecloud_row_top_anime_movies"
    const val K_ROW_TOP_ANIME_SERIES = "bingecloud_row_top_anime_series"
    const val K_ROW_MOST_POPULAR_ANIME = "bingecloud_row_most_popular_anime"
    const val K_ROW_MOST_FAV_ANIME = "bingecloud_row_most_fav_anime"
    const val K_ROW_BEST_2020S = "bingecloud_row_best_2020s"
    const val K_ROW_BEST_2010S = "bingecloud_row_best_2010s"
    const val K_ROW_BEST_2000S = "bingecloud_row_best_2000s"
    const val K_ROW_BEST_90S = "bingecloud_row_best_90s"
    const val K_ROW_BEST_80S = "bingecloud_row_best_80s"
    const val K_ROW_HINDI_MOVIES = "bingecloud_row_hindi_movies"
    const val K_ROW_HINDI_SERIES = "bingecloud_row_hindi_series"
    const val K_ROW_ANIME_SCHEDULE = "bingecloud_row_anime_schedule"

    val DEFAULT_CF_DOMAINS = emptyList<String>()

    val ALL_ROWS: List<RowSpec> = listOf(
        RowSpec(K_ROW_TRENDING_MOVIES, "movie", "tmdb.trending", "Trending Movies", null, "TMDB"),
        RowSpec(K_ROW_TRENDING_SERIES, "series", "tmdb.trending", "Trending Series", null, "TMDB"),
        RowSpec(K_ROW_POPULAR_MOVIES, "movie", "tmdb.top", "Popular Movies", null, "TMDB"),
        RowSpec(K_ROW_POPULAR_SERIES, "series", "tmdb.top", "Popular Series", null, "TMDB"),
        RowSpec(K_ROW_HINDI_MOVIES, "movie", "tmdb.language", "Hindi Movies", "hi", "TMDB • Hindi"),
        RowSpec(K_ROW_HINDI_SERIES, "series", "tmdb.language", "Hindi Series", "hi", "TMDB • Hindi"),
        RowSpec(K_ROW_TVDB_MOVIES, "movie", "tvdb.trending", "TVDB Trending Movies", "Action", "TVDB"),
        RowSpec(K_ROW_TVDB_SERIES, "series", "tvdb.trending", "TVDB Trending Series", "Action", "TVDB"),
        RowSpec(K_ROW_TVDB_GENRES_MOVIES, "movie", "tvdb.genres", "TVDB Genre Movies", "Action", "TVDB"),
        RowSpec(K_ROW_TVDB_GENRES_SERIES, "series", "tvdb.genres", "TVDB Genre Series", "Action", "TVDB"),
        RowSpec(K_ROW_TOP_ANIME, "anime", "mal.top_anime", "Top Anime", null, "MAL"),
        RowSpec(K_ROW_AIRING_ANIME, "anime", "mal.airing", "Airing Now", null, "MAL"),
        RowSpec(K_ROW_UPCOMING_ANIME, "anime", "mal.upcoming", "Upcoming Anime", null, "MAL"),
        RowSpec(K_ROW_ANIME_SCHEDULE, "anime", "mal.schedule", "Airing Schedule", "Monday", "MAL"),
        RowSpec(K_ROW_TOP_ANIME_MOVIES, "anime", "mal.top_movies", "Top Anime Movies", null, "MAL"),
        RowSpec(K_ROW_TOP_ANIME_SERIES, "anime", "mal.top_series", "Top Anime Series", null, "MAL"),
        RowSpec(K_ROW_MOST_POPULAR_ANIME, "anime", "mal.most_popular", "Most Popular Anime", null, "MAL"),
        RowSpec(K_ROW_MOST_FAV_ANIME, "anime", "mal.most_favorites", "Most Favorited Anime", null, "MAL"),
        RowSpec(K_ROW_BEST_2020S, "anime", "mal.20sDecade", "Best of 2020s", "Action", "MAL"),
        RowSpec(K_ROW_BEST_2010S, "anime", "mal.10sDecade", "Best of 2010s", "Action", "MAL"),
        RowSpec(K_ROW_BEST_2000S, "anime", "mal.00sDecade", "Best of 2000s", "Action", "MAL"),
        RowSpec(K_ROW_BEST_90S, "anime", "mal.90sDecade", "Best of 90s", "Action", "MAL"),
        RowSpec(K_ROW_BEST_80S, "anime", "mal.80sDecade", "Best of 80s", "Action", "MAL"),
    )

    fun getRowOrder(): List<String> {
        val stored = getKey<String>(K_ROW_ORDER) ?: ""
        val parts = stored.split("|").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return ALL_ROWS.map { it.key }
        val seen = parts.toSet()
        val extras = ALL_ROWS.map { it.key }.filter { it !in seen }
        return parts + extras
    }

    fun setRowOrder(order: List<String>) {
        setKey(K_ROW_ORDER, order.joinToString("|"))
    }

    fun getRowSpecByKey(key: String): RowSpec? = ALL_ROWS.firstOrNull { it.key == key }

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
    fun isPrefilterEnabled(): Boolean = getKey<Boolean>(K_PREFILTER) ?: true
    fun isRowEnabled(key: String): Boolean = getKey<Boolean>(key) ?: true

    // ── Sky palette ──
    private const val BG = 0xFF0A0D14.toInt()
    private const val SKY_TOP = 0xFF1E3A5F.toInt()
    private const val SKY_MID = 0xFF142238.toInt()
    private const val SKY_BOTTOM = 0xFF0A0D14.toInt()
    private const val CARD = 0xFF0F1520.toInt()
    private const val CARD_BORDER = 0xFF1E2A3D.toInt()
    private const val ROW = 0xFF141B28.toInt()
    private const val INPUT = 0xFF0B1018.toInt()
    private const val ACCENT = 0xFF7DD3FC.toInt()
    private const val ACCENT_BG = 0x1A7DD3FC
    private const val ACCENT_STRONG = 0xFF38BDF8.toInt()
    private const val SAVE_GRAD_TOP = 0xFF38BDF8.toInt()
    private const val SAVE_GRAD_BOTTOM = 0xFF7DD3FC.toInt()
    private const val TEXT = 0xFFE6EDF5.toInt()
    private const val SUBTEXT = 0xFF8296AD.toInt()
    private const val GREEN = 0xFF4ADE80.toInt()
    private const val RED = 0xFFF87171.toInt()
    private const val DISABLED = 0xFF3A4555.toInt()

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radiusDp: Int, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    private fun skyGradient(ctx: Context): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(SKY_TOP, SKY_MID, SKY_BOTTOM)
        ).apply { cornerRadius = 0f }

    private fun cardBg(ctx: Context): GradientDrawable = GradientDrawable().apply {
        setColor(CARD)
        cornerRadius = dp(ctx, 14).toFloat()
        setStroke(dp(ctx, 1), CARD_BORDER)
    }

    private fun accentPill(ctx: Context): GradientDrawable = GradientDrawable().apply {
        setColor(ACCENT_BG)
        cornerRadius = dp(ctx, 20).toFloat()
        setStroke(dp(ctx, 1), 0x337DD3FC)
    }

    private fun saveButtonBg(ctx: Context): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(SAVE_GRAD_TOP, SAVE_GRAD_BOTTOM)
        ).apply { cornerRadius = dp(ctx, 14).toFloat() }

    private fun cancelButtonBg(ctx: Context): GradientDrawable = GradientDrawable().apply {
        setColor(0xFF000000.toInt())
        cornerRadius = dp(ctx, 14).toFloat()
        setStroke(dp(ctx, 1), CARD_BORDER)
    }

    private fun arrowButtonBg(ctx: Context): GradientDrawable = GradientDrawable().apply {
        setColor(ROW)
        cornerRadius = dp(ctx, 8).toFloat()
        setStroke(dp(ctx, 1), CARD_BORDER)
    }

    // ── Shooting stars ──
    private class ShootingStarsView(context: Context) : View(context) {
        private data class Star(
            var x: Float, var y: Float, var vx: Float, var vy: Float,
            var length: Float, var alpha: Float, var thickness: Float
        )
        private val stars = mutableListOf<Star>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val rnd = java.util.Random()
        private var lastNs = 0L

        init { setWillNotDraw(false) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = System.nanoTime()
            val dt = if (lastNs == 0L) 0f
                else ((now - lastNs) / 1_000_000_000f).coerceAtMost(0.05f)
            lastNs = now

            if (rnd.nextFloat() < 0.03f && stars.size < 6) {
                val startX = width + 60f + rnd.nextFloat() * 200f
                val startY = -40f + rnd.nextFloat() * (height * 0.7f)
                val speed = 280f + rnd.nextFloat() * 260f
                stars.add(Star(
                    startX, startY,
                    -speed * 0.9f, speed * 0.55f,
                    70f + rnd.nextFloat() * 90f,
                    0.55f + rnd.nextFloat() * 0.45f,
                    1.2f + rnd.nextFloat() * 1.6f
                ))
            }

            val iter = stars.iterator()
            while (iter.hasNext()) {
                val s = iter.next()
                s.x += s.vx * dt; s.y += s.vy * dt
                if (s.x < -250f || s.y > height + 80f) { iter.remove(); continue }

                val lenScale = (s.x / width.toFloat()).coerceIn(0f, 1f)
                val fade = 1f - (1f - lenScale) * 0.6f
                val a = (s.alpha * fade * 255f).coerceIn(0f, 255f).toInt()

                val tx = s.x - s.vx / 280f * s.length
                val ty = s.y - s.vy / 280f * s.length

                paint.color = Color.argb(a / 3, 140, 200, 255)
                paint.strokeWidth = s.thickness * 2.2f
                canvas.drawLine(s.x, s.y, tx, ty, paint)

                paint.color = Color.argb(a, 200, 235, 255)
                paint.strokeWidth = s.thickness
                canvas.drawLine(s.x, s.y, tx, ty, paint)

                paint.color = Color.argb((a * 0.9f).toInt(), 255, 255, 255)
                paint.strokeWidth = s.thickness * 1.8f
                canvas.drawPoint(s.x, s.y, paint)
            }
            postInvalidateOnAnimation()
        }
    }

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
            text = badge ?: ""; setTextColor(badgeColor); textSize = 12f
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
        val bodyLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 12))
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        root.addView(bodyLayout)
        header.setOnClickListener {
            val showing = bodyLayout.visibility == View.VISIBLE
            bodyLayout.visibility = if (showing) View.GONE else View.VISIBLE
            chev.text = if (showing) "▸" else "▾"
        }
        return Card(root, bodyLayout, status, chev)
    }

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
            background = bg(INPUT, 20, ctx); isAllCaps = false
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
            setTextColor(if (buttonColor == RED) RED else ACCENT_STRONG)
            background = if (buttonColor == RED) bg(0x22F87171, 18, ctx) else accentPill(ctx)
            setPadding(dp(ctx, 16), dp(ctx, 6), dp(ctx, 16), dp(ctx, 6))
            minHeight = 0; minWidth = 0; isAllCaps = false
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
            textSize = 12f; setTextColor(ACCENT_STRONG)
            background = accentPill(ctx); isAllCaps = false
            setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6))
            minHeight = 0; minWidth = 0
            setOnClickListener { onOpen() }
        })
        if (hasCookie) {
            row.addView(Button(ctx).apply {
                text = "✕"; textSize = 12f; setTextColor(RED)
                background = bg(0x22F87171, 18, ctx); isAllCaps = false
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

    // ── Homepage reorder row ──
    private fun makeArrowBtn(
        ctx: Context, symbol: String, enabled: Boolean,
        onClick: () -> Unit
    ): TextView = TextView(ctx).apply {
        text = symbol
        textSize = 14f
        setTextColor(if (enabled) ACCENT_STRONG else DISABLED)
        background = arrowButtonBg(ctx)
        gravity = Gravity.CENTER
        val size = dp(ctx, 34)
        layoutParams = LinearLayout.LayoutParams(size, size)
            .apply { leftMargin = dp(ctx, 4) }
        isClickable = enabled
        if (enabled) setOnClickListener { onClick() }
    }

    private fun rowReorderItem(
        ctx: Context, spec: RowSpec, position: Int, total: Int,
        onMoveUp: () -> Unit, onMoveDown: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(ROW, 10, ctx)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 6) }
        }
        // Position number
        row.addView(TextView(ctx).apply {
            text = "${position + 1}"
            setTextColor(SUBTEXT); textSize = 12f
            gravity = Gravity.CENTER
            val s = dp(ctx, 26)
            layoutParams = LinearLayout.LayoutParams(s, s)
            background = bg(INPUT, 12, ctx)
        })
        // Label
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(ctx, 10) }
        }
        col.addView(TextView(ctx).apply {
            text = spec.name; setTextColor(TEXT); textSize = 13f
        })
        col.addView(TextView(ctx).apply {
            text = spec.sourceLabel; setTextColor(SUBTEXT); textSize = 10f
            setPadding(0, dp(ctx, 2), 0, 0)
        })
        row.addView(col)

        // Arrows
        row.addView(makeArrowBtn(ctx, "▲", position > 0) { onMoveUp() })
        row.addView(makeArrowBtn(ctx, "▼", position < total - 1) { onMoveDown() })
        return row
    }

    // ── Main dialog ──
    fun showSettingsDialog(ctx: Context, onSaved: () -> Unit) {
        lateinit var dialog: AlertDialog

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(BG, 0, ctx)
        }

        // Sky header
        run {
            val headerFrame = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 160)
                )
                background = skyGradient(ctx)
                clipChildren = true
            }
            headerFrame.addView(ShootingStarsView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM
                setPadding(dp(ctx, 22), dp(ctx, 22), dp(ctx, 22), dp(ctx, 22))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            content.addView(TextView(ctx).apply {
                text = "☁  BingeCloud"
                setTextColor(TEXT); textSize = 26f
            })
            content.addView(TextView(ctx).apply {
                text = "Configure sources, catalogs & cookies"
                setTextColor(ACCENT); textSize = 12f
                setPadding(0, dp(ctx, 6), 0, 0)
            })
            headerFrame.addView(content)
            root.addView(headerFrame)
        }

        val scroll = ScrollView(ctx)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 14), dp(ctx, 12), dp(ctx, 24))
        }
        scroll.addView(body)

        // 1. Performance
        run {
            val c = buildCard(ctx, "⚡", "Performance", "Control scraping speed")
            c.body.addView(stepperRow(
                ctx, "Concurrency", "Providers running in parallel",
                1, 50, getConcurrency()
            ) { setKey(K_CONCURRENCY, it) })
            body.addView(c.root)
        }

        // 2. Cloudflare
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

            if (saved.isEmpty()) {
                c.body.addView(TextView(ctx).apply {
                    text = "No domains saved yet. Add one below."
                    setTextColor(SUBTEXT); textSize = 12f
                    setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
                })
            } else {
                for (domain in saved) {
                    val has = getCookieForDomain(domain) != null
                    c.body.addView(domainRow(
                        ctx, domain, has,
                        onOpen = { openCfWebView(ctx, "https://$domain", domain) },
                        onClear = {
                            clearCookieForDomain(domain)
                            Toast.makeText(ctx, "Cleared $domain", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            showSettingsDialog(ctx, onSaved)
                        }
                    ))
                }
            }

            c.body.addView(actionRow(
                ctx, "Add custom domain", "Open any URL to solve CF",
                "Add"
            ) { openCfWebView(ctx, "https://www.febbox.com", "febbox.com") })
            body.addView(c.root)
        }

        // 3. FebBox
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
                if (has) "Session saved — nothing else to do."
                else "A WebView will open. Log in with your FebBox account and tap Save Token."
            ))
            c.body.addView(actionRow(
                ctx, "Sign in / Refresh", "Opens febbox.com login",
                if (has) "Re-login" else "Sign in"
            ) {
                openFebBoxLogin(ctx) {
                    onSaved()
                    dialog.dismiss()
                    showSettingsDialog(ctx, onSaved)
                }
            })
            if (has) {
                c.body.addView(actionRow(
                    ctx, "Sign out", "Removes saved session",
                    "Sign out", buttonColor = RED
                ) {
                    clearFebBoxToken()
                    Toast.makeText(ctx, "Signed out", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    showSettingsDialog(ctx, onSaved)
                })
            }
            body.addView(c.root)
        }

        // 4. Sources
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

        // 5. Quality
        run {
            val cur = getQualityPref()
            val c = buildCard(ctx, "🎞️", "Preferred Quality", "Current: $cur")
            val options = listOf("Auto", "480p", "720p", "1080p", "2K (1440p)", "4K (2160p)")
            val display = options.map { it.substringBefore(" (").trim() }
            val spinner = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, options)
                setSelection(display.indexOf(cur).coerceAtLeast(0))
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

        // 6. Pre-filter
        run {
            val c = buildCard(ctx, "🧪", "Link Validation", "Pre-filter dead links")
            c.body.addView(toggleRow(
                ctx, "Pre-filter unreachable",
                "Test each link before showing it in the dialog (slightly slower)",
                isPrefilterEnabled()
            ) { setKey(K_PREFILTER, it) })
            body.addView(c.root)
        }

        // 7. Homepage — reorderable
        run {
            val c = buildCard(
                ctx, "🏠", "Homepage", "Tap ▲▼ to reorder sections"
            )

            val listHolder = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }

            fun renderList() {
                listHolder.removeAllViews()
                val order = getRowOrder()
                val total = order.size
                val on = order.count { isRowEnabled(it) }

                c.statusBadge.text = "$on/$total"
                c.statusBadge.visibility = View.VISIBLE

                for ((idx, key) in order.withIndex()) {
                    val spec = getRowSpecByKey(key) ?: continue
                    listHolder.addView(rowReorderItem(
                        ctx, spec, idx, total,
                        onMoveUp = {
                            val current = getRowOrder().toMutableList()
                            val i = current.indexOf(key)
                            if (i > 0) {
                                current[i] = current[i - 1]
                                current[i - 1] = key
                                setRowOrder(current)
                                renderList()
                            }
                        },
                        onMoveDown = {
                            val current = getRowOrder().toMutableList()
                            val i = current.indexOf(key)
                            if (i >= 0 && i < current.size - 1) {
                                current[i] = current[i + 1]
                                current[i + 1] = key
                                setRowOrder(current)
                                renderList()
                            }
                        }
                    ))
                }
            }

            renderList()
            c.body.addView(labelBlock(ctx, "Section order",
                "Position 1 shows first on the home screen."))
            c.body.addView(listHolder)
            body.addView(c.root)
        }

        // Footer
        run {
            val footer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(ctx, 16), dp(ctx, 20), dp(ctx, 16), dp(ctx, 8))
            }
            footer.addView(TextView(ctx).apply {
                text = "☁  FLUMMOX Repo"
                setTextColor(SUBTEXT); textSize = 13f
                gravity = Gravity.CENTER
            })
            footer.addView(TextView(ctx).apply {
                text = "BINGECLOUD  •  EXTENSION"
                setTextColor(0xFF3D4A5C.toInt()); textSize = 10f
                letterSpacing = 0.15f
                setPadding(0, dp(ctx, 4), 0, 0)
                gravity = Gravity.CENTER
            })
            body.addView(footer)
        }

        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Buttons
        run {
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = bg(BG, 0, ctx)
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 16))
            }
            bar.addView(Button(ctx).apply {
                text = "Cancel"; textSize = 14f; setTextColor(SUBTEXT)
                background = cancelButtonBg(ctx); isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 50), 1f)
                    .apply { rightMargin = dp(ctx, 6) }
                setOnClickListener { dialog.dismiss() }
            })
            bar.addView(Button(ctx).apply {
                text = "Save & Close"; textSize = 14f
                setTextColor(0xFF0A0D14.toInt())
                background = saveButtonBg(ctx); isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 50), 1.4f)
                    .apply { leftMargin = dp(ctx, 6) }
                setOnClickListener { dialog.dismiss(); onSaved() }
            })
            root.addView(bar)
        }

        dialog = AlertDialog.Builder(ctx)
            .setView(root)
            .create()
        dialog.window?.setBackgroundDrawable(bg(BG, 20, ctx))
        dialog.show()
    }

    // ── WebViews ──
    @SuppressLint("SetJavaScriptEnabled")
    private fun openCfWebView(ctx: Context, startUrl: String, domain: String) {
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = bg(BG, 0, ctx)
        }
        val urlBar = TextView(ctx).apply {
            text = startUrl; setTextColor(SUBTEXT); textSize = 11f
            background = bg(SKY_MID, 0, ctx)
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10)); maxLines = 1
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
            background = bg(SKY_MID, 0, ctx)
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        }
        bar.addView(Button(ctx).apply {
            text = "Close"; textSize = 13f; setTextColor(TEXT)
            background = bg(ROW, 20, ctx); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(ctx, 8) }
            setOnClickListener { dlg.dismiss() }
        })
        bar.addView(Button(ctx).apply {
            text = "🍪  Save Cookies"; textSize = 13f
            setTextColor(0xFF0A0D14.toInt())
            background = saveButtonBg(ctx); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
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
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        dlg.setOnDismissListener {
            try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
        }
        dlg.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openFebBoxLogin(ctx: Context, onSaved: () -> Unit) {
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = bg(BG, 0, ctx)
        }
        val banner = TextView(ctx).apply {
            text = "Sign in to FebBox. Your session is stored locally."
            setTextColor(SUBTEXT); textSize = 12f
            background = bg(SKY_MID, 0, ctx)
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
            background = bg(SKY_MID, 0, ctx)
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        }
        bar.addView(Button(ctx).apply {
            text = "Close"; textSize = 13f; setTextColor(TEXT)
            background = bg(ROW, 20, ctx); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(ctx, 8) }
            setOnClickListener { dlg.dismiss() }
        })
        bar.addView(Button(ctx).apply {
            text = "🔑  Save Token"; textSize = 13f
            setTextColor(0xFF0A0D14.toInt())
            background = saveButtonBg(ctx); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
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
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        dlg.setOnDismissListener {
            try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
        }
        dlg.show()
    }
}
