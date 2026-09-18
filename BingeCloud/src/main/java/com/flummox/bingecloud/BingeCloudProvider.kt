package com.flummox.bingecloud

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

private const val SEP = "|"
private const val ROW_TAG = "::"
private const val PREFETCH_DEBOUNCE_MS = 800L

private val PREFETCH_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private var activePrefetchJob: Job? = null
private var lastHomeRenderMs: Long = 0L
private const val HOME_GRACE_MS = 5000L

fun StreamQuery.cacheKey(): String =
    "scrape:${title.lowercase()}:${year}:${type}:${season}:${episode}"

// ── home content filter: drop daily soaps / talk / reality ──
private val HOME_BLOCKED_GENRES = setOf(
    "soap", "talk", "talk show", "reality", "reality tv", "news", "game show"
)

private fun AioMeta.isJunk(): Boolean {
    val g = genres ?: return false
    return g.any { it.lowercase().trim() in HOME_BLOCKED_GENRES }
}

open class BingeCloudProvider : MainAPI() {
    override var mainUrl = AIOMETA_BASE
    override var name = "BingeCloud"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val mainPage = mainPageOf(
        *Settings.getRowOrder()
            .mapNotNull { key ->
                val spec = Settings.getRowSpecByKey(key) ?: return@mapNotNull null
                if (!Settings.isRowEnabled(key)) return@mapNotNull null
                val id = if (spec.defaultGenre != null)
                    "${spec.catalogId}$ROW_TAG${spec.defaultGenre}"
                else spec.catalogId
                "${spec.type}$ROW_TAG$id$ROW_TAG${spec.name}" to spec.name
            }.toTypedArray()
    )

    // ── home ──
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val parts = request.data.split(ROW_TAG)
        if (parts.size < 2) return null
        lastHomeRenderMs = System.currentTimeMillis()
        val items = aioFetchCatalog(parts[0], parts[1], parts.getOrNull(2), (page - 1) * 25)
        .filter { !it.isJunk() }
        .mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── search ──
    override suspend fun search(query: String): List<SearchResponse>? {
        val key = BuildConfig.TMDB_API_KEY
        if (key.isBlank()) {
            BCLog.e("TMDB key missing — falling back to Aiometa search")
            return searchViaAiometa(query)
        }
        return searchViaTmdb(query, key)
    }

    private suspend fun searchViaTmdb(query: String, key: String): List<SearchResponse>? {
        val out = mutableListOf<SearchResponse>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/multi" +
                "?api_key=$key&language=en-US&query=$encoded&page=1&include_adult=false"
            val json = app.get(url).text
            val parsed = tryParseJson<TmdbSearchResponse>(json)
            parsed?.results?.forEach { item ->
                item.toSearchResponse()?.let { out.add(it) }
            }
        } catch (e: Exception) {
            BCLog.e("TMDB search failed: ${e.message}")
        }
        BCLog.d("TMDB search '$query': ${out.size} results")
        return out
    }

    private suspend fun searchViaAiometa(query: String): List<SearchResponse>? {
        val results = mutableListOf<SearchResponse>()
        for (t in listOf("movie", "series", "anime")) {
            try { results.addAll(aioSearch(query, t).mapNotNull { it.toSearchResponse() }) }
            catch (e: Exception) { BCLog.e("Search $t failed: ${e.message}") }
        }
        return results
    }

    private fun AioMeta.toSearchResponse(): SearchResponse? {
        val metaId = this.id ?: return null
        val metaName = this.name ?: return null
        val typeStr = this.type ?: "movie"
        val tvType = when {
            typeStr.contains("series", true) -> TvType.TvSeries
            typeStr.contains("anime", true) -> TvType.Anime
            else -> TvType.Movie
        }
        val yearInt = (this.releaseInfo ?: this.year)?.take(4)?.toIntOrNull()
        return newMovieSearchResponse(metaName, "/$typeStr$SEP$metaId", tvType) {
            this.posterUrl = this@toSearchResponse.poster
            this.year = yearInt
        }
    }

    private fun TmdbSearchItem.toSearchResponse(): SearchResponse? {
        val id = this.id ?: return null
        val mt = this.media_type ?: return null
        if (mt != "movie" && mt != "tv") return null
        val title = this.title ?: this.name ?: return null
        val isAnime = mt == "tv" &&
            original_language == "ja" &&
            (genre_ids?.contains(16) == true)
        val tvType = when {
            mt == "movie" -> TvType.Movie
            isAnime -> TvType.Anime
            else -> TvType.TvSeries
        }
        val loadType = if (mt == "movie") "movie" else "series"
        val metaId = "tmdb:$id"
        val year = (release_date ?: first_air_date)?.take(4)?.toIntOrNull()
        return newMovieSearchResponse(title, "/$loadType$SEP$metaId", tvType) {
            this.posterUrl = poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            this.year = year
        }
    }

    // ── load ──
    override suspend fun load(url: String): LoadResponse? {
        val clean = url.removePrefix(mainUrl).removePrefix("/")
        val parts = clean.split(SEP)
        if (parts.size < 2) return null
        val type = parts[0]
        val metaId = parts[1]
        val meta = aioFetchMeta(type, metaId) ?: return null
        val name = meta.name ?: return null
        val tvType = when {
            type.contains("series", true) -> TvType.TvSeries
            type.contains("anime", true) -> TvType.Anime
            else -> TvType.Movie
        }
        val yearInt = (meta.releaseInfo ?: meta.year)?.take(4)?.toIntOrNull()
        val actors = meta.app_extras?.cast?.mapNotNull { c ->
            val n = c.name ?: return@mapNotNull null
            Actor(n, c.photo)
        } ?: emptyList()
        val videos = meta.videos ?: emptyList()
        val statusTag = computeStatusTag(meta, videos, tvType)
        val desc = meta.description ?: ""
        val plot = if (statusTag.isNotBlank() && desc.isNotBlank()) "<b>$statusTag</b><br><br>$desc"
            else if (statusTag.isNotBlank()) "<b>$statusTag</b>" else desc

        val sinceHome = System.currentTimeMillis() - lastHomeRenderMs
        val fromHomeBanner = sinceHome in 0 until HOME_GRACE_MS
        if (fromHomeBanner) {
            BCLog.d("load() preview (no prefetch): ${name.take(40)} [${sinceHome}ms since home]")
        }
        if (Settings.isPrefetchEnabled() && !fromHomeBanner) {
            val prefetchQuery: StreamQuery? = when {
                tvType == TvType.Movie && videos.isEmpty() ->
                    StreamQuery(name, yearInt?.toString() ?: "", "movie", meta.imdb_id ?: "")
                videos.isNotEmpty() -> {
                    val first = videos.firstOrNull()
                    val s = first?.season
                    val e = first?.episode
                    if (s != null && e != null && s > 0)
                        StreamQuery(name, yearInt?.toString() ?: "", "series", meta.imdb_id ?: "", s, e)
                    else null
                }
                else -> null
            }
            if (prefetchQuery != null) {
                val key = prefetchQuery.cacheKey()
                if (BCCache.getMirrors(key) == null) {
                    activePrefetchJob?.cancel()
                    activePrefetchJob = PREFETCH_SCOPE.launch {
                        try {
                            delay(PREFETCH_DEBOUNCE_MS)
                            if (BCCache.getMirrors(key) != null) return@launch
                            BCLog.d("smart prefetch: ${prefetchQuery.title} S${prefetchQuery.season}E${prefetchQuery.episode}")
                            val mirrors = scrapeAllSources(prefetchQuery)
                            BCCache.putMirrors(key, mirrors)
                            BCLog.d("smart prefetch done: ${mirrors.size} mirrors")
                        } catch (e: CancellationException) {
                            BCLog.d("smart prefetch cancelled")
                        } catch (e: Exception) {
                            BCLog.e("smart prefetch failed: ${e.message}")
                        }
                    }
                }
            }
        }

        return if (tvType == TvType.Movie && videos.isEmpty()) {
            val q = StreamQuery(name, yearInt?.toString() ?: "", "movie", meta.imdb_id ?: "")
            newMovieLoadResponse(name, url, TvType.Movie, encodeQuery(q)) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.plot = plot
                this.year = yearInt
                this.tags = meta.genres
                this.score = Score.from10(meta.imdbRating)
                if (actors.isNotEmpty()) addActors(actors)
            }
        } else {
            val episodes = videos.mapIndexedNotNull { idx, v ->
                val s = v.season ?: return@mapIndexedNotNull null
                val e = v.episode ?: return@mapIndexedNotNull null
                val next = videos.getOrNull(idx + 1)
                val q = StreamQuery(
                    name, yearInt?.toString() ?: "", "series", meta.imdb_id ?: "",
                    s, e,
                    next?.season ?: 0, next?.episode ?: 0
                )
                newEpisode(encodeQuery(q)) {
                    this.name = v.title ?: "Episode $e"
                    this.season = s
                    this.episode = e
                    this.posterUrl = v.thumbnail ?: meta.background
                    this.description = v.overview
                }
            }
            val responseType = if (tvType == TvType.Anime) TvType.Anime else TvType.TvSeries
            newTvSeriesLoadResponse(name, url, responseType, episodes) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.plot = plot
                this.year = yearInt
                this.tags = meta.genres
                this.score = Score.from10(meta.imdbRating)
                if (actors.isNotEmpty()) addActors(actors)
            }
        }
    }

    // ── loadLinks ──
    
    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: ""
    } catch (_: Exception) { "" }

    private fun qualityRank(q: String): Int = when {
        q.contains("2160", true) || q.contains("4k", true) -> 2160
        q.contains("1440", true) || q.contains("2k", true) -> 1440
        q.contains("1080", true) -> 1080
        q.contains("720", true) -> 720
        q.contains("480", true) -> 480
        q.contains("360", true) -> 360
        else -> 0
    }

    private fun audioPriority(mirror: String, source: String): Int {
        if (source != "MB") return 0
        val l = mirror.lowercase()
        return when {
            l.contains("original") -> 0
            l.contains("hindi") -> 1
            l.contains("english") -> 2
            l.contains("spanish") -> 5
            l.contains("portug") -> 6
            else -> 3
        }
    }

    private fun computeStatusTag(meta: AioMeta, videos: List<AioVideo>, tvType: TvType): String {
        if (tvType == TvType.Movie) return ""
        val rel = meta.releaseInfo ?: ""
        if (rel.endsWith("-")) return "Ongoing"
        if (rel.matches(Regex("""\d{4}-\d{4}"""))) return "Completed"
        val lastEp = videos.lastOrNull()
        if (lastEp?.available == false) return "Ongoing"
        val year = rel.take(4).toIntOrNull()
        val nowYear = Calendar.getInstance().get(Calendar.YEAR)
        if (year != null && year < nowYear - 1) return "Completed"
        return ""
    }
}

private fun encodeQuery(q: StreamQuery): String {
    val o = JSONObject()
    o.put("t", q.title); o.put("y", q.year); o.put("ty", q.type)
    o.put("s", q.season); o.put("e", q.episode); o.put("i", q.imdbId)
    o.put("ns", q.nextSeason); o.put("ne", q.nextEpisode)
    return o.toString()
}

private fun decodeQuery(s: String): StreamQuery? = try {
    val o = JSONObject(s)
    StreamQuery(
        o.optString("t"), o.optString("y"),
        o.optString("ty", "movie"), o.optString("i"),
        o.optInt("s", 0), o.optInt("e", 0),
        o.optInt("ns", 0), o.optInt("ne", 0)
    )
} catch (e: Exception) { null }

private data class TmdbSearchResponse(val results: List<TmdbSearchItem>? = null)
private data class TmdbSearchItem(
    val id: Int? = null,
    val media_type: String? = null,
    val title: String? = null,
    val name: String? = null,
    val poster_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val original_language: String? = null,
    val genre_ids: List<Int>? = null
)
