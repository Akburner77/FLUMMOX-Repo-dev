package com.flummox.bingecloud

import android.util.Base64
import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

private const val MB_SECRET_B64 = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
private const val MB_SECRET_ALT_B64 = "XQn2nnO41/L92o1iuXhSLHTbXvY4Z5ZZ62m8mSLA"
private val MB_VERSION_CODE: Long = (BuildConfig.MB_VERSION_CODE.toLongOrNull() ?: 50020126L)
private val MB_VERSION_NAME: String = BuildConfig.MB_VERSION_NAME.ifBlank { "4.0.02.0831.03" }
private const val MB_PACKAGE = "com.community.mbox.in"
private val MB_UA = "com.community.mbox.in/$MB_VERSION_CODE (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)"
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

private val MB_HOSTS = listOf(
    "api6.aoneroom.com",
    "api5.aoneroom.com",
    "api4.aoneroom.com",
    "api4sg.aoneroom.com",
    "api3.aoneroom.com"
)

private const val MB_BOOTSTRAP_HOST = "apig.inmoviebox.com"
private const val MB_BOOTSTRAP_PATH = "/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"

// ══════════════════════════════════════════════════════════════
// ── DEVICE ID ──
// ══════════════════════════════════════════════════════════════
private val mbDeviceIdLock = Any()
private var mbDeviceId: String? = null

private fun deviceId(): String {
    return mbDeviceId ?: synchronized(mbDeviceIdLock) {
        mbDeviceId ?: run {
            val chars = "0123456789abcdef"
            val sb = StringBuilder(16)
            repeat(16) { sb.append(chars[Random.nextInt(chars.length)]) }
            sb.toString().also { mbDeviceId = it }
        }
    }
}

private fun clientInfo(): String {
    return """{"package_name":"$MB_PACKAGE","version_name":"$MB_VERSION_NAME","version_code":$MB_VERSION_CODE,"os":"android","os_version":"14","device_id":"${deviceId()}","install_store":"official","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"Google","model":"Pixel 8","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}"""
}

// ══════════════════════════════════════════════════════════════
// ── CRYPTO ──
// ══════════════════════════════════════════════════════════════
private fun md5Hex(data: ByteArray): String {
    val md = MessageDigest.getInstance("MD5")
    return md.digest(data).joinToString("") { "%02x".format(it) }
}

private fun b64DecodeBytes(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)
private fun b64Encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

private val SECRET_KEY_BYTES: ByteArray by lazy { b64DecodeBytes(MB_SECRET_B64) }
private val SECRET_KEY_ALT_BYTES: ByteArray by lazy { b64DecodeBytes(MB_SECRET_ALT_B64) }

private fun generateXClientToken(ts: Long): String {
    val tsStr = ts.toString()
    val hash = md5Hex(tsStr.reversed().toByteArray(Charsets.UTF_8))
    return "$tsStr,$hash"
}

private fun buildCanonicalString(
    method: String,
    accept: String?,
    contentType: String?,
    url: String,
    body: String?,
    timestamp: Long
): String {
    val parsed = try { URI(url) } catch (_: Exception) { null }
    val path = parsed?.path ?: ""
    val query = parsed?.query?.takeIf { it.isNotBlank() }?.let { q ->
        q.split("&")
            .mapNotNull { part ->
                val parts = part.split("=")
                if (parts.isEmpty()) null else parts[0] to (parts.getOrNull(1) ?: "")
            }
            .sortedBy { it.first }
            .joinToString("&") { (k, v) -> "$k=$v" }
    } ?: ""
    val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path

    val bodyBytes = body?.toByteArray(Charsets.UTF_8)
    val bodyHash = if (bodyBytes != null) {
        val trimmed = if (bodyBytes.size > 0x19000) bodyBytes.copyOfRange(0, 0x19000) else bodyBytes
        md5Hex(trimmed)
    } else ""
    val bodyLength = bodyBytes?.size?.toString() ?: ""

    return buildString {
        append(method.uppercase(Locale.ROOT)); append('\n')
        append(accept ?: ""); append('\n')
        append(contentType ?: ""); append('\n')
        append(bodyLength); append('\n')
        append(timestamp); append('\n')
        append(bodyHash); append('\n')
        append(canonicalUrl)
    }
}

private fun generateXTrSignature(
    method: String,
    accept: String?,
    contentType: String?,
    url: String,
    body: String?,
    useAltKey: Boolean = false,
    hardcodedTimestamp: Long? = null
): String {
    val ts = hardcodedTimestamp ?: System.currentTimeMillis()
    val canonical = buildCanonicalString(method, accept, contentType, url, body, ts)
    val secret = if (useAltKey) SECRET_KEY_ALT_BYTES else SECRET_KEY_BYTES
    val mac = Mac.getInstance("HmacMD5")
    mac.init(SecretKeySpec(secret, "HmacMD5"))
    val sig = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
    return "$ts|2|${b64Encode(sig)}"
}

private fun buildHeaders(
    method: String,
    url: String,
    contentType: String,
    accept: String,
    body: String?,
    bearer: String?
): Map<String, String> {
    val ts = System.currentTimeMillis()
    val xClientToken = generateXClientToken(ts)
    val xTrSig = generateXTrSignature(method, accept, contentType, url, body, false, ts)
    val map = mutableMapOf(
        "user-agent" to MB_UA,
        "accept" to accept,
        "content-type" to contentType,
        "connection" to "keep-alive",
        "x-client-token" to xClientToken,
        "x-tr-signature" to xTrSig,
        "x-client-info" to clientInfo(),
        "x-client-status" to "0"
    )
    if (!bearer.isNullOrBlank()) map["Authorization"] = "Bearer $bearer"
    return map
}

// ══════════════════════════════════════════════════════════════
// ── SESSION ──
// ══════════════════════════════════════════════════════════════
private var mbSession: String? = null

private fun parseJwtExp(token: String): Long {
    return try {
        val parts = token.split(".")
        if (parts.size != 3) return 0L
        val padding = when (parts[1].length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        val decoded = Base64.decode(parts[1] + padding, Base64.URL_SAFE or Base64.NO_WRAP)
        JSONObject(String(decoded)).optLong("exp", 0L) * 1000L
    } catch (e: Exception) { 0L }
}

fun restoreMbSession() {
    val tok = Settings.getMbToken() ?: return
    val exp = Settings.getMbTokenExp()
    if (exp > System.currentTimeMillis() + 60 * 60 * 1000L) {
        mbSession = tok
        val mins = (exp - System.currentTimeMillis()) / 60000
        BCLog.d("MB session restored (exp in ${mins}min)")
    }
}

private suspend fun bootstrapToken(): String? {
    val url = "https://$MB_BOOTSTRAP_HOST$MB_BOOTSTRAP_PATH"
    return try {
        val headers = buildHeaders("GET", url, "application/json", "application/json", null, null)
        val res = app.get(url, headers = headers)
        BCLog.d("MB bootstrap ${res.code}")
        if (res.code !in 200..299) return null
        val xUser = res.headers["x-user"] ?: res.headers["X-User"] ?: return null
        BCLog.d("MB x-user: ${xUser.take(120)}")
        val tok = JSONObject(xUser).optString("token").takeIf { it.isNotBlank() }
        if (tok != null) {
            BCLog.d("MB token len=${tok.length}")
            mbSession = tok
            val exp = parseJwtExp(tok)
            if (exp > 0) Settings.saveMbToken(tok, exp)
        }
        tok
    } catch (e: Exception) {
        BCLog.e("MB bootstrap failed: ${e.message}")
        null
    }
}

private suspend fun ensureSession(): String? {
    mbSession?.let { return it }
    restoreMbSession()
    mbSession?.let { return it }
    BCLog.d("MB: no session, bootstrapping…")
    return bootstrapToken()
}

// ══════════════════════════════════════════════════════════════
// ── GET / POST ──
// ══════════════════════════════════════════════════════════════
private suspend fun mbGet(path: String, query: String? = null, retried: Boolean = false): JSONObject? {
    val cacheKey = "mb:get:$path?${query ?: ""}"
    BCCache.get(cacheKey)?.let {
        return try { JSONObject(it) } catch (_: Exception) { null }
    }

    val session = ensureSession() ?: run {
        BCLog.e("MB: no session for GET $path"); return null
    }
    for (host in MB_HOSTS) {
        try {
            val fullUrl = if (query.isNullOrBlank()) "https://$host$path" else "https://$host$path?$query"
            val headers = buildHeaders("GET", fullUrl, "application/json", "application/json", null, session)
            val res = app.get(fullUrl, headers = headers)
            BCLog.d("MB GET $host$path -> ${res.code}")
            if (res.code in 200..299) {
                val text = res.text
                BCCache.put(cacheKey, text)
                return try { JSONObject(text) } catch (e: Exception) {
                    BCLog.e("MB JSON parse: ${e.message}"); null
                }
            }
            if ((res.code == 401 || res.code == 403) && !retried) {
                mbSession = null
                return mbGet(path, query, true)
            }
        } catch (e: Exception) {
            BCLog.e("MB GET $host err: ${e.message}")
        }
    }
    return null
}

data class MBSubject(val subjectId: String, val title: String, val year: Int?, val type: Int)
data class MBStream(
    val url: String,
    val quality: String,
    val size: String?,
    val signCookie: String? = null,
    val audio: String? = null,
    val durationSec: Long = 0L
)

// ══════════════════════════════════════════════════════════════
// ── SEARCH ──
// ══════════════════════════════════════════════════════════════
suspend fun mbSearch(query: String, page: Int = 1): List<MBSubject> {
    val cacheKey = "mb:search:$query:$page"
    BCCache.get(cacheKey)?.let { return parseSearchResults(it) }

    val session = ensureSession() ?: return emptyList()
    val jsonBody = "{\"page\": $page, \"perPage\": 20, \"keyword\": \"$query\", \"restrictKid\": 1}"
    BCLog.d("MB search: $query (p$page)")

    for (host in MB_HOSTS) {
        try {
            val url = "https://$host/wefeed-mobile-bff/subject-api/search/v2"
            val headers = buildHeaders("POST", url, "application/json; charset=utf-8",
                "application/json", jsonBody, session)
            val res = app.post(url, headers = headers, requestBody = jsonBody.toRequestBody(JSON_MEDIA))
            BCLog.d("MB POST search $host -> ${res.code}")
            if (res.code !in 200..299) {
                if (res.code == 401 || res.code == 403) {
                    mbSession = null
                    return mbSearch(query, page)
                }
                continue
            }
            BCCache.put(cacheKey, res.text)
            return parseSearchResults(res.text)
        } catch (e: Exception) {
            BCLog.e("MB search $host err: ${e.message}")
        }
    }
    return emptyList()
}

private fun parseSearchResults(text: String): List<MBSubject> {
    val json = try { JSONObject(text) } catch (_: Exception) { return emptyList() }
    val results = json.optJSONObject("data")?.optJSONArray("results") ?: return emptyList()
    val out = mutableListOf<MBSubject>()
    for (i in 0 until results.length()) {
        val r = results.optJSONObject(i) ?: continue
        val subs = r.optJSONArray("subjects") ?: continue
        for (j in 0 until subs.length()) {
            val s = subs.optJSONObject(j) ?: continue
            val id = s.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
            val title = s.optString("title").takeIf { it.isNotBlank() } ?: continue
            out.add(MBSubject(id, title, null, s.optInt("subjectType", 1)))
        }
    }
    BCLog.d("MB search: ${out.size} results")
    return out
}

suspend fun mbDetail(subjectId: String): JSONObject? =
    mbGet("/wefeed-mobile-bff/subject-api/get", "subjectId=$subjectId")

// ══════════════════════════════════════════════════════════════
// ── LANGUAGES ──
// ══════════════════════════════════════════════════════════════
suspend fun mbLanguages(originalSubjectId: String): List<Pair<String, String>> {
    val detail = try { mbDetail(originalSubjectId) } catch (e: Exception) { null }
    val dubs = detail?.optJSONObject("data")?.optJSONArray("dubs")

    if (dubs == null || dubs.length() == 0) {
        BCLog.d("MB langs: [Original] (no dubs array)")
        return listOf(originalSubjectId to "Original")
    }

    var originalLabel: String? = null
    val dubEntries = mutableListOf<Pair<String, String>>()
    for (i in 0 until dubs.length()) {
        val d = dubs.optJSONObject(i) ?: continue
        val id = d.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
        val lan = d.optString("lanName").takeIf { it.isNotBlank() } ?: continue
        if (id == originalSubjectId) { originalLabel = lan; continue }
        dubEntries.add(id to lan)
    }
    val out = mutableListOf<Pair<String, String>>()
    out.add(originalSubjectId to (originalLabel ?: "Original"))
    out.addAll(dubEntries)
    BCLog.d("MB langs: ${out.map { it.second }}")
    return out
}

// ══════════════════════════════════════════════════════════════
// ── PLAY-INFO ──
// ══════════════════════════════════════════════════════════════
suspend fun mbPlay(subjectId: String, season: Int = 0, episode: Int = 0, audioLabel: String? = null): List<MBStream> {
    val q = "subjectId=$subjectId&se=$season&ep=$episode"
    val json = mbGet("/wefeed-mobile-bff/subject-api/play-info", q) ?: return emptyList()
    val root = json.optJSONObject("data") ?: json
    val arr = root.optJSONArray("streams")
        ?: root.optJSONArray("videos")
        ?: root.optJSONArray("list")
        ?: return emptyList()
    val out = mutableListOf<MBStream>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val url = o.optString("url").ifBlank { o.optString("playUrl").ifBlank { o.optString("src") } }
        if (url.isBlank()) continue
        val resolutionsStr = o.optString("resolutions").ifBlank { null }
        val quality = resolutionsStr?.split(",")?.firstOrNull()?.trim()?.let {
            if (it.toIntOrNull() != null) "${it}p" else it
        } ?: o.optString("quality").ifBlank { "Auto" }

        var dur = o.optLong("duration", 0L)
        if (dur <= 0) dur = o.optLong("durationSeconds", 0L)
        if (dur <= 0) dur = o.optLong("length", 0L)
        if (dur <= 0) dur = o.optLong("durationMs", 0L).let { if (it > 0) it / 1000 else 0 }

        val idTypeVal = o.optString("idType")
        val formatVal = o.optString("format")
        val sizeVal = o.optString("size")
        val codecVal = o.optString("codecName")
        val urlHead = url.take(70)
        BCLog.d("MB raw [$audioLabel] dur=${dur}s idType=$idTypeVal fmt=$formatVal codec=$codecVal size=$sizeVal url=$urlHead")

        out.add(MBStream(
            url = url,
            quality = quality,
            size = sizeVal.ifBlank { null },
            signCookie = o.optString("signCookie").ifBlank { null },
            audio = audioLabel,
            durationSec = dur
        ))
    }
    BCLog.d("MB play [$audioLabel]: ${out.size} streams")
    return out
}
