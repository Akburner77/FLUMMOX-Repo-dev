package com.flummox.bingecloud

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

// ── AniKoto data models ──

data class AnikotoServerEntry(
    val linkId: String,
    val serverName: String,
    val serverType: String
)

data class AnikotoImageData(
    val coverType: String? = null,
    val url: String? = null
)

data class AnikotoMetaEpisode(
    val title: Map<String, String>? = null,
    val image: String? = null,
    val overview: String? = null,
    val rating: String? = null,
    val runtime: Int? = null,
    val airDateUtc: String? = null
)

data class AnikotoMetaAnimeData(
    val episodes: Map<String, AnikotoMetaEpisode>? = null,
    val images: List<AnikotoImageData>? = null
)

private val anikotoMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

fun parseAnikotoAnimeData(json: String?): AnikotoMetaAnimeData? {
    if (json.isNullOrBlank()) return null
    return try {
        anikotoMapper.readValue(json, AnikotoMetaAnimeData::class.java)
    } catch (e: Exception) {
        BCLog.d("AniKoto meta parse failed: ${e.message}"); null
    }
}
