package com.flummox.bingecloud

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.api.Log
import kotlinx.coroutines.runBlocking
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

// A single playable link: quality + mirror + url
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
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cinemetaUrl = "https://v3-cinemeta.strem.io/meta"

    init {
        runBlocking {
            basemainUrl?.let { mainUrl = it }
        }
    }

    companion object {
        val basemainUrl: String? by lazy {
            runBlocking {
                try {
                    val response = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json")
                    val jsonObject = JSONObject(response.text)
                    jsonObject.optString("vegamovies")
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
        "$mainUrl/category/web-series/netflix/page/%d/" to "Netflix",
        "$mainUrl/category/web-series/disney-plus-hotstar/page/%d/" to "Disney+ Hotstar",
        "$mainUrl/category/web-series/amazon-prime-video/page/%d/" to "Amazon Prime",
        "$mainUrl/category/web-series/mx-original/page/%d/" to "MX Original",
        "$mainUrl/category/anime-series/page/%d/" to "Anime Series",
        "$mainUrl/category/korean-series/page/%d/" to "Korean Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data.format(page)).document
        val home = document.select("div.movies-grid > a").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
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
        val json = app.get("$mainUrl/search.php?q=$query").text
        val response = tryParseJson<VegaSearchResponse>(json) ?: return null
        return response.hits.map { hit ->
            val doc = hit.document
            newMovieSearchResponse(
                doc.post_title.replace("Download ", ""),
                doc.permalink,
                TvType.Movie
            ) {
                this.posterUrl = doc.post_thumbnail
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(fixUrl(url)).document
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
                val links = if (nextEl != null && nextEl.tagName() == "p") {
                    nextEl.select("a")
                } else {
                    header.select("a")
                }

                // The old code only found V-Cloud. Now we find the "Download" button
                // which leads to the nexdrive.fit page with ALL mirrors.
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
                episodes.add(
                    newEpisode(mirrors) {
                        this.name = "S${key.first} E${key.second}"
                        this.season = key.first
                        this.episode = key.second
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
                if (imdbId.isNotEmpty()) addImdbUrl(imdbUrl)
            }
        } else {
            // For movies: capture ALL qualities and ALL mirrors
            val allMirrors = mutableListOf<MirrorLink>()

            // Find every quality header (480p, 720p, 1080p, etc.)
            val qualityHeaders = document.select(
                "main > h3, main > h5, main > h4"
            ).filter {
                val txt = it.text()
                txt.contains(Regex("""\d{3,4}[pP]""")) && !txt.contains("Zip", true)
            }

            for (header in qualityHeaders) {
                val headerText = header.text()
                val quality = extractQualityFromHeader(headerText)
                val size = extractSizeFromHeader(headerText)

                val nextEl = header.nextElementSibling()
                val links = if (nextEl != null && nextEl.tagName() == "p") {
                    nextEl.select("a")
                } else {
                    header.select("a")
                }

                val downloadLink = links.firstOrNull {
                    it.text().contains("Download", true)
                } ?: links.firstOrNull { it.text().contains("V-Cloud", true) }
                ?: links.firstOrNull { it.text().contains("G-Direct", true) }
                ?: continue

                val mirrors = fetchMirrorsFromDownloadPage(downloadLink.attr("href"), quality, size)
                allMirrors.addAll(mirrors)
            }

            // Fallback: if no quality headers found, try the old direct button approach
            if (allMirrors.isEmpty()) {
                val buttons = document.select("a:has(button.dwd-button)")
                for (button in buttons) {
                    val link = fixUrl(button.attr("href"))
                    val mirrors = fetchMirrorsFromDownloadPage(link, "Unknown", "")
                    allMirrors.addAll(mirrors)
                }
            }

            Log.d("BingeCloud", "Total mirrors found: ${allMirrors.size}")
            allMirrors.forEach { Log.d("BingeCloud", "Mirror: ${it.mirror} | ${it.quality} | ${it.url}") }

            newMovieLoadResponse(title, url, TvType.Movie, allMirrors) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
                if (imdbId.isNotEmpty()) addImdbUrl(imdbUrl)
            }
        }
    }

    /**
     * Given a download button URL (typically nexdrive.fit), fetch the page
     * and extract ALL mirror buttons: V-Cloud, V-Drive [Multi], G-Direct, Filepress.
     */
    private suspend fun fetchMirrorsFromDownloadPage(
        downloadUrl: String,
        quality: String,
        size: String = ""
    ): List<MirrorLink> {
        val mirrors = mutableListOf<MirrorLink>()
        try {
            val doc = app.get(fixUrl(downloadUrl)).document
            Log.d("BingeCloud", "Fetching mirrors from: $downloadUrl")
            Log.d("BingeCloud", "Page title: ${doc.title()}")

            // Find every anchor on the page
            val anchors = doc.select("a")

            for (a in anchors) {
                val href = a.attr("href").trim()
                val text = a.text().trim()
                if (href.isEmpty() || href.startsWith("#")) continue

                when {
                    href.contains("vcloud", true) || text.contains("V-Cloud", true) -> {
                        mirrors.add(MirrorLink(quality, size, "V-Cloud", href))
                    }
                    href.contains("vdrive", true) || text.contains("V-Drive", true) -> {
                        mirrors.add(MirrorLink(quality, size, "V-Drive", href))
                    }
                    href.contains("gdirect", true) || text.contains("G-Direct", true) -> {
                        mirrors.add(MirrorLink(quality, size, "G-Direct", href))
                    }
                    href.contains("filepress", true) || text.contains("Filepress", true) -> {
                        mirrors.add(MirrorLink(quality, size, "Filepress", href))
                    }
                    href.contains("gdflix", true) || text.contains("GDFlix", true) -> {
                        mirrors.add(MirrorLink(quality, size, "GDFlix", href))
                    }
                }
            }

            // Also check <p> tags containing anchors (common on these pages)
            val pAnchors = doc.select("p > a")
            for (a in pAnchors) {
                val href = a.attr("href").trim()
                if (href.isEmpty()) continue
                val text = a.text().trim()
                if (mirrors.any { it.url == href }) continue

                when {
                    href.contains("vcloud", true) -> mirrors.add(MirrorLink(quality, size, "V-Cloud", href))
                    href.contains("vdrive", true) -> mirrors.add(MirrorLink(quality, size, "V-Drive", href))
                    href.contains("gdirect", true) -> mirrors.add(MirrorLink(quality, size, "G-Direct", href))
                    href.contains("filepress", true) -> mirrors.add(MirrorLink(quality, size, "Filepress", href))
                    href.contains("gdflix", true) -> mirrors.add(MirrorLink(quality, size, "GDFlix", href))
                }
            }

        } catch (e: Exception) {
            Log.e("BingeCloud", "fetchMirrors error: ${e.message}")
        }
        return mirrors
    }

    private fun extractQualityFromHeader(header: String): String {
        val match = Regex("""(\d{3,4}[pP])""").find(header)
        return match?.value ?: "Unknown"
    }

    private fun extractSizeFromHeader(header: String): String {
        val match = Regex("""\[([^\]]*(?:MB|GB)[^\]]*)\]""").find(header)
        return match?.groupValues?.getOrNull(1) ?: ""
    }

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

        Log.d("BingeCloud", "loadLinks received ${mirrors.size} mirrors")

        for (mirror in mirrors) {
            Log.d("BingeCloud", "Processing: ${mirror.mirror} ${mirror.quality} ${mirror.url}")
            try {
                when {
                    mirror.url.contains("vcloud", true) -> {
                        VCloud().getUrl(mirror.url, "", subtitleCallback, callback)
                    }
                    mirror.url.contains("vdrive", true) -> {
                        VDrive().getUrl(mirror.url, "", subtitleCallback, callback)
                    }
                    mirror.url.contains("gdirect", true) -> {
                        GDirect().getUrl(mirror.url, "", subtitleCallback, callback)
                    }
                    mirror.url.contains("filepress", true) ||
                    mirror.url.contains("gdflix", true) -> {
                        Filepress().getUrl(mirror.url, "", subtitleCallback, callback)
                    }
                    else -> {
                        loadExtractor(mirror.url, "", subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                Log.e("BingeCloud", "Mirror ${mirror.mirror} failed: ${e.message}")
            }
        }
        return true
    }
}
