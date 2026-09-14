package com.flummox.bingecloud

import android.util.Base64
import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

// ─────────────────────────────────────────
//  Constants
// ─────────────────────────────────────────
private const val MB_SECRET = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
private const val MB_APP_VERSION = "50020117"
private const val MB_UA_SUFFIX = "Cronet/135.0.7012.3"
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

private val MB_HOSTS = listOf(
    "api6.aoneroom.com",
    "api5.aoneroom.com",
    "api4.aoneroom.com",
    "api4sg.aoneroom.com",
    "api3.aoneroom.com",
    "api6sg.aoneroom.com",
    "api.inmoviebox.com"
)

private data class MBDevice(val model: String, val os: String, val build: String)
private val MB_DEVICES = listOf(
    MBDevice("Redmi Note 8 Pro", "Android 11", "begonia"),
    MBDevice("Redmi Note 9S", "Android 11", "curtana"),
    MBDevice("Redmi Note 10 Pro", "Android 12", "sweet"),
    MBDevice("POCO F3", "Android 12", "alioth"),
    MBDevice("Redmi K40", "Android 12", "alioth"),
    MBDevice("Xiaomi 11 Lite NE", "Android 12", "lisa"),
    MBDevice("Redmi 9A", "Android 10", "dandelion")
)

private val MB_IP_PREFIXES = listOf(
    "103.241.12", "49.36.100", "117.195.20",
    "59.145.30", "106.51.60", "49.207.10"
)

private val MB_TIMEZONES = listOf(
    "Asia/Kolkata", "Asia/Karachi", "Asia/Dhaka", "Asia/Shanghai", "Asia/Dubai"
)

// ─────────────────────────────────────────
//  Crypto helpers
// ─────────────────────────────────────────
private fun md5Hex(data: ByteArray): String {
    val md = MessageDigest.getInstance("MD5")
    return md.digest(data).joinToString("") { "%02x".format(it) }
}

private fun hmacMd5Base64(data: ByteArray, key: ByteArray): String {
    val mac = Mac.getInstance("HmacMD5")
    mac.init(SecretKeySpec(key, "HmacMD5"))
    return Base64.encodeToString(mac.doFinal(data), Base64.NO_WRAP)
}

private fun randomHex(len: Int): String {
    val chars = "0123456789abcdef"
    val sb = StringBuilder(len)
    repeat(len) { sb.append(chars[Random.nextInt(chars.length)]) }
    return sb.toString()
}

// ─────────────────────────────────────────
//  Signing
// ─────────────────────────────────────────
private fun buildSignedHeaders(
    method: String,
    path: String,
    query: String?,
    body: String,
    bearer: String?
): Map<String, String> {
    val ts = System.currentTimeMillis().toString()
    val contentType = "application/json"

    val xClientToken = "$ts,${md5Hex(ts.toByteArray())}"
    val bodyMd5 = md5Hex(body.toByteArray())

    // Canonical string — 6 lines
    val canonical = listOf(
        method.uppercase(),
        contentType,
        bodyMd5,
        ts,
        query ?: "",
        path
    ).joinToString("\n")

    val signature = hmacMd5Base64(canonical.toByteArray(), MB_SECRET.toByteArray())

    val device = MB_DEVICES.random()
    val deviceInfo = JSONObject().apply {
        put("device_id", randomHex(16))
        put("device_name", device.model)
        put("device_model", device.model)
        put("os_version", device.os)
        put("app_version", MB_APP_VERSION)
        put("net", "wifi")
        put("timezone", MB_TIMEZONES.random())
        put("country", "US")
    }.toString()

    val ua = "com.community.oneroom/$MB_APP_VERSION " +
            "(Linux; U; ${device.os}; en_US; ${device.model}; " +
            "Build/${device.build}; $MB_UA_SUFFIX)"

    val headers = mutableMapOf(
        "User-Agent" to ua,
        "Accept" to "application/json",
        "Content-Type" to contentType,
        "Connection" to "keep-alive",
        "x-client-token" to xClientToken,
        "x-tr-signature" to "$ts|2|$signature",
        "x-client-info" to deviceInfo,
        "x-client-status" to "0",
        "x-forwarded-for" to "${MB_IP_PREFIXES.random()}.${Random.nextInt(1, 254)}"
    )
    if (!bearer.isNullOrBlank()) headers["Authorization"] = "Bearer $bearer"
    return headers
}

// ─────────────────────────────────────────
//  Session
// ─────────────────────────────────────────
private var mbSession: String? = null

private suspend fun mbLogin(): String? {
    val path = "/wefeed-mobile-bff/user-api/visitor-login"
    val body = "{}"
    for (host in MB_HOSTS.shuffled()) {
        try {
            val headers = buildSignedHeaders("POST", path, null, body, null)
            val res = app.post(
                url = "https://$host$path",
                headers = headers,
                requestBody = body.toRequestBody(JSON_MEDIA)
            )
            BCLog.d("MB login $host -> ${res.code}")
            if (res.code in 200..299) {
                val text = res.text
                BCLog.d("MB login body: ${text.take(200)}")
                val obj = JSONObject(text)
                val token = obj.optString("token").ifBlank {
                    obj.optJSONObject("data")?.optString("token") ?: ""
                }
                if (token.isNotBlank()) {
                    BCLog.d("MB login success, token len=${token.length}")
                    return token
                }
            }
        } catch (e: Exception) {
            BCLog.e("MB login $host failed: ${e.message}")
        }
    }
    return null
}

private suspend fun ensureSession(): String? {
    mbSession?.let { return it }
    BCLog.d("MB: no cached session, logging in...")
    val t = mbLogin()
    if (t != null) {
        BCLog.d("MB: login OK (token ${t.length} chars)")
        mbSession = t
    } else {
        BCLog.e("MB: login FAILED — all hosts rejected")
    }
    return t
}

// ─────────────────────────────────────────
//  Generic GET / POST
// ─────────────────────────────────────────
private suspend fun mbGet(
    path: String,
    query: String? = null,
    retried: Boolean = false
): JSONObject? {
    val session = ensureSession() ?: run {
        BCLog.e("MB: no session for GET $path")
        return null
    }
    for (host in MB_HOSTS.shuffled()) {
        try {
            val headers = buildSignedHeaders("GET", path, query, "", session)
            val url = if (query.isNullOrBlank())
                "https://$host$path"
            else
                "https://$host$path?$query"
            val res = app.get(url, headers = headers)
            BCLog.d("MB GET $host$path -> ${res.code}")
            if (res.code in 200..299) {
                return try { JSONObject(res.text) } catch (e: Exception) {
                    BCLog.e("MB: JSON parse failed: ${e.message}")
                    null
                }
            }
            if (res.code == 401 && !retried) {
                BCLog.d("MB: session expired, re-login")
                mbSession = null
                return mbGet(path, query, true)
            }
        } catch (e: Exception) {
            BCLog.e("MB GET $host failed: ${e.message}")
        }
    }
    return null
}

private suspend fun mbPost(
    path: String,
    body: String,
    query: String? = null,
    retried: Boolean = false
): JSONObject? {
    val session = ensureSession() ?: run {
        BCLog.e("MB: no session for POST $path")
        return null
    }
    for (host in MB_HOSTS.shuffled()) {
        try {
            val headers = buildSignedHeaders("POST", path, query, body, session)
            val url = if (query.isNullOrBlank())
                "https://$host$path"
            else
                "https://$host$path?$query"
            val res = app.post(
                url = url,
                headers = headers,
                requestBody = body.toRequestBody(JSON_MEDIA)
            )
            BCLog.d("MB POST $host$path -> ${res.code}")
            if (res.code in 200..299) {
                return try { JSONObject(res.text) } catch (e: Exception) {
                    BCLog.e("MB: JSON parse failed: ${e.message}")
                    null
                }
            }
            if (res.code == 401 && !retried) {
                mbSession = null
                return mbPost(path, body, query, true)
            }
        } catch (e: Exception) {
            BCLog.e("MB POST $host failed: ${e.message}")
        }
    }
    return null
}

// ─────────────────────────────────────────
//  Public data types
// ─────────────────────────────────────────
data class MBSubject(
    val subjectId: String,
    val title: String,
    val year: Int?,
    val type: Int
)

data class MBStream(
    val url: String,
    val quality: String,
    val size: String?
)

// ─────────────────────────────────────────
//  Public API
// ─────────────────────────────────────────
suspend fun mbSearch(query: String, page: Int = 1): List<MBSubject> {
    val q = "keyword=${URLEncoder.encode(query, "UTF-8")}&page=$page&perPage=20"
    BCLog.d("MB: GET /subject/search?$q")
    val json = mbGet("/wefeed-mobile-bff/subject/search", q) ?: run {
        BCLog.e("MB: search GET returned null")
        return emptyList()
    }
    BCLog.d("MB: search response: ${json.toString().take(300)}")

    val itemsArray = json.optJSONObject("data")?.optJSONArray("items")
        ?: json.optJSONArray("items")
        ?: run {
            BCLog.e("MB: no 'items' array in response")
            return emptyList()
        }

    val out = mutableListOf<MBSubject>()
    for (i in 0 until itemsArray.length()) {
        val o = itemsArray.optJSONObject(i) ?: continue
        val id = o.optString("subjectId").ifBlank {
            o.optString("subject_id").ifBlank {
                o.optString("id")
            }
        }
        if (id.isBlank()) continue
        val title = o.optString("title").ifBlank { o.optString("name") }
        val yearStr = o.optString("releaseDate").ifBlank { o.optString("year") }
        val year = yearStr.take(4).toIntOrNull()
        val type = o.optInt("subjectType", o.optInt("type", 1))
        out.add(MBSubject(id, title, year, type))
    }
    return out
}

suspend fun mbDetail(subjectId: String): JSONObject? {
    return mbGet("/wefeed-mobile-bff/subject/detail", "subjectId=$subjectId")
}

suspend fun mbPlay(subjectId: String, season: Int = 0, episode: Int = 0): List<MBStream> {
    val q = "subjectId=$subjectId&se=$season&ep=$episode"
    BCLog.d("MB: GET /subject/play?$q")
    val json = mbGet("/wefeed-mobile-bff/subject/play", q) ?: run {
        BCLog.e("MB: play GET returned null")
        return emptyList()
    }

    val root = json.optJSONObject("data") ?: json
    val streamsArr = root.optJSONArray("streams")
        ?: root.optJSONArray("videos")
        ?: run {
            BCLog.e("MB: no 'streams' in play response")
            return emptyList()
        }

    val out = mutableListOf<MBStream>()
    for (i in 0 until streamsArr.length()) {
        val o = streamsArr.optJSONObject(i) ?: continue
        val url = o.optString("url").ifBlank {
            o.optString("playUrl").ifBlank { o.optString("src") }
        }
        if (url.isBlank()) continue
        val quality = o.optString("quality").ifBlank {
            o.optString("resolution").ifBlank { "Auto" }
        }
        val size = o.optString("size").ifBlank { null }
        out.add(MBStream(url, quality, size))
    }
    return out
}
