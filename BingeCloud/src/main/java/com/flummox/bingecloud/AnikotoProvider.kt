package com.flummox.bingecloud

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// ═══════════════════════════════════════════════════════════════
// ── AniKoto provider ──
// Search HTML + AJAX episode/server endpoints + megaplay extractor
// ═══════════════════════════════════════════════════════════════

class AnikotoProvider : MainAPI() {
    override var mainUrl = "https://anikototv.to"
    override var name = "AniKoto"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updated" to "Latest Updated",
        "$mainUrl/most-viewed" to "Most Popular",
        "$mainUrl/status/currently-airing" to "Ongoing",
        "$mainUrl/type/movie" to "Movies"
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    private fun ajaxHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to browserHeaders["User-Agent"]!!,
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Referer" to referer
    )

    // ── home ──
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get("${request.data}?page=$page", headers = browserHeaders).document
        val items = doc.select("div.ani.items > div.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ── search ──
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/filter?keyword=$query", headers = browserHeaders).document
        return doc.select("div.ani.items > div.item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val titleEl = selectFirst("a.name.d-title")
            ?: selectFirst("a[title]")
            ?: selectFirst("a[href*='/watch/']") ?: return null
        var href = titleEl.attr("href")
        if (href.isBlank()) {
            href = selectFirst("div.poster a, a")?.attr("href") ?: ""
        }
        val title = titleEl.text().trim().ifBlank { titleEl.attr("title").trim() }
        if (href.isBlank() || title.isBlank()) return null

        val img = select("div.poster img, img")
        val poster = img.attr("data-src").ifBlank { img.attr("src") }

        val typeEl = selectFirst(".type, .right")
        val isMovie = typeEl?.text()?.contains("Movie", true) == true
        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

        val metaText = select(".meta, .info, .type, .right").text()
        val hasDub = selectFirst(".dub, i.dub, .fa-microphone") != null ||
                metaText.contains("Dub", true)
        val hasSub = selectFirst(".sub, i.sub, .fa-closed-captioning") != null ||
                metaText.contains("Sub", true) || hasDub

        val subCount = selectFirst("span.ep-status.sub span")?.text()?.trim()?.toIntOrNull()
        val dubCount = selectFirst("span.ep-status.dub span")?.text()?.trim()?.toIntOrNull()

        return newAnimeSearchResponse(title, fixUrl(href), type) {
            this.posterUrl = fixUrl(poster)
            addDubStatus(hasDub, hasSub, dubCount, subCount)
        }
    }

    // ── load ──
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = browserHeaders).document

        val titleEl = doc.selectFirst("#w-info h1.title, h1[itemprop=name], .title[itemprop=name]")
            ?: doc.selectFirst("h1.title") ?: return null
        val title = titleEl.text().trim()
        if (title.isBlank()) return null

        val posterEl = doc.select("#w-info .poster img, img[itemprop=image], .poster img")
        val poster = posterEl.attr("data-src").ifBlank { posterEl.attr("src") }

        val description = doc.selectFirst("#w-info .synopsis .content, #w-info .synopsis, .synopsis .content")?.text()
        val genres = doc.select("#w-info a[href*='/genre/'], .meta a[href*='/genre/']")
            .map { it.text().trim() }

        val isMovie = doc.selectFirst("#w-info a[href*='/type/movie']") != null ||
                doc.selectFirst(".bmeta")?.text()?.contains("Movie", true) == true

        val animeId = doc.selectFirst("#watch-main")?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("[data-id]")?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: Regex("""data-id=["'](\d+)["']""").find(doc.html())?.groupValues?.get(1)

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        var animeMeta: AnikotoMetaAnimeData? = null

        if (animeId != null) {
            val epListJson = try {
                val resp = app.get("$mainUrl/ajax/episode/list/$animeId", headers = ajaxHeaders(url)).text
                jsonResultString(resp)
            } catch (e: Exception) {
                BCLog.d("AniKoto ep list failed: ${e.message}"); ""
            }

            if (epListJson.isNotBlank()) {
                val epDoc = Jsoup.parse(epListJson)
                val malId = epDoc.selectFirst("a[data-mal]")?.attr("data-mal")?.toIntOrNull()
                if (malId != null) {
                    try {
                        val metaJson = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
                        animeMeta = parseAnikotoAnimeData(metaJson)
                    } catch (_: Exception) {}
                }

                for (el in epDoc.select("a[data-ids]")) {
                    val serverIds = el.attr("data-ids").takeIf { it.isNotBlank() } ?: continue
                    val episodeNum = el.attr("data-num").toIntOrNull() ?: continue
                    val hasSub = el.attr("data-sub") == "1"
                    val hasDub = el.attr("data-dub") == "1"

                    val meta = animeMeta?.episodes?.get(episodeNum.toString())
                    val episodeName = meta?.title?.get("en")
                        ?: meta?.title?.get("x-jat")
                        ?: meta?.title?.get("ja")
                        ?: el.selectFirst(".d-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: el.parent()?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
                        ?: "Episode $episodeNum"

                    val epThumb = meta?.image
                    val epDesc = meta?.overview
                    val epRuntime = meta?.runtime
                    val epAirDate = meta?.airDateUtc

                    if (hasSub || !hasDub) {
                        subEpisodes.add(newEpisode("anikoto|$url|$serverIds|sub") {
                            this.episode = episodeNum
                            this.name = episodeName
                            this.posterUrl = epThumb
                            this.description = epDesc
                            this.runTime = epRuntime
                            addDate(epAirDate)
                        })
                    }
                    if (hasDub) {
                        dubEpisodes.add(newEpisode("anikoto|$url|$serverIds|dub") {
                            this.episode = episodeNum
                            this.name = episodeName
                            this.posterUrl = epThumb
                            this.description = epDesc
                            this.runTime = epRuntime
                            addDate(epAirDate)
                        })
                    }
                }

                if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) {
                    doc.select("a[href*='/ep-']").forEachIndexed { idx, el ->
                        subEpisodes.add(newEpisode(fixUrl(el.attr("href"))) {
                            this.episode = idx + 1
                            this.name = el.text().trim().ifBlank { "Episode ${idx + 1}" }
                        })
                    }
                }
            }
        }

        val backgroundPoster = animeMeta?.images
            ?.firstOrNull { it.coverType?.equals("Fanart", true) == true }
            ?.url

        val tvType = when {
            isMovie || dubEpisodes.isEmpty() && subEpisodes.isEmpty() -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        val allEpisodes = subEpisodes + dubEpisodes

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backgroundPoster
            this.plot = description
            this.tags = genres
            addEpisodes(DubStatus.Both, allEpisodes)
        }
    }

    private fun jsonResultString(json: String): String {
        return try {
            val root = JSONObject(json)
            if (root.optInt("status") == 200) root.optString("result") else ""
        } catch (_: Exception) { "" }
    }

    // ── loadLinks ──
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = data.removePrefix("$mainUrl/").removePrefix("/")
        if (clean.startsWith("anikoto|")) {
            val parts = clean.split("|")
            if (parts.size < 4) return false
            val referer = parts[1]
            val serverIds = parts[2].takeIf { it.isNotBlank() } ?: return false
            val audioType = parts[3].ifBlank { "sub" }
            return resolveServers(serverIds, referer, audioType, subtitleCallback, callback)
        }

        val doc = try { app.get(clean, headers = browserHeaders).document } catch (_: Exception) { return false }
        val animeId = doc.selectFirst("#watch-main")?.attr("data-id")
            ?: doc.selectFirst("[data-id]")?.attr("data-id")
            ?: Regex("""data-id=["'](\d+)["']""").find(doc.html())?.groupValues?.get(1)
            ?: return false
        val epNum = Regex("/ep-(\\d+)").find(clean)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val listJson = try {
            val resp = app.get("$mainUrl/ajax/episode/list/$animeId", headers = ajaxHeaders(clean)).text
            jsonResultString(resp)
        } catch (_: Exception) { return false }
        if (listJson.isBlank()) return false
        val listDoc = Jsoup.parse(listJson)
        val epEl = listDoc.select("a[data-ids]")
            .firstOrNull { it.attr("data-num").toIntOrNull() == epNum }
            ?: listDoc.selectFirst("a[data-ids]") ?: return false
        val serverIds = epEl.attr("data-ids").takeIf { it.isNotBlank() } ?: return false
        val audioType = if (epEl.attr("data-dub") == "1") "dub" else "sub"
        return resolveServers(serverIds, clean, audioType, subtitleCallback, callback)
    }

    private suspend fun resolveServers(
        serverIds: String,
        referer: String,
        audioType: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val serverListJson = try {
            val resp = app.get("$mainUrl/ajax/server/list?servers=$serverIds",
                headers = ajaxHeaders(referer)).text
            jsonResultString(resp)
        } catch (e: Exception) {
            BCLog.d("AniKoto server list failed: ${e.message}"); return false
        }
        if (serverListJson.isBlank()) return false

        val serverDoc = Jsoup.parse(serverListJson)
        val typeSelectors = if (audioType == "dub")
            listOf("div.type[data-type=dub]")
        else
            listOf("div.type[data-type=sub]", "div.type[data-type=hsub]")

        val entries = mutableListOf<AnikotoServerEntry>()
        for (sel in typeSelectors) {
            val sType = Regex("data-type=(\\w+)").find(sel)?.groupValues?.get(1) ?: audioType
            serverDoc.select("$sel li[data-link-id]").forEach { li ->
                val linkId = li.attr("data-link-id")
                val name = li.text().trim().ifBlank { "Server" }
                entries.add(AnikotoServerEntry(linkId, name, sType))
            }
        }
        if (entries.isEmpty()) {
            serverDoc.select("li[data-link-id]").forEach { li ->
                val linkId = li.attr("data-link-id")
                val name = li.text().trim().ifBlank { "Server" }
                entries.add(AnikotoServerEntry(linkId, name, audioType))
            }
        }

        val filtered = entries.filter { it.linkId.isNotBlank() }
        if (filtered.isEmpty()) return false

        val results = coroutineScope {
            filtered.map { entry ->
                async { resolveEmbed(entry, referer, subtitleCallback, callback) }
            }.awaitAll()
        }
        return results.any { it }
    }

    private suspend fun resolveEmbed(
        entry: AnikotoServerEntry,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkJson = try {
            val resp = app.get("$mainUrl/ajax/server/${entry.linkId}", headers = ajaxHeaders(referer)).text
            jsonResultUrl(resp)
        } catch (_: Exception) { null } ?: return false

        val normalizedUrl = when {
            linkJson.startsWith("//") -> "https:$linkJson"
            linkJson.startsWith("/") -> "$mainUrl$linkJson"
            else -> linkJson
        }

        anikotoGetHashM3u8(normalizedUrl)?.let { m3u8 ->
            val typeLabel = anikotoServerTypeLabel(entry.serverType)
            val link = newExtractorLink(
                source = "AniKoto",
                name = "${entry.serverName} [$typeLabel]",
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
            }
            callback(link)
            return true
        }

        val domain = Regex("https?://([^/]+)").find(normalizedUrl)?.groupValues?.get(1) ?: ""
        val typeLabel = anikotoServerTypeLabel(entry.serverType)
        val label = "${entry.serverName} [$typeLabel]"

        return when {
            domain.contains("megaplay", true) ->
                runCatching {
                    anikotoExtractMegaPlayUrl(normalizedUrl, referer, "https://$domain", label, subtitleCallback, callback)
                    true
                }.getOrDefault(false)
            domain.contains("vidwish", true) ->
                runCatching {
                    anikotoExtractMegaPlayUrl(normalizedUrl, referer, "https://$domain", label, subtitleCallback, callback)
                    true
                }.getOrDefault(false)
            domain.contains("vidtube", true) ->
                runCatching {
                    anikotoExtractMegaPlayUrl(normalizedUrl, referer, "https://$domain", label, subtitleCallback, callback)
                    true
                }.getOrDefault(false)
            else -> try {
                loadExtractor(normalizedUrl, referer, subtitleCallback, callback)
                true
            } catch (_: Exception) { false }
        }
    }

    private fun jsonResultUrl(json: String): String? {
        return try {
            val root = JSONObject(json)
            if (root.optInt("status") == 200)
                root.optJSONObject("result")?.optString("url")?.takeIf { it.isNotBlank() }
            else null
        } catch (_: Exception) { null }
    }
}
