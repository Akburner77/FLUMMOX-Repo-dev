package com.flummox.bingecloud

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import org.json.JSONObject
import java.util.Calendar

private const val SEP = "|"
private const val ROW_TAG = "::"

open class BingeCloudProvider : MainAPI() {
    override var mainUrl = AIOMETA_BASE
    override var name = "BingeCloud"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val rows = listOf(
        Triple("movie", "tmdb.trending", "Trending Movies"),
        Triple("series", "tmdb.trending", "Trending Series"),
        Triple("movie", "tmdb.top", "Popular Movies"),
        Triple("series", "tmdb.top", "Popular Series"),
        Triple("anime", "mal.top_anime", "Top Anime"),
        Triple("anime", "mal.airing", "Airing Now"),
        Triple("anime", "mal.top_movies", "Top Anime Movies"),
        Triple("anime", "mal.most_popular", "Most Popular Anime")
    )

    override val mainPage = mainPageOf(
        *rows.map { (type, id, name) -> "$type$ROW_TAG$id$ROW_TAG$name" to name }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val parts = request.data.split(ROW_TAG)
        if (parts.size < 2) return null
        val type = parts[0]
        val catalogId = parts[1]
        val skip = (page - 1) * 25
        val metas = aioFetchCatalog(type, catalogId, null, skip)
        val items = metas.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val results = mutableListOf<SearchResponse>()
        for (t in listOf("movie", "series", "anime")) {
            try {
                results.addAll(aioSearch(query, t).mapNotNull { it.toSearchResponse() })
            } catch (e: Exception) {
                Log.e("BingeCloud", "Search $t failed: ${e.message}")
            }
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
        val plotWithStatus = if (statusTag.isNotBlank() && desc.isNotBlank())
            "<b>$statusTag</b><br><br>$desc"
        else if (statusTag.isNotBlank())
            "<b>$statusTag</b>"
        else desc

        return if (tvType == TvType.Movie && videos.isEmpty()) {
            val q = StreamQuery(
                title = name,
                year = yearInt?.toString() ?: "",
                type = "movie",
                imdbId = meta.imdb_id ?: ""
            )
            newMovieLoadResponse(name, url, TvType.Movie, encodeQuery(q)) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.plot = plotWithStatus
                this.year = yearInt
                this.tags = meta.genres
                this.score = Score.from10(meta.imdbRating)
                if (actors.isNotEmpty()) addActors(actors)
            }
        } else {
            val episodes = videos.mapNotNull { v ->
                val s = v.season ?: return@mapNotNull null
                val e = v.episode ?: return@mapNotNull null
                val q = StreamQuery(
                    title = name,
                    year = yearInt?.toString() ?: "",
                    type = "series",
                    season = s,
                    episode = e,
                    imdbId = meta.imdb_id ?: ""
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
                this.plot = plotWithStatus
                this.year = yearInt
                this.tags = meta.genres
                this.score = Score.from10(meta.imdbRating)
                if (actors.isNotEmpty()) addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val query = decodeQuery(data) ?: return false
        Log.d("BingeCloud", "loadLinks: ${query.title} (${query.year}) ${query.type} S${query.season}E${query.episode}")

        val mirrors = scrapeAllSources(query)
        if (mirrors.isEmpty()) return false

        val sorted = mirrors.sortedByDescending { qualityRank(it.quality) }
        Log.d("BingeCloud", "loadLinks: ${sorted.size} mirrors after sort")

        sorted.amap { m ->
            try {
                when {
                    m.url.contains("vcloud", true) || m.url.contains("hubcloud", true) ->
                        VCloud(m.source).getUrl(m.url, "", subtitleCallback, callback)
                    else ->
                        loadExtractor(m.url, "", subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("BingeCloud", "${m.mirror} failed: ${e.message}")
            }
        }
        return true
    }

    private fun qualityRank(q: String): Int = when {
        q.contains("2160", true) || q.contains("4k", true) -> 2160
        q.contains("1080", true) -> 1080
        q.contains("720", true) -> 720
        q.contains("480", true) -> 480
        q.contains("360", true) -> 360
        else -> 0
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
    o.put("t", q.title)
    o.put("y", q.year)
    o.put("ty", q.type)
    o.put("s", q.season)
    o.put("e", q.episode)
    o.put("i", q.imdbId)
    return o.toString()
}

private fun decodeQuery(s: String): StreamQuery? = try {
    val o = JSONObject(s)
    StreamQuery(
        title = o.optString("t"),
        year = o.optString("y"),
        type = o.optString("ty", "movie"),
        season = o.optInt("s", 0),
        episode = o.optInt("e", 0),
        imdbId = o.optString("i")
    )
} catch (e: Exception) {
    null
}
