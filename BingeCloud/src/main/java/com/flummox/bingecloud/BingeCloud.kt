package com.flummox.bingecloud

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class BingeCloud : MainAPI() {
    override var mainUrl = "https://vidsrc.to"
    override var name = "BingeCloud"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey get() = BuildConfig.TMDB_API_KEY
    private val tmdbImg = "https://image.tmdb.org/t/p/w500"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val res = app.get("$tmdbApi/trending/all/week?api_key=$tmdbKey&page=$page").text
        val results = JSONObject(res).getJSONArray("results")
        val items = (0 until results.length()).mapNotNull { i ->
            parseTmdbItem(results.getJSONObject(i))
        }
        return newHomePageResponse(listOf(HomePageList("Trending", items)), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val res = app.get("$tmdbApi/search/multi?api_key=$tmdbKey&query=$query").text
        val results = JSONObject(res).getJSONArray("results")
        return (0 until results.length()).mapNotNull { i ->
            parseTmdbItem(results.getJSONObject(i))
        }
    }

    private fun parseTmdbItem(o: JSONObject): SearchResponse? {
        val mediaType = o.optString("media_type")
        val tvType = when (mediaType) {
            "movie" -> TvType.Movie
            "tv" -> TvType.TvSeries
            else -> return null
        }
        val id = o.optInt("id", -1)
        if (id == -1) return null
        val title = o.optString("title").ifEmpty { o.optString("name") }
        if (title.isEmpty()) return null
        val posterPath = o.optString("poster_path")
        val poster = if (posterPath.isNotEmpty() && posterPath != "null") "$tmdbImg$posterPath" else null
        val dateStr = o.optString("release_date").ifEmpty { o.optString("first_air_date") }
        val year = dateStr.take(4).toIntOrNull()

        return newMovieSearchResponse(title, "$mediaType/$id", tvType) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("/")
        if (parts.size < 2) return null
        val mediaType = parts[0]
        val id = parts[1]

        val res = app.get("$tmdbApi/$mediaType/$id?api_key=$tmdbKey").text
        val o = JSONObject(res)
        val title = o.optString("title").ifEmpty { o.optString("name") }
        val posterPath = o.optString("poster_path")
        val poster = if (posterPath.isNotEmpty() && posterPath != "null") "$tmdbImg$posterPath" else null
        val plot = o.optString("overview")
        val dateStr = o.optString("release_date").ifEmpty { o.optString("first_air_date") }
        val year = dateStr.take(4).toIntOrNull()

        return if (mediaType == "movie") {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            val seasonsArray = o.optJSONArray("seasons") ?: return null
            val episodes = mutableListOf<Episode>()
            for (i in 0 until seasonsArray.length()) {
                val season = seasonsArray.getJSONObject(i)
                val seasonNumber = season.optInt("season_number", -1)
                if (seasonNumber < 0) continue
                val seasonRes = app.get("$tmdbApi/tv/$id/season/$seasonNumber?api_key=$tmdbKey").text
                val seasonObj = JSONObject(seasonRes)
                val epsArray = seasonObj.optJSONArray("episodes") ?: continue
                for (j in 0 until epsArray.length()) {
                    val ep = epsArray.getJSONObject(j)
                    val epNum = ep.optInt("episode_number", -1)
                    val epName = ep.optString("name")
                    if (epNum < 0) continue
                    episodes.add(
                        newEpisode("$mediaType/$id/$seasonNumber/$epNum") {
                            this.name = epName
                            this.season = seasonNumber
                            this.episode = epNum
                        }
                    )
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }
}
