package com.flummox.bingecloud

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
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
    val imdbRating: String?, val year: String?
)

data class ResponseData(val meta: Meta)
data class VegaSearchResponse(val hits: List<VegaHit>)
data class VegaHit(val document: VegaDocument)
data class VegaDocument(
    val id: String, val imdb_id: String?, val post_title: String,
    val permalink: String, val post_thumbnail: String
)

open class VegaMoviesProvider : MainAPI() {
    override var mainUrl = "https://vegamovies.mq"
    override var name = "BingeCloud"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cinemetaUrl = "https://v3-cinemeta.strem.io/meta"

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
        "$mainUrl/category/web-series/netflix/page/%d/" to "Netflix",
        "$mainUrl/category/web-series/disney-plus-hotstar/page/%d/" to "Disney+ Hotstar",
        "$mainUrl/category/web-series/amazon-prime-video/page/%d/" to "Amazon Prime",
        "$mainUrl/category/anime-series/page/%d/" to "Anime",
        "$mainUrl/category/korean-series/page/%d/" to "Korean"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data.format(page)).document
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
            val seasonHeaders = document.select("main > h3, main > h5")

            for (header in seasonHeaders) {
                val headerText = header.text()
                if (headerText.contains("Zip", true)) continue

                val seasonMatch = Regex("""(?:Season\s*|S)(\d+)""", RegexOption.IGNORE_CASE)
                    .find(headerText)
                val season = seasonMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

                val nextEl = header.nextElementSibling()
                val links = if (nextEl != null && nextEl.tagName() == "p") {
                    nextEl.select("a")
                } else {
                    header.select("a")
                }

                links.firstOrNull { it.text().contains("V-Cloud", true) }?.let { link ->
                    val vcloudUrl = link.attr("href")
                    episodes.add(
                        newEpisode(vcloudUrl) {
                            this.name = "Season $season"
                            this.season = season
                            this.episode = 1
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
            }
        } else {
            val data = document.selectFirst("a:contains(V-Cloud)")?.attr("href") ?: ""
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
                if (imdbId.isNotEmpty()) addImdbUrl(imdbId)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("vcloud", ignoreCase = true)) {
            VCloud().getUrl(data, "", subtitleCallback, callback)
        } else if (data.isNotEmpty()) {
            loadExtractor(data, "", subtitleCallback, callback)
        }
        return true
    }
}
