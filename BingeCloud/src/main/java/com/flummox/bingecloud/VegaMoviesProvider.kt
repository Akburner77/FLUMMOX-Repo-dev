/*
 * FLUMMOX Repo — CloudStream 3 Extension Repository
 * Copyright (C) 2026 FlummoxGamer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

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

        var responseData: ResponseData? = null
if (imdbId.isNotEmpty()) {
    val jsonResponse = app.get("$cinemetaUrl/${if (isSeries) "series" else "movie"}/$imdbId.json").text
    responseData = tryParseJson<ResponseData>(jsonResponse)
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
    // Build season → mirrors map from VegaMovies page
    val seasonMirrorsMap: MutableMap<Int, MutableList<MirrorLink>> = mutableMapOf()
    val seasonHeaders = document.select("h3, h4, h5").filter {
        val t = it.text()
        (t.contains(Regex("""(?:Season|S)\s*\d+""", RegexOption.IGNORE_CASE)) ||
         t.contains("Complete", true)) && !t.contains("Zip", true)
    }

    for (header in seasonHeaders) {
        val headerText = header.text()
        val season = Regex("""(?:Season\s*|S)(\d+)""", RegexOption.IGNORE_CASE)
            .find(headerText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
        val quality = extractQualityFromHeader(headerText)
        val size = extractSizeFromHeader(headerText)

        val nextEl = header.nextElementSibling()
        val links = if (nextEl != null && nextEl.tagName() == "p") nextEl.select("a") else header.select("a")
        val dl = links.firstOrNull {
            it.text().contains("Download", true) || it.text().contains("V-Cloud", true)
        } ?: continue

        val mirrors = fetchMirrorsFromDownloadPage(dl.attr("href"), quality, size)
        seasonMirrorsMap.getOrPut(season) { mutableListOf() }.addAll(mirrors)
    }

    // Use real episode list from Cinemeta if available
    val episodeList = responseData?.meta?.videos ?: emptyList()
    val episodes = mutableListOf<Episode>()

    if (episodeList.isNotEmpty()) {
        for (ep in episodeList) {
            val seasonMirrors = seasonMirrorsMap[ep.season] ?: emptyList()
            if (seasonMirrors.isEmpty()) continue
            episodes.add(newEpisode(seasonMirrors) {
                this.name = ep.name ?: ep.title ?: "Episode ${ep.episode}"
                this.season = ep.season
                this.episode = ep.episode
                this.posterUrl = ep.thumbnail
                this.description = ep.overview
            })
        }
    } else {
        for ((season, mirrors) in seasonMirrorsMap) {
            episodes.add(newEpisode(mirrors) {
                this.name = "Season $season (Complete)"
                this.season = season
                this.episode = 1
            })
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
            val allMirrors = mutableListOf<MirrorLink>()

            val qualityHeaders = document.select("h3, h4, h5, .entry-title, .quality-title").filter {
                val txt = it.text()
                txt.contains(Regex("""\d{3,4}[pP]""")) && !txt.contains("Zip", true)
            }

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
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                addActors(cast)
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // MIRROR DETECTION — scans <a>, <button>, [onclick], [data-href]
    // ─────────────────────────────────────────────────────────
    private suspend fun fetchMirrorsFromDownloadPage(
        downloadUrl: String,
        quality: String,
        size: String = ""
    ): List<MirrorLink> {
        val mirrors = mutableListOf<MirrorLink>()
        try {
            val doc = app.get(fixUrl(downloadUrl)).document

            val candidates = doc.select("a, button, [onclick], [data-href]")

            for (elem in candidates) {
                var href = elem.attr("href").trim()
                if (href.isEmpty() || href == "#") {
                    href = elem.attr("data-href").trim()
                }
                if (href.isEmpty() || href == "#") {
                    val onclick = elem.attr("onclick")
                    if (onclick.isNotEmpty()) {
                        href = Regex("""['"](https?://[^'"]+)['"]""").find(onclick)
                            ?.groupValues?.get(1) ?: ""
                    }
                }
                if (href.isEmpty() || href.startsWith("#")) continue

                val text = elem.text().trim().lowercase()

                when {
                    text.contains("v-cloud") || text.contains("vcloud") -> {
                        if (mirrors.none { it.url == href })
                            mirrors.add(MirrorLink(quality, size, "V-Cloud", href))
                    }
                      
                    text.contains("download now") && href.startsWith("http") -> {
                        if (mirrors.none { it.url == href })
                            mirrors.add(MirrorLink(quality, size, "Direct", href))
                    }
                }
            }

            Log.d("BingeCloud", "Mirrors found on $downloadUrl: ${mirrors.size}")
            mirrors.forEach { Log.d("BingeCloud", "  ${it.mirror} | ${it.quality} | ${it.size} | ${it.url}") }
        } catch (e: Exception) {
            Log.e("BingeCloud", "fetchMirrors error: ${e.message}")
        }
        return mirrors
    }

    private fun extractQualityFromHeader(header: String): String =
        Regex("""(\d{3,4}[pP])""").find(header)?.value ?: "Unknown"

    private fun extractSizeFromHeader(header: String): String =
        Regex("""\[([^\]]*(?:MB|GB)[^\]]*)\]""").find(header)?.groupValues?.getOrNull(1) ?: ""

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

        Log.d("BingeCloud", "loadLinks: ${mirrors.size} mirrors")

        mirrors.amap { mirror ->
    try {
        when (mirror.mirror) {
            "V-Cloud" -> VCloud().getUrl(mirror.url, "", subtitleCallback, callback)
            "G-Direct" -> GDirect().getUrl(mirror.url, "", subtitleCallback, callback)
            "Filepress", "GDFlix" -> Filepress().getUrl(mirror.url, "", subtitleCallback, callback)
            else -> loadExtractor(mirror.url, "", subtitleCallback, callback)
        }
    } catch (e: Exception) {
        Log.e("BingeCloud", "${mirror.mirror} failed: ${e.message}")
    }
        }
        return true
    }
}
