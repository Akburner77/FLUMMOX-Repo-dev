package com.flummox.bingecloud

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class GogoAnimeProvider : MainAPI() {
    override var mainUrl = "https://gogoanime.by"
    override var name = "GogoAnime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "latest-episodes" to "Latest Episodes",
        "popular" to "Popular",
        "ongoing" to "Ongoing Series",
        "completed" to "Completed Series",
    )

    // ── home ──
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path = if (request.data == "latest-episodes")
            "$mainUrl/latest-episodes/page/$page/"
        else
            "$mainUrl/${request.data}/page/$page/"
        val doc = app.get(path).document
        val items = doc.select("ul.items li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("p.name a") ?: return null
        val title = a.attr("title").ifBlank { a.text() }.trim()
        val href = a.attr("href").trim()
        if (title.isBlank() || href.isBlank()) return null
        val poster = selectFirst("div.img img")?.attr("src")
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // ── search ──
    override suspend fun search(query: String): List<SearchResponse>? {
        val doc = app.get("$mainUrl/search.html", params = mapOf("keyword" to query)).document
        return doc.select("ul.items li").mapNotNull { it.toSearchResult() }
    }

    // ── detail ──
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("div.anime_info img")?.attr("src")
        val desc = doc.selectFirst("div.anime_info p")?.text()?.trim()
        val genres = doc.select("div.anime_info p:contains(Genre) a").map { it.text().trim() }

        // hidden input carries the numeric anime id
        val animeId = doc.selectFirst("input#movie_id")?.attr("value")
            ?: doc.selectFirst("input[name=id]")?.attr("value")
            ?: ""

        // episode range from the active tab
        val activeTab = doc.selectFirst("#episode_page li a.active")
        val epStart = activeTab?.attr("ep_start") ?: "0"
        val epEnd = activeTab?.attr("ep_end") ?: "0"

        val episodes: List<Episode> = if (animeId.isNotBlank()) {
            try {
                val epDoc = app.get(
                    "$mainUrl/ajax/load-list-episode",
                    params = mapOf("ep_start" to epStart, "ep_end" to epEnd, "id" to animeId)
                ).document
                epDoc.select("li a").mapNotNull { a ->
                    val href = a.attr("href").trim()
                    if (href.isBlank()) return@mapNotNull null
                    val epNum = a.selectFirst("div.play span")?.text()
                        ?.removePrefix("Episode")?.trim()?.toIntOrNull()
                    newEpisode(href) {
                        this.name = if (epNum != null) "Episode $epNum" else a.text().trim()
                        this.episode = epNum
                    }
                }.reversed()
            } catch (e: Exception) {
                BCLog.e("Gogo episodes failed: ${e.message}")
                emptyList()
            }
        } else emptyList()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = desc
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ── playback ──
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val iframe = doc.selectFirst("div.play-video iframe")?.attr("src")
            ?: doc.selectFirst("iframe")?.attr("src")
            ?: run { BCLog.e("Gogo: no iframe on $data"); return false }

        val finalUrl = when {
            iframe.startsWith("//") -> "https:$iframe"
            iframe.startsWith("/") -> mainUrl + iframe
            else -> iframe
        }
        BCLog.d("Gogo iframe: $finalUrl")

        try {
            GogoCdn().getUrl(finalUrl, data, subtitleCallback, callback)
        } catch (e: Exception) {
            BCLog.e("GogoCdn failed: ${e.message}")
            return false
        }
        return true
    }
}
