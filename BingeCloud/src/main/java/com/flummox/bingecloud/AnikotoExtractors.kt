package com.flummox.bingecloud

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ═══════════════════════════════════════════════════════════════
// ── AniKoto extractors (megaplay / vidtube / vidwish) ──
// Decrypts AES-encrypted sources, signs MegaPlay tokens, proxies
// dead CDN hosts to live ones, resolves m3u8 + subtitles.
// ═══════════════════════════════════════════════════════════════

private const val MEGAPLAY_ENC_IV = "W0;27ToaUpl_P%'c"
private const val MEGAPLAY_ENC_KEY = "i?LMTAx0Q6,:}50U"
private const val MEGAPLAY_TOKEN_SECRET = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"

private val ANIKOTO_PROXY_MAP: Map<String, String> = mapOf(
    "vibeplayer.site" to "nanobyte.bigdreamsmalldih.site",
    "vault-01.uwucdn.top" to "uwu1.bigdreamsmalldih.site",
    "vault-02.uwucdn.top" to "uwu2.bigdreamsmalldih.site",
    "vault-03.uwucdn.top" to "uwu3.bigdreamsmalldih.site",
    "vault-04.uwucdn.top" to "uwu4.bigdreamsmalldih.site",
    "vault-05.uwucdn.top" to "uwu5.bigdreamsmalldih.site",
    "vault-06.uwucdn.top" to "uwu6.bigdreamsmalldih.site",
    "vault-07.uwucdn.top" to "uwu7.bigdreamsmalldih.site",
    "vault-08.uwucdn.top" to "uwu8.bigdreamsmalldih.site",
    "vault-09.uwucdn.top" to "uwu9.bigdreamsmalldih.site",
    "vault-10.uwucdn.top" to "uwu10.bigdreamsmalldih.site",
    "vault-11.uwucdn.top" to "uwu11.bigdreamsmalldih.site",
    "vault-12.uwucdn.top" to "uwu12.bigdreamsmalldih.site",
    "vault-13.uwucdn.top" to "uwu13.bigdreamsmalldih.site",
    "vault-14.uwucdn.top" to "uwu14.bigdreamsmalldih.site",
    "vault-15.uwucdn.top" to "uwu15.bigdreamsmalldih.site",
    "vault-16.uwucdn.top" to "uwu16.bigdreamsmalldih.site",
    "vault-99.uwucdn.top" to "uwu17.bigdreamsmalldih.site",
    "vault-10.owocdn.top" to "10.bigdreamsmalldih.site",
    "vault-11.owocdn.top" to "11.bigdreamsmalldih.site",
    "vault-12.owocdn.top" to "12.bigdreamsmalldih.site",
    "vault-13.owocdn.top" to "13.bigdreamsmalldih.site",
    "vault-14.owocdn.top" to "14.bigdreamsmalldih.site",
    "vault-15.owocdn.top" to "15.bigdreamsmalldih.site",
    "vault-16.owocdn.top" to "16.bigdreamsmalldih.site",
    "vault-99.owocdn.top" to "99.bigdreamsmalldih.site"
)

fun anikotoProxyPlayerHost(url: String): String {
    var cur = url
    for ((old, new) in ANIKOTO_PROXY_MAP) cur = cur.replace(old, new)
    return cur
}

fun anikotoGetHashM3u8(url: String): String? {
    val encoded = url.substringAfter("#", "").substringBefore("#").takeIf { it.isNotBlank() } ?: return null
    val decoded = try { String(Base64.decode(encoded, Base64.DEFAULT)) } catch (_: Exception) { return null }
    val proxied = anikotoProxyPlayerHost(decoded)
    return if (proxied.startsWith("http") && proxied.contains(".m3u8")) proxied else null
}

fun anikotoServerTypeLabel(type: String): String = when (type.lowercase(Locale.ROOT)) {
    "hsub" -> "HSub"
    "dub" -> "Dub"
    else -> "Sub"
}

private fun b64UrlNoPad(bytes: ByteArray): String =
    Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

fun anikotoSignMegaPlayUrl(url: String): String {
    if (url.contains("token=")) return url
    val m = Regex("/([a-f0-9]{32})/([a-f0-9]{32})/", RegexOption.IGNORE_CASE).find(url) ?: return url
    val payload = "${(System.currentTimeMillis() / 1000) + 90}|${m.groupValues[1]}/${m.groupValues[2]}"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(MEGAPLAY_TOKEN_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val sig = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
    val token = "${b64UrlNoPad(payload.toByteArray(Charsets.UTF_8))}.${b64UrlNoPad(sig)}"
    val sep = if (url.contains("?")) "&" else "?"
    return "$url$sep" + "token=$token"
}

private fun anikotoDecryptMegaPlaySources(enc: String): String? {
    return try {
        val normalized = enc.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val data = Base64.decode(padded, Base64.DEFAULT)
        val keyBytes = MEGAPLAY_ENC_KEY.toByteArray(Charsets.UTF_8)
        val key = keyBytes + ByteArray(32 - keyBytes.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(MEGAPLAY_ENC_IV.toByteArray(Charsets.UTF_8))
        )
        val json = String(cipher.doFinal(data), Charsets.UTF_8).trim()
        when {
            json.startsWith("{") -> JSONObject(json).optString("file").takeIf { it.isNotBlank() }
            json.startsWith("[") -> {
                val arr = JSONArray(json)
                if (arr.length() > 0)
                    arr.optJSONObject(0)?.optString("file")?.takeIf { it.isNotBlank() }
                else null
            }
            else -> null
        }
    } catch (e: Exception) {
        BCLog.e("AniKoto decrypt failed: ${e.message}"); null
    }
}

suspend fun anikotoExtractMegaPlayUrl(
    url: String,
    referer: String?,
    host: String,
    label: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val type = if (url.contains("/dub", ignoreCase = true)) "dub" else "sub"

    val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    val pageHeaders = mapOf(
        "User-Agent" to ua,
        "Referer" to (referer ?: "https://anikototv.to/")
    )
    val ajaxHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to host,
        "Referer" to url
    )
    val playbackHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "Origin" to host,
        "Referer" to "$host/"
    )

    val doc = try {
        app.get(url, headers = pageHeaders).document
    } catch (e: Exception) {
        BCLog.e("AniKoto player page failed: ${e.message}"); return
    }

    val playerEl = doc.selectFirst("#megaplay-player")
    val streamId = run {
        playerEl?.attr("data-id")?.takeIf { it.isNotBlank() }?.let { return@run it }
        playerEl?.attr("data-realid")?.takeIf { it.isNotBlank() }?.let { return@run it }
        Regex("/stream/s-\\d+/(\\d+)").find(url)?.groupValues?.get(1)
    } ?: return

    val srcUrl = "$host/stream/getSources?id=$streamId&type=$type"
    val root: JSONObject = try {
        val resp = app.get(srcUrl, headers = ajaxHeaders, referer = url).text
        JSONObject(resp)
    } catch (e: Exception) {
        BCLog.e("AniKoto sources fetch failed: ${e.message}"); return
    }

    val m3u8 = run {
        val sources = root.opt("sources")
        when (sources) {
            is JSONObject -> sources.optString("file").takeIf { it.isNotBlank() }
            is JSONArray -> if (sources.length() > 0)
                sources.optJSONObject(0)?.optString("file")?.takeIf { it.isNotBlank() }
            else null
            else -> null
        }
    } ?: root.optString("enc").takeIf { it.isNotBlank() }?.let { anikotoDecryptMegaPlaySources(it) }

    if (m3u8.isNullOrBlank()) { BCLog.d("AniKoto: no m3u8"); return }

    val signed = anikotoSignMegaPlayUrl(m3u8)

    val link = newExtractorLink(
        source = "AniKoto",
        name = label,
        url = signed,
        type = ExtractorLinkType.M3U8
    ) {
        this.referer = "$host/"
        this.headers = playbackHeaders
    }
    callback(link)

    val tracks = root.optJSONArray("tracks")
    if (tracks != null) {
        for (i in 0 until tracks.length()) {
            val tr = tracks.optJSONObject(i) ?: continue
            val kind = tr.optString("kind")
            if (kind != "captions" && kind != "subtitles") continue
            val file = tr.optString("file").takeIf { it.isNotBlank() } ?: continue
            val trackUrl = if (file.startsWith("http")) file else "$host/$file"
            val subLabel = tr.optString("label").takeIf { it.isNotBlank() } ?: "Unknown"
            try { subtitleCallback(SubtitleFile(subLabel, trackUrl)) } catch (_: Exception) {}
        }
    }

    BCLog.d("AniKoto: emitted from $label")
}

// ═══════════════════════════════════════════════════════════════
// ── MegaPlay / Vidtube / Vidwish extractors ──
// ═══════════════════════════════════════════════════════════════

open class AnikotoMegaPlay : ExtractorApi() {
    override val name: String = "MegaPlay"
    override val mainUrl: String = "https://megaplay.buzz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = if (url.contains("/dub", ignoreCase = true)) "dub" else "sub"
        val label = "${type.uppercase(Locale.ROOT)} ($name)"
        anikotoExtractMegaPlayUrl(url, referer, mainUrl, label, subtitleCallback, callback)
    }
}

class AnikotoVidtube : AnikotoMegaPlay() {
    override val name: String = "Vidtube"
    override val mainUrl: String = "https://vidtube.site"
}

class AnikotoVidwish : AnikotoMegaPlay() {
    override val name: String = "Vidwish"
    override val mainUrl: String = "https://vidwish.live"
}
