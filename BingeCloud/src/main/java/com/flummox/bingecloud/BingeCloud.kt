package com.flummox.bingecloud

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class BingeCloud : MainAPI() {
    override var mainUrl = "https://vidsrc.to"
    override var name = "BingeCloud"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return newHomePageResponse("BingeCloud", emptyList())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return emptyList()
    }
}
