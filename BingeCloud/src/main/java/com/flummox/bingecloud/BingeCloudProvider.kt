package com.flummox.bingecloud

import com.flummox.bingecore.ProgressiveResolver
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar

private const val SEP = "|"
private const val ROW_TAG = "::"
private const val PREFETCH_DEBOUNCE_MS = 800L

private val PREFETCH_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private var activePrefetchJob: Job? = null

private fun StreamQuery.cacheKey(): String =
    "scrape:${title.lowercase()}:${year}:${type}:${season}:${episode}"

open class BingeCloudProvider : MainAPI() {
    override var mainUrl = AIOMETA_BASE
    override var name = "BingeCloud"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
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
        val items = aioFetchCatalog(parts[0], parts[1], parts.getOrNull(2), (page - 1) * 25)
            .mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── search ──
    override suspend fun search(query: String): List<SearchResponse>? {
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

        // ── prefetch: debounced 800ms ──
        if (Settings.isPrefetchEnabled()) {
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

    // ── loadLinks: score, sort, progressive emit ──
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val query = decodeQuery(data) ?: return false
        BCLog.section("loadLinks: ${query.title} (${query.year}) ${query.type} S${query.season}E${query.episode}")

        val cached = BCCache.getMirrors(query.cacheKey())
        val mirrors = cached ?: com.flummox.bingecore.SpeedBooster.dedupedScrape(
            "mirrors:${query.cacheKey()}"
        ) { scrapeAllSources(query) }
        if (cached != null) BCLog.d("using smart prefetch cache: ${mirrors.size} mirrors")
        if (mirrors.isEmpty()) { BCLog.e("loadLinks: no mirrors"); return false }

        // ── 1. score all mirrors (URL math only, no network) ──
        val scored = mirrors
            .map { it to LinkScore.prelimScore(it) }
            .sortedWith(
                compareByDescending<Pair<ScrapedMirror, Int>> { it.second }
                    .thenByDescending { qualityRank(it.first.quality) }
                    .thenBy { audioPriority(it.first.mirror, it.first.source) }
            )

        // ── 2. apply quality preference on top ──
        val pref = Settings.getQualityPref()
        val prefRank = qualityRank(pref)
        val finalOrder = scored.sortedWith(
            compareByDescending<Pair<ScrapedMirror, Int>> {
                if (prefRank > 0 && qualityRank(it.first.quality) == prefRank) 1 else 0
            }
                .thenByDescending { it.second }
                .thenByDescending { qualityRank(it.first.quality) }
                .thenBy { audioPriority(it.first.mirror, it.first.source) }
        )

        val concurrency = Settings.getConcurrency().coerceIn(1, 50)
        BCLog.d("resolving ${finalOrder.size} mirrors (c=$concurrency)")

        // ── 3. progressive resolve via bingecore ──
        ProgressiveResolver.run(
            items = finalOrder,
            concurrency = concurrency,
            softCapMs = 2500L,
            minBeforeReturn = 3,
            logTag = "BingeCloud",
            resolve = { pair ->
                val (m, score) = pair
                val emoji = LinkScore.emoji(score)
                if (m.source == "MB") {
                    val linkType = when {
                        m.url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                        m.url.contains(".mpd", true) -> ExtractorLinkType.DASH
                        else -> ExtractorLinkType.VIDEO
                    }
                    val display = "$emoji${m.quality} •MB ${m.mirror}"
                    BCLog.d("MB link: $display (score=$score)")
                    val hdrs = m.headers
                    val link = newExtractorLink("MovieBox", display, m.url, linkType) {
                        this.referer = "https://h5.aoneroom.com/"
                        if (hdrs != null) this.headers = hdrs
                    }
                    HostHealth.recordSuccess("mb.local")
                    listOf(link)
                } else {
                    val finalUrl = resolveWrapper(m.url)
                    if (finalUrl == null) {
                        BCLog.d("unresolved: ${m.mirror}")
                        HostHealth.recordFailure(hostOf(m.url))
                        emptyList()
                    } else {
                        val bag = mutableListOf<ExtractorLink>()
                        VCloud(m.source, m.mirror, m.quality, emoji)
                            .getUrl(finalUrl, "", subtitleCallback) { bag.add(it) }
                        if (bag.isEmpty()) HostHealth.recordFailure(hostOf(m.url))
                        else HostHealth.recordSuccess(hostOf(m.url))
                        bag
                    }
                }
            },
            onEmit = { link -> callback.invoke(link) },
            onFail = { pair, e ->
                val (m, _) = pair
                BCLog.e("${m.mirror} failed: ${e.message}")
                HostHealth.recordFailure(hostOf(m.url))
            }
        )

        // ── 4. prefetch next episode ──
        if (Settings.isPrefetchEnabled() && query.type == "series"
            && query.nextSeason > 0 && query.nextEpisode > 0) {
            val nextQ = StreamQuery(
                query.title, query.year, "series", query.imdbId,
                query.nextSeason, query.nextEpisode
            )
            val nextKey = nextQ.cacheKey()
            if (BCCache.getMirrors(nextKey) == null) {
                activePrefetchJob?.cancel()
                activePrefetchJob = PREFETCH_SCOPE.launch {
                    try {
                        BCLog.d("smart prefetch next: S${query.nextSeason}E${query.nextEpisode}")
                        val nextMirrors = scrapeAllSources(nextQ)
                        BCCache.putMirrors(nextKey, nextMirrors)
                        BCLog.d("smart prefetch next done: ${nextMirrors.size} mirrors")
                    } catch (e: CancellationException) {
                        BCLog.d("smart prefetch next cancelled")
                    } catch (e: Exception) {
                        BCLog.e("smart prefetch next failed: ${e.message}")
                    }
                }
            }
        }
        return true
    }

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
