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

data class MdMeta(
    val id: String?, val imdb_id: String?, val type: String?,
    val poster: String?, val background: String?,
    val name: String?, val description: String?,
    val genre: List<String>?, val imdbRating: String?,
    val year: String?, val status: String?, val videos: List<MdEpisodeDetails>?
)

data class MdEpisodeDetails(
    val name: String?, val title: String?,
    val season: Int?, val episode: Int?,
    val overview: String?, val thumbnail: String?
)

data class MdResponseData(val meta: MdMeta)
data class MdEpisodeLink(val source: String)
data class MdSearchHit(val document: MdSearchDoc)
data class MdSearchResponse(val hits: List<MdSearchHit>)
data class MdSearchDoc(
    val postTitle: String, val permalink: String, val postThumbnail: String
)

open class MoviesDriveProvider : MainAPI() {
    override var mainUrl = "https://moviesdrive.forum"
    override var name = "MoviesDrive"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime)

    private val aiometaUrl = "https://aiometadata.elfhosted.com/stremio/9197a4a9-2f5b-4911-845e-8704c520bdf7/meta"
    private var domainResolved = false

    private suspend fun ensureDomain() {
        if (domainResolved) return
        try {
            val res = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json")
            val live = JSONObject(res.text).optString("moviesdrive").trim()
            if (live.startsWith("http")) mainUrl = live
            domainResolved = true
        } catch (e: Exception) {
            Log.e("BingeCloud", "MoviesDrive domain resolution failed: ${e.message}")
        }
    }

    override val mainPage = mainPageOf(
        "/page/" to "Home",
        "/category/amzn-prime-video/page/" to "Prime Video",
        "/category/netflix/page/" to "Netflix",
        "/category/hotstar/page/" to "Hotstar",
        "/category/anime/page/" to "Anime",
        "/category/k-drama/page/" to "K Drama"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureDomain()
        val document = app.get("$mainUrl${request.data}$page").document
        val items = document.select("#moviesGridMain > a").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select("p").text().replace("Download ", "")
        if (title.isEmpty()) return null
        val href = this.attr("href")
        val poster = this.select("img").attr("src")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        ensureDomain()
        val text = app.get("$mainUrl/search.php?q=$query&page=1").text
        val response = tryParseJson<MdSearchResponse>(text) ?: return null
        return response.hits.map { hit ->
            newMovieSearchResponse(
                hit.document.postTitle.replace("Download ", ""),
                "$mainUrl${hit.document.permalink}",
                TvType.Movie
            ) { this.posterUrl = hit.document.postThumbnail }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureDomain()
        val document = app.get(url).document
        var title = document.select("title").text().replace("Download ", "")
        var posterUrl = document.select("main > p > img").attr("src")
        val imdbUrl = document.select("a[href*=\"imdb\"]").attr("href")
        val imdbId = imdbUrl.substringAfter("title/").substringBefore("/")

        val seasonRegex = """(?i)season\s*\d+""".toRegex()
        val isSeries = title.contains("Episode", true) ||
                seasonRegex.containsMatchIn(title) ||
                title.contains("series", true)

        var description = ""
        var cast: List<Actor> = emptyList()
        var genre: List<String> = emptyList()
        var imdbRating = ""
        var year = ""
        var background = posterUrl
        var metaStatus: String? = null

        if (imdbId.isNotEmpty()) {
            val jsonRes = app.get("$aiometaUrl/${if (isSeries) "series" else "movie"}/$imdbId.json").text
            val responseData = tryParseJson<MdResponseData>(jsonRes)
            responseData?.meta?.let { meta ->
                description = meta.description ?: description
                title = meta.name ?: title
                genre = meta.genre ?: emptyList()
                imdbRating = meta.imdbRating ?: ""
                year = meta.year ?: ""
                posterUrl = meta.poster ?: posterUrl
                background = meta.background ?: background
                metaStatus = meta.status
            }
            
        }

        return if (isSeries) {
                loadSeries(document, title, url, posterUrl, description, cast, genre, imdbRating, year, background, imdbUrl, metaStatus)
            } else {
                loadMovie(document, title, url, posterUrl, description, cast, genre, imdbRating, year, background, imdbUrl, metaStatus)
        }
    }

    private suspend fun loadMovie(
        document: org.jsoup.nodes.Document, title: String, url: String,
        posterUrl: String, description: String, cast: List<Actor>,
        genre: List<String>, imdbRating: String, year: String,
        background: String, imdbUrl: String, metaStatus: String?
    ): LoadResponse? {
        val buttons = document.select("h5 > a")
        val sources = mutableListOf<MdEpisodeLink>()
        for (button in buttons) {
            val link = button.attr("href")
            val doc = app.get(link).document
            val inner = doc.select("a").filter {
                it.attr("href").contains(Regex("hubcloud|gdflix|gdlink", RegexOption.IGNORE_CASE))
            }
            inner.forEach { sources.add(MdEpisodeLink(it.attr("href"))) }
        }
        val statusTag = when {
    metaStatus?.contains("Released", true) == true -> "Completed"
    metaStatus?.contains("Returning", true) == true -> "Ongoing"
    else -> ""
}
val tagsWithStatus = if (statusTag.isNotBlank()) genre + statusTag else genre

     return newMovieLoadResponse(title, url, TvType.Movie, sources) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tagsWithStatus
            this.score = Score.from10(imdbRating)
            this.year = year.toIntOrNull()
            this.backgroundPosterUrl = background
            addActors(cast)
            if (imdbUrl.isNotEmpty()) addImdbUrl(imdbUrl)
        }
    }

    private suspend fun loadSeries(
        document: org.jsoup.nodes.Document, title: String, url: String,
        posterUrl: String, description: String, cast: List<Actor>,
        genre: List<String>, imdbRating: String, year: String,
        background: String, imdbUrl: String, metaStatus: String?
    ): LoadResponse? {
        val episodesMap: MutableMap<Pair<Int, Int>, MutableList<String>> = mutableMapOf()
        val buttons = document.select("h5 > a").filter { !it.text().contains("Zip", true) }

        for (button in buttons) {
            val titleEl = button.parent()?.previousElementSibling()
            val mainTitle = titleEl?.text() ?: ""
            val season = Regex("""(?:Season |S)(\d+)""").find(mainTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val episodeLink = button.attr("href")
            val doc = app.get(episodeLink).document

            var elements = doc.select("span:matches((?i)(Ep))")
            if (elements.isEmpty()) elements = doc.select("a:matches((?i)(HubCloud|GDFlix))")

            var epNum = 1
            for (el in elements) {
                if (el.tagName() == "span") {
                    val titleTag = el.parent()
                    var hTag = titleTag?.nextElementSibling()
                    epNum = Regex("""Ep(\d{2})""").find(el.toString())?.groupValues?.get(1)?.toIntOrNull() ?: epNum
                    while (hTag != null && (
                        hTag.text().contains("HubCloud", true) ||
                        hTag.text().contains("gdflix", true) ||
                        hTag.text().contains("gdlink", true)
                    )) {
                        val aTag = hTag.selectFirst("a")
                        val epUrl = aTag?.attr("href").orEmpty()
                        if (epUrl.isNotEmpty()) {
                            episodesMap.getOrPut(Pair(season, epNum)) { mutableListOf() }.add(epUrl)
                        }
                        hTag = hTag.nextElementSibling()
                    }
                    epNum++
                } else {
                    val epUrl = el.attr("href")
                    if (epUrl.isNotEmpty()) {
                        episodesMap.getOrPut(Pair(season, epNum)) { mutableListOf() }.add(epUrl)
                    }
                    epNum++
                }
            }
        }

        val episodes = episodesMap.map { (key, urls) ->
            newEpisode(urls.map { MdEpisodeLink(it) }) {
                this.name = "S${key.first} E${key.second}"
                this.season = key.first
                this.episode = key.second
            }
        }

        val statusTag = when {
             metaStatus?.contains("Ended", true) == true -> "Completed"
             metaStatus?.contains("Returning", true) == true -> "Ongoing"
        else -> ""
     }
     val tagsWithStatus = if (statusTag.isNotBlank()) genre + statusTag else genre

     return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tagsWithStatus
            this.score = Score.from10(imdbRating)
            this.year = year.toIntOrNull()
            this.backgroundPosterUrl = background
            addActors(cast)
            if (imdbUrl.isNotEmpty()) addImdbUrl(imdbUrl)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = try {
            parseJson<List<MdEpisodeLink>>(data)
        } catch (e: Exception) {
            return false
        }
        sources.amap {
            val src = it.source
            when {
                src.contains("gdflix", true) || src.contains("gdlink", true) ->
                    loadExtractor(src, "", subtitleCallback, callback)
                src.contains("hubcloud", true) || src.contains("vcloud", true) ->
                VCloud("MD").getUrl(src, "", subtitleCallback, callback)
                else -> loadExtractor(src, "", subtitleCallback, callback)
            }
        }
        return true
    }
}
