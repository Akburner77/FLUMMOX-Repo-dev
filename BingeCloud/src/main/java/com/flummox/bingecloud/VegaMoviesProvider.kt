package com.flummox.bingecloud

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.api.Log
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI

data class Meta(
    val id: String?, val imdb_id: String?, val type: String?,
    val poster: String?, val background: String?,
    val moviedb_id: Int?, val name: String?, val description: String?,
    val genre: List<String>?, val genres: List<String>?,
    val releaseInfo: String?, val status: String?, val runtime: String?,
    val cast: List<String>?, val language: String?, val country: String?,
    val imdbRating: String?, val year: String?,
    val videos: List<EpisodeDetails>?
)

data class EpisodeDetails(
    val id: String?, val name: String?, val title: String?,
    val season: Int, val episode: Int,
    val released: String?, val firstAired: String?, val overview: String?,
    val thumbnail: String?, val moviedb_id: Int?, val imdb_id: String?,
    val imdbSeason: Int?, val imdbEpisode: Int?
)

data class ResponseData(val meta: Meta)
data class VegaSearchResponse(val hits: List<VegaHit>)
data class VegaHit(val document: VegaDocument)
data class VegaDocument(
    val id: String, val imdb_id: String?, val post_title: String,
    val permalink: String, val post_thumbnail: String
)

data class MirrorLink(
    val quality: String,
    val size: String,
    val mirror: String,
    val url: String
)

open class VegaMoviesProvider : MainAPI() {
    override var mainUrl = "https://vegamovies.mq"
    override var name = "BingeCloud"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cinemetaUrl = "https://v3-cinemeta.strem.io/meta"
    private var domainResolved = false

    private suspend fun ensureDomain() {
        if (domainResolved) return
        try {
            val res = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json")
            val json = JSONObject(res.text)
            val live = json.optString("vegamovies").trim()
            if (live.startsWith("http")) mainUrl = live
            domainResolved = true
        } catch (e: Exception) {
            Log.e("BingeCloud", "Domain resolution failed: ${e.message}")
        }
    }

    override val mainPage = mainPageOf(
        "page/%d/" to "Home",
        "category/web-series/netflix/page/%d/" to "Netflix",
        "category/web-series/disney-plus-hotstar/page/%d/" to "Disney+ Hotstar",
        "category/web-series/amazon-prime-video/page/%d/" to "Amazon Prime",
        "category/anime-series/page/%d/" to "Anime",
        "category/korean-series/page/%d/" to "Korean"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureDomain()
        val url = "$mainUrl/${request.data.format(page)}"
        val document = app.get(url).document
        val items = document.select("div.movies-grid > a").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select("img").attr("alt").replace("Download ", "")
        val href = this.attr("href")
        var posterUrl = this.select("img").attr("src")
        if (!posterUrl.contains("https:")) posterUrl = this.select("img").attr("data-src")
        return newMovieSearchResponse(title, URI(href).path, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        ensureDomain()
        val json = app.get("$mainUrl/search.php?q=$query").text
        val response = tryParseJson<VegaSearchResponse>(json) ?: return null
        return response.hits.map { hit ->
            val doc = hit.document
            newMovieSearchResponse(
                doc.post_title.replace("Download ", ""),
                doc.permalink,
                TvType.Movie
            ) { this.posterUrl = doc.post_thumbnail }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureDomain()
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = app.get(fullUrl).document
        var title = document.select("title").text().replace("Download ", "")
        var posterUrl = document.select("p > img").attr("src")
        val imdbUrl = document.select("a[href*=\"imdb\"]").attr("href")
        val imdbId = imdbUrl.substringAfter("title/").substringBefore("/")

        val isSeries = document.selectFirst("h3:matches((?i)Series-SYNOPSIS/PLOT)") != null ||
                document.selectFirst("h3:matches((?i)Series Info)") != null ||
                document.selectFirst("h3:matches((?i)Series synopsis/PLOT)") != null

        var description = document
            .selectFirst("h3:has(span:matches((?i)SYNOPSIS/PLOT))")
            ?.nextElementSibling()?.text()

        var cast: List<String> = emptyList()
        var genre: List<String> = emptyList()
        var imdbRating = ""
        var year = ""
        var background = posterUrl

        if (imdbId.isNotEmpty()) {
            val jsonResponse = app.get("$cinemetaUrl/${if (isSeries) "series" else "movie"}/$imdbId.json").text
            val responseData = tryParseJson<ResponseData>(jsonResponse)
            if (responseData != null) {
                description = responseData.meta.description ?: description
                cast = responseData.meta.cast ?: emptyList()
                title = responseData.meta.name ?: title
                genre = responseData.meta.genre ?: emptyList()
                imdbRating = responseData.meta.imdbRating ?: ""
                year = responseData.meta.year ?: ""
                posterUrl = responseData.meta.poster ?: posterUrl
                background = responseData.meta.background ?: background
            }
        }

        return if (isSeries) {
            // ... TV series logic (same as before, keep your existing episode parsing) ...
            val episodes = mutableListOf<Episode>()
            val seasonHeaders = document.select(
                "main > h3:matches((?i)(4K|[0-9]*0p)),main > h5:matches((?i)(4K|[0-9]*0p))"
            ).filter { !it.text().contains("Zip", true) }

            val episodesMap: MutableMap<Pair<Int, Int>, MutableList<MirrorLink>> = mutableMapOf()

            for (header in seasonHeaders) {
                val headerText = header.text()
                val seasonMatch = Regex("""(?:Season\s*|S)(\d+)""", RegexOption.IGNORE_CASE).find(headerText)
                val season = seasonMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val quality = extractQualityFromHeader(headerText)

                val nextEl = header.nextElementSibling()
                val links = if (nextEl != null && nextEl.tagName() == "p") nextEl.select("a") else header.select("a")

                val downloadLink = links.firstOrNull {
                    it.text().contains("Download", true) || it.text().contains("Episode", true)
                } ?: links.firstOrNull { it.text().contains("V-Cloud", true) }
                ?: links.firstOrNull { it.text().contains("G-Direct", true) }
                ?: continue

                val mirrorLinks = fetchMirrorsFromDownloadPage(downloadLink.attr("href"), quality)
                mirrorLinks.forEach { mirrorLink ->
                    val episodeNumber = episodesMap.keys.count { it.first == season } + 1
                    val key = Pair(season, episodeNumber)
                    episodesMap.getOrPut(key) { mutableListOf() }.add(mirrorLink)
                }
            }

            for ((key, mirrors) in episodesMap) {
                episodes.add(newEpisode(mirrors) {
                    this.name = "S${key.first} E${key.second}"
                    this.season = key.first
                    this.episode = key.second
                })
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl; this.plot = description; this.tags = genre
                this.score = Score.from10(imdbRating); this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background; addActors(cast)
            }
        } else {
            // ─────────── MOVIES: PARALLEL mirror fetching ───────────
            val allMirrors = mutableListOf<MirrorLink>()
            val qualityHeaders = document.select("main > h3, main > h5, main > h4").filter {
                val txt = it.text()
                txt.contains(Regex("""\d{3,4}[pP]""")) && !txt.contains("Zip", true)
            }

            // PARALLEL: fetch all quality download pages simultaneously
            val results = qualityHeaders.amap { header ->
                val headerText = header.text()
                val quality = extractQualityFromHeader(headerText)
                val size = extractSizeFromHeader(headerText)
                val nextEl = header.nextElementSibling()
                val links = if (nextEl != null && nextEl.tagName() == "p") nextEl.select("a") else header.select("a")
                val downloadLink = links.firstOrNull { it.text().contains("Download", true) }
                    ?: links.firstOrNull { it.text().contains("V-Cloud", true) }
                    ?: links.firstOrNull { it.text().contains("G-Direct", true) }
                    ?: return@amap emptyList<MirrorLink>()
                fetchMirrorsFromDownloadPage(downloadLink.attr("href"), quality, size)
            }
            results.forEach { allMirrors.addAll(it) }

            if (allMirrors.isEmpty()) {
                val buttons = document.select("a:has(button.dwd-button)")
                val fallback = buttons.amap { button ->
                    fetchMirrorsFromDownloadPage(fixUrl(button.attr("href")), "Unknown", "")
                }
                fallback.forEach { allMirrors.addAll(it) }
            }

            Log.d("BingeCloud", "Total mirrors: ${allMirrors.size}")
            allMirrors.forEach { Log.d("BingeCloud", "${it.mirror} | ${it.quality} | ${it.size}") }

            newMovieLoadResponse(title, url, TvType.Movie, allMirrors) {
                this.posterUrl = posterUrl; this.plot = description; this.tags = genre
                this.score = Score.from10(imdbRating); this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background; addActors(cast)
            }
        }
    }

    // ─────────── IMPROVED MIRROR DETECTION ───────────
    // Matches by button TEXT (more reliable than URL keywords)
    private suspend fun fetchMirrorsFromDownloadPage(
        downloadUrl: String,
        quality: String,
        size: String = ""
    ): List<MirrorLink> {
        val mirrors = mutableListOf<MirrorLink>()
        try {
            val doc = app.get(fixUrl(downloadUrl)).document

            // Collect all anchors — the nexdrive.fit page has buttons as <a> tags
            val anchors = doc.select("a")

            for (a in anchors) {
                val href = a.attr("href").trim()
                val text = a.text().trim().lowercase()
                if (href.isEmpty() || href.startsWith("#")) continue

                // Match by TEXT — most reliable
                when {
                    text.contains("v-cloud") || text.contains("vcloud") ||
                    href.contains("vcloud", true) -> {
                        mirrors.add(MirrorLink(quality, size, "V-Cloud", href))
                    }
                    text.contains("v-drive") || text.contains("vdrive") ||
                    href.contains("vdrive", true) -> {
                        mirrors.add(MirrorLink(quality, size, "V-Drive", href))
                    }
                    text.contains("g-direct") || text.contains("gdirect") ||
                    href.contains("gdirect", true) ||
                    href.contains("drive.google.com", true) -> {
                        mirrors.add(MirrorLink(quality, size, "G-Direct", href))
                    }
                    text.contains("filepress") || text.contains("gdflix") ||
                    href.contains("filepress", true) || href.contains("gdflix", true) -> {
                        mirrors.add(MirrorLink(quality, size, "Filepress", href))
                    }
                    // Fallback: any button that mentions "download" and is a real URL
                    text.contains("download") && href.startsWith("http") -> {
                        // Only add if not already captured
                        if (mirrors.none { it.url == href }) {
                            mirrors.add(MirrorLink(quality, size, "Direct", href))
                        }
                    }
                }
            }

            // Also check <p> tags (some pages nest anchors inside <p>)
            doc.select("p a").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isEmpty() || mirrors.any { it.url == href }) return@forEach
                val text = a.text().trim().lowercase()
                when {
                    text.contains("v-cloud") || href.contains("vcloud", true) ->
                        mirrors.add(MirrorLink(quality, size, "V-Cloud", href))
                    text.contains("v-drive") || href.contains("vdrive", true) ->
                        mirrors.add(MirrorLink(quality, size, "V-Drive", href))
                    text.contains("g-direct") || href.contains("gdirect", true) ->
                        mirrors.add(MirrorLink(quality, size, "G-Direct", href))
                    text.contains("filepress") || href.contains("filepress", true) ->
                        mirrors.add(MirrorLink(quality, size, "Filepress", href))
                }
            }
        } catch (e: Exception) {
            Log.e("BingeCloud", "fetchMirrors error: ${e.message}")
        }
        return mirrors
    }

    private fun extractQualityFromHeader(header: String): String =
        Regex("""(\d{3,4}[pP])""").find(header)?.value ?: "Unknown"

    private fun extractSizeFromHeader(header: String): String =
        Regex("""\[([^\]]*(?:MB|GB)[^\]]*)\]""").find(header)?.groupValues?.getOrNull(1) ?: ""

    // ─────────── PARALLEL LINK LOADING ───────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mirrors = try {
            parseJson<List<MirrorLink>>(data)
        } catch (e: Exception) {
            Log.e("BingeCloud", "Failed to parse mirrors: ${e.message}")
            return false
        }

        Log.d("BingeCloud", "loadLinks: ${mirrors.size} mirrors, loading in parallel")

        // PARALLEL: all mirrors at once, not one-by-one
        mirrors.amap { mirror ->
            try {
                when {
                    mirror.url.contains("vcloud", true) ->
                        VCloud().getUrl(mirror.url, "", subtitleCallback, callback)
                    mirror.url.contains("vdrive", true) ->
                        VDrive().getUrl(mirror.url, "", subtitleCallback, callback)
                    mirror.url.contains("gdirect", true) ||
                    mirror.url.contains("drive.google.com", true) ->
                        GDirect().getUrl(mirror.url, "", subtitleCallback, callback)
                    mirror.url.contains("filepress", true) ||
                    mirror.url.contains("gdflix", true) ->
                        Filepress().getUrl(mirror.url, "", subtitleCallback, callback)
                    else -> loadExtractor(mirror.url, "", subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("BingeCloud", "${mirror.mirror} failed: ${e.message}")
            }
        }
        return true
    }
}
