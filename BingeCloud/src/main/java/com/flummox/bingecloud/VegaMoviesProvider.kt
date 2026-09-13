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
import java.util.Calendar

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

data class AiometaCast(
    val name: String?, val character: String?, val photo: String?
)

data class AiometaMeta(
    val id: String?, val imdb_id: String?, val type: String?,
    val poster: String?, val background: String?,
    val name: String?, val description: String?,
    val genres: List<String>?, val imdbRating: String?,
    val releaseInfo: String?, val status: String?,
    val cast: List<AiometaCast>?, val videos: List<EpisodeDetails>?
)

data class AiometaResponse(val meta: AiometaMeta)
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
    val url: String,
    val season: Int = 0,
    val episode: Int = 0,
    val showName: String = ""
)

open class VegaMoviesProvider : MainAPI() {
    override var mainUrl = "https://vegamovies.mq"
    override var name = "BingeCloud"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val aiometaBase = "https://aiometadata.elfhosted.com/stremio/9197a4a9-2f5b-4911-845e-8704c520bdf7/meta"
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
        val title = cleanTitle(this.select("img").attr("alt"))
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
                cleanTitle(doc.post_title),
                doc.permalink,
                TvType.Movie
            ) { this.posterUrl = doc.post_thumbnail }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureDomain()
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val document = app.get(fullUrl).document
        var title = cleanTitle(document.select("title").text())
        var posterUrl = document.select("p > img").attr("src")
        val imdbUrl = document.select("a[href*=\"imdb\"]").attr("href")
        val imdbId = imdbUrl.substringAfter("title/").substringBefore("/")

        val isSeries = document.selectFirst("h3:matches((?i)Series-SYNOPSIS/PLOT)") != null ||
                document.selectFirst("h3:matches((?i)Series Info)") != null ||
                document.selectFirst("h3:matches((?i)Series synopsis/PLOT)") != null

        var description = document
            .selectFirst("h3:has(span:matches((?i)SYNOPSIS/PLOT))")
            ?.nextElementSibling()?.text() ?: ""

        var cast: List<Actor> = emptyList()
        var genre: List<String> = emptyList()
        var imdbRating = ""
        var year = ""
        var background = posterUrl
        var aiometa: AiometaMeta? = null

        if (imdbId.isNotEmpty()) {
            try {
                val metaUrl = "$aiometaBase/${if (isSeries) "series" else "movie"}/$imdbId.json"
                val jsonResponse = app.get(metaUrl).text
                val parsed = tryParseJson<AiometaResponse>(jsonResponse)
                aiometa = parsed?.meta
                if (aiometa != null) {
                    description = aiometa.description ?: description
                    title = aiometa.name ?: title
                    genre = aiometa.genres ?: emptyList()
                    imdbRating = aiometa.imdbRating ?: ""
                    year = aiometa.releaseInfo?.take(4) ?: ""
                    posterUrl = aiometa.poster ?: posterUrl
                    background = aiometa.background ?: background

                    val castList = mutableListOf<Actor>()
                    aiometa.cast?.forEach { c ->
                        if (!c.name.isNullOrBlank()) {
                            castList.add(
                                Actor(
                                    name = c.name!!,
                                    roleString = c.character,
                                    image = c.photo
                                )
                            )
                        }
                    }
                    cast = castList
                }
            } catch (e: Exception) {
                Log.e("BingeCloud", "Aiometa fetch failed: ${e.message}")
            }
        }

        return if (isSeries) {
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

            val episodeList = aiometa?.videos ?: emptyList()
            val episodes = mutableListOf<Episode>()

            if (episodeList.isNotEmpty()) {
                for (ep in episodeList) {
                    val seasonMirrors = seasonMirrorsMap[ep.season] ?: emptyList()
                    if (seasonMirrors.isEmpty()) continue
                    val tagged = seasonMirrors.map {
                        it.copy(season = ep.season, episode = ep.episode, showName = title)
                    }
                    episodes.add(newEpisode(tagged) {
                        this.name = ep.name ?: ep.title ?: "Episode ${ep.episode}"
                        this.season = ep.season
                        this.episode = ep.episode
                        this.posterUrl = when {
                            !ep.thumbnail.isNullOrBlank() && ep.thumbnail != "null" -> ep.thumbnail
                            background.isNotBlank() && background != "null" -> background
                            else -> posterUrl
                        }
                        this.description = ep.overview
                    })
                }
            } else {
                for ((season, mirrors) in seasonMirrorsMap) {
                    val tagged = mirrors.map { it.copy(season = season, episode = 1, showName = title) }
                    episodes.add(newEpisode(tagged) {
                        this.name = "Season $season (Complete)"
                        this.season = season
                        this.episode = 1
                        this.posterUrl = background
                    })
                }
            }

            val statusTag = buildStatusTag(aiometa?.status, year)
            val plotWithStatus = if (statusTag.isNotBlank())
                "<b>$statusTag</b><br><br>$description"
            else description

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plotWithStatus
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                if (cast.isNotEmpty()) addActors(cast)
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

            val taggedAll = allMirrors.map { it.copy(showName = title) }
            allMirrors.clear()
            allMirrors.addAll(taggedAll)

            val statusTag = buildStatusTag(aiometa?.status, year)
            val plotWithStatus = if (statusTag.isNotBlank())
                "<b>$statusTag</b><br><br>$description"
            else description

            newMovieLoadResponse(title, url, TvType.Movie, allMirrors) {
                this.posterUrl = posterUrl
                this.plot = plotWithStatus
                this.tags = genre
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.backgroundPosterUrl = background
                if (cast.isNotEmpty()) addActors(cast)
            }
        }
    }

    private fun buildStatusTag(status: String?, year: String): String {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val parsedYear = year.substringBefore("–").substringBefore("-").trim().toIntOrNull()
            ?: year.take(4).toIntOrNull()
        return when {
            status?.contains("Ended", true) == true -> "Completed"
            status?.contains("Returning", true) == true -> "Ongoing"
            status?.contains("Canceled", true) == true -> "Completed"
            parsedYear != null && parsedYear < currentYear - 1 -> "Completed"
            parsedYear != null && parsedYear >= currentYear - 1 -> "Ongoing"
            else -> ""
        }
    }

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
                if (href.isEmpty() || href == "#") href = elem.attr("data-href").trim()
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
                    text.contains("g-direct") || text.contains("gdirect") -> {
                        if (mirrors.none { it.url == href })
                            mirrors.add(MirrorLink(quality, size, "G-Direct", href))
                    }
                    text.contains("filepress") -> {
                        if (mirrors.none { it.url == href })
                            mirrors.add(MirrorLink(quality, size, "Filepress", href))
                    }
                    text.contains("download now") && href.startsWith("http") -> {
                        if (mirrors.none { it.url == href })
                            mirrors.add(MirrorLink(quality, size, "Direct", href))
                    }
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

        mirrors.amap { mirror ->
            try {
                when (mirror.mirror) {
                    "V-Cloud" -> VCloud("VM").getUrl(mirror.url, "", subtitleCallback, callback)
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

/**
 * Aggressively strips junk from raw titles scraped from VegaMovies.
 * Input:  "Avatar The Way of Water 2022 BluRay Hindi ORG DD 5.1 480p 720p 1080p 2160p 4K"
 * Output: "Avatar The Way of Water"
 */
fun cleanTitle(raw: String): String {
    var t = raw

    // Convert dotted filenames to spaced words
    t = t.replace(".", " ")
    t = t.replace("_", " ")

    // Strip square-bracket tags:  [1080p] [2.2GB] [Hindi]
    t = Regex("\\[[^\\]]*\\]").replace(t, " ")
    // Strip curly-brace tags:  {Hindi-Korean} {Dual Audio}
    t = Regex("\\{[^\\}]*\\}").replace(t, " ")
    // Strip parenthesised years only:  (2022)
    t = Regex("\\((?:19|20)\\d{2}\\)").replace(t, " ")

    // Strip common junk words (case-insensitive, word-boundary)
    val junk = listOf(
        "(?i)\\bdual\\s*audio\\b",
        "(?i)\\bmulti\\s*audio\\b",
        "(?i)\\bhindi\\b",
        "(?i)\\bkorean\\b",
        "(?i)\\benglish\\b",
        "(?i)\\btamil\\b",
        "(?i)\\btelugu\\b",
        "(?i)\\bmalayalam\\b",
        "(?i)\\bkannada\\b",
        "(?i)\\badded\\b",
        "(?i)\\bweb[\\s\\-]?dl\\b",
        "(?i)\\bweb[\\s\\-]?rip\\b",
        "(?i)\\bblu[\\s\\-]?ray\\b",
        "(?i)\\bbr[\\s\\-]?rip\\b",
        "(?i)\\bhdr[\\s\\-]?rip\\b",
        "(?i)\\bdvd[\\s\\-]?rip\\b",
        "(?i)\\bhdtv\\b",
        "(?i)\\bdweb\\b",
        "(?i)\\bpredvd\\b",
        "(?i)\\bx264\\b",
        "(?i)\\bx265\\b",
        "(?i)\\bh264\\b",
        "(?i)\\bh265\\b",
        "(?i)\\bhevc\\b",
        "(?i)\\bavc\\b",
        "(?i)\\baac\\b",
        "(?i)\\bddp\\b",
        "(?i)\\bdd[p5]?[\\d\\s\\.]*\\b",
        "(?i)\\bdts\\b",
        "(?i)\\b5\\.1\\b",
        "(?i)\\b7\\.1\\b",
        "(?i)\\b2\\.0\\b",
        "(?i)\\besubs?\\b",
        "(?i)\\bsubs?\\b",
        "(?i)\\bmkv\\b",
        "(?i)\\bmp4\\b",
        "(?i)\\bdownload\\b",
        "(?i)\\borg\\b",
        "(?i)\\bamzn\\b",
        "(?i)\\bnf\\b",
        "(?i)\\bimax\\b",
        "(?i)\\bprime\\b",
        "(?i)\\bhotstar\\b",
        "(?i)\\bdsnp\\b",
        "(?i)\\bcomplete\\b",
        "(?i)\\bseason\\b",
        "(?i)\\bfull\\s*movie\\b",
        "(?i)\\bmovie\\b",
        "(?i)\\bwatch\\s*online\\b",
        "(?i)\\bfree\\b",
        "(?i)\\bvegamovies\\b",
        "(?i)\\b\\d{3,4}[pP]\\b",
        "(?i)\\b(?:2160|1080|720|480|360|240)p?\\b",
        "(?i)\\b\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB)\\b",
        "(?i)\\b\\d+\\s*channel\\b"
    )
    for (p in junk) t = Regex(p).replace(t, " ")

    // Collapse whitespace
    t = Regex("\\s+").replace(t, " ").trim()

    // Trim leading/trailing separators
    t = t.trim('-', '|', ':', '·', '.', '_', ' ')

    return t.ifBlank { raw }
}
