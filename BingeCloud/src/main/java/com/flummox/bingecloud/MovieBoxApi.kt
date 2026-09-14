package com.flummox.bingecloud

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
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
    val xClientToken = "$ts,${md5Hex(ts.reversed().toByteArray())}"

    val accept = "application/json"
    val contentType = "application/json"
    val bodyBytes = body.toByteArray()
    val bodyLen = bodyBytes.size.toString()
    val bodyMd5 = md5Hex(bodyBytes.copyOfRange(0, minOf(bodyBytes.size, 102_400)))

    val sortedQuery = query
        ?.split("&")
        ?.filter { it.isNotBlank() }
        ?.sorted()
        ?.joinToString("&")
        ?: ""
    val pathWithQuery = if (sortedQuery.isEmpty()) path else "$path?$sortedQuery"

    val canonical = listOf(
        method.uppercase(),
        accept,
        contentType,
        bodyLen,
        ts,
        bodyMd5,
        pathWithQuery
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
        "Accept" to accept,
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
                "https://$host$path",
                headers = headers,
                data = body
            )
            Log.d("BingeCloud-MB", "login $host -> ${res.code}")
            if (res.code in 200..299) {
                val text = res.text
                val obj = JSONObject(text)
                val token = obj.optString("token").ifBlank {
                    obj.optJSONObject("data")?.optString("token") ?: ""
                }
                if (token.isNotBlank()) {
                    Log.d("BingeCloud-MB", "login success, token len=${token.length}")
                    return token
                }
            }
        } catch (e: Exception) {
            Log.w("BingeCloud-MB", "login $host failed: ${e.message}")
        }
    }
    return null
}

private suspend fun ensureSession(): String? {
    mbSession?.let { return it }
    val t = mbLogin()
    if (t != null) mbSession = t
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
        Log.e("BingeCloud-MB", "no session for GET $path")
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
            Log.d("BingeCloud-MB", "GET $host$path -> ${res.code}")
            if (res.code in 200..299) {
                return try { JSONObject(res.text) } catch (_: Exception) { null }
            }
            if (res.code == 401 && !retried) {
                Log.d("BingeCloud-MB", "session expired, re-login")
                mbSession = null
                return mbGet(path, query, true)
            }
        } catch (e: Exception) {
            Log.w("BingeCloud-MB", "GET $host failed: ${e.message}")
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
        Log.e("BingeCloud-MB", "no session for POST $path")
        return null
    }
    for (host in MB_HOSTS.shuffled()) {
        try {
            val headers = buildSignedHeaders("POST", path, query, body, session)
            val url = if (query.isNullOrBlank())
                "https://$host$path"
            else
                "https://$host$path?$query"
            val res = app.post(url, headers = headers, data = body)
            Log.d("BingeCloud-MB", "POST $host$path -> ${res.code}")
            if (res.code in 200..299) {
                return try { JSONObject(res.text) } catch (_: Exception) { null }
            }
            if (res.code == 401 && !retried) {
                mbSession = null
                return mbPost(path, body, query, true)
            }
        } catch (e: Exception) {
            Log.w("BingeCloud-MB", "POST $host failed: ${e.message}")
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
    val type: Int          // 1 = movie, 2 = series
)

data class MBStream(
    val url: String,
    val quality: String,   // "1080p", "720p", etc.
    val size: String?
)

// ─────────────────────────────────────────
//  Public API
// ─────────────────────────────────────────
suspend fun mbSearch(query: String, page: Int = 1): List<MBSubject> {
    val q = "keyword=${URLEncoder.encode(query, "UTF-8")}&page=$page&perPage=20"
    val json = mbGet("/wefeed-mobile-bff/subject/search", q) ?: return emptyList()

    // Response shape can be { data: { items: [...] } } or { items: [...] }
    val itemsArray = json.optJSONObject("data")?.optJSONArray("items")
        ?: json.optJSONArray("items")
        ?: return emptyList()

    val out = mutableListOf<MBSubject>()
    for (i in 0 until itemsArray.length()) {
        val o = itemsArray.optJSONObject(i) ?: continue
        val id = o.optString("subjectId").ifBlank {
            o.optString("subject_id").ifBlank {
                o.optString("id")
            }
        }
        if (id.isBlank()) continue
        val title = o.optString("title").ifBlank {
            o.optString("name")
        }
        val yearStr = o.optString("releaseDate").ifBlank {
            o.optString("year")
        }
        val year = yearStr.take(4).toIntOrNull()
        val type = o.optInt("subjectType",
            o.optInt("type", 1))
        out.add(MBSubject(id, title, year, type))
    }
    return out
}

suspend fun mbDetail(subjectId: String): JSONObject? {
    return mbGet("/wefeed-mobile-bff/subject/detail", "subjectId=$subjectId")
}

suspend fun mbPlay(subjectId: String, season: Int = 0, episode: Int = 0): List<MBStream> {
    val q = "subjectId=$subjectId&se=$season&ep=$episode"
    val json = mbGet("/wefeed-mobile-bff/subject/play", q)
        ?: return emptyList()

    val root = json.optJSONObject("data") ?: json
    val streamsArr = root.optJSONArray("streams")
        ?: root.optJSONArray("videos")
        ?: return emptyList()

    val out = mutableListOf<MBStream>()
    for (i in 0 until streamsArr.length()) {
        val o = streamsArr.optJSONObject(i) ?: continue
        val url = o.optString("url").ifBlank {
            o.optString("playUrl").ifBlank {
                o.optString("src")
            }
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
