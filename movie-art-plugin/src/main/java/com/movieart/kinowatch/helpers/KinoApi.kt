package com.movieart.kinowatch.helpers

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the Kino.Watch / KinoPub API, used only to look up a trailer for the focused
 * title. The user signs in once with the usual "enter this code on the website" flow, exactly like
 * adding another device to the account. Tokens are kept in their own private preferences file
 * (NOT in the plugin settings, which Projectivy can export/back up).
 */
object KinoApi {

    // API hosts (the mirror list of Kino.Watch 2.12). The one that answered last is tried first.
    private val HOSTS = listOf(
        "https://api.boramoraboom.ru/",
        "https://api.srvkp.com/",
        "https://api.teleos.club/",
        "https://api.alador.space/"
    )

    private const val CLIENT_ID = "android"
    private const val CLIENT_SECRET = "rcaqh7wodackn9ll1uggvqkx2iib6umh"

    private const val PREFS = "kino_account"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_HOST = "last_good_host"
    private const val KEY_STATUS = "trailer_status"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    class ApiResult(val ok: Boolean, val code: Int, val body: String, val error: String?)

    class DeviceCode(
        val code: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSec: Int,
        val expiresInSec: Int
    )

    enum class PollState { PENDING, SIGNED_IN, FAILED }
    class PollResult(val state: PollState, val message: String)

    // ---------------------------------------------------------------- storage

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSignedIn(context: Context): Boolean =
        !prefs(context).getString(KEY_ACCESS, null).isNullOrEmpty() ||
            !prefs(context).getString(KEY_REFRESH, null).isNullOrEmpty()

    fun signOut(context: Context) {
        prefs(context).edit().remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES_AT).apply()
    }

    fun setStatus(context: Context, text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        prefs(context).edit().putString(KEY_STATUS, "[$time] $text").apply()
    }

    fun getStatus(context: Context): String =
        prefs(context).getString(KEY_STATUS, null) ?: "no trailer requests yet"

    private fun saveTokens(context: Context, json: JsonObject): Boolean {
        val access = json.stringOrNull("access_token") ?: return false
        val refresh = json.stringOrNull("refresh_token")
        val expiresIn = json.longOrNull("expires_in") ?: 3600L
        val editor = prefs(context).edit()
            .putString(KEY_ACCESS, access)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000L)
        if (!refresh.isNullOrEmpty()) editor.putString(KEY_REFRESH, refresh)
        editor.apply()
        return true
    }

    // ---------------------------------------------------------------- hosts / requests

    private fun hostsInOrder(context: Context): List<String> {
        val last = prefs(context).getString(KEY_HOST, null)
        return if (last != null && HOSTS.contains(last)) {
            listOf(last) + HOSTS.filter { it != last }
        } else {
            HOSTS
        }
    }

    /** Runs [build] against each host until one answers like the real API. */
    private fun call(context: Context, build: (String) -> Request): ApiResult {
        var lastError = "no host answered"
        for (host in hostsInOrder(context)) {
            try {
                client.newCall(build(host)).execute().use { resp ->
                    val text = resp.body.string()
                    val contentType = resp.header("Content-Type") ?: ""
                    val looksLikeApi = contentType.lowercase(Locale.ROOT).contains("json") || text.trimStart().startsWith("{")
                    if (resp.code < 500 && looksLikeApi) {
                        prefs(context).edit().putString(KEY_HOST, host).apply()
                        return ApiResult(resp.isSuccessful, resp.code, text, null)
                    }
                    lastError = "$host -> HTTP ${resp.code} (not the API)"
                }
            } catch (e: Exception) {
                lastError = "$host -> ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return ApiResult(false, 0, "", lastError)
    }

    private fun oauthRequest(host: String, params: Map<String, String>): Request {
        val url = host.toHttpUrl().newBuilder().addPathSegments("oauth2/device").apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("client_id", CLIENT_ID)
            addQueryParameter("client_secret", CLIENT_SECRET)
        }.build()
        return Request.Builder().url(url).post(FormBody.Builder().build()).build()
    }

    // ---------------------------------------------------------------- sign in

    fun startDeviceLogin(context: Context): Pair<DeviceCode?, String> {
        val res = call(context) { host -> oauthRequest(host, mapOf("grant_type" to "device_code")) }
        if (!res.ok) return Pair(null, res.error ?: "HTTP ${res.code}: ${res.body.take(120)}")
        return try {
            val json = JsonParser.parseString(res.body).asJsonObject
            val code = json.stringOrNull("code")
            val userCode = json.stringOrNull("user_code")
            if (code == null || userCode == null) {
                Pair(null, "unexpected answer: ${res.body.take(120)}")
            } else {
                Pair(
                    DeviceCode(
                        code = code,
                        userCode = userCode,
                        verificationUri = json.stringOrNull("verification_uri") ?: "https://kino.pub/device",
                        intervalSec = (json.longOrNull("interval") ?: 5L).toInt(),
                        expiresInSec = (json.longOrNull("expires_in") ?: 300L).toInt()
                    ),
                    ""
                )
            }
        } catch (e: Exception) {
            Pair(null, "bad answer: ${e.message}")
        }
    }

    fun pollDeviceLogin(context: Context, deviceCode: String): PollResult {
        val res = call(context) { host ->
            oauthRequest(host, mapOf("grant_type" to "device_token", "code" to deviceCode))
        }
        if (res.error != null) return PollResult(PollState.PENDING, res.error)
        return try {
            val json = JsonParser.parseString(res.body).asJsonObject
            if (saveTokens(context, json)) {
                notifyDevice(context)
                return PollResult(PollState.SIGNED_IN, "ok")
            }
            val error = json.stringOrNull("error") ?: ""
            when (error) {
                "authorization_pending", "slow_down", "" -> PollResult(PollState.PENDING, error)
                else -> PollResult(PollState.FAILED, error)
            }
        } catch (e: Exception) {
            PollResult(PollState.PENDING, "bad answer: ${e.message}")
        }
    }

    /** Gives the new "device" a readable name in the account's device list. Best effort. */
    private fun notifyDevice(context: Context) {
        try {
            val token = prefs(context).getString(KEY_ACCESS, null) ?: return
            call(context) { host ->
                Request.Builder()
                    .url(host + "v1/device/notify")
                    .header("Authorization", "Bearer $token")
                    .post(
                        FormBody.Builder()
                            .add("title", "Movie Art for Kino.Watch")
                            .add("hardware", "Android TV")
                            .add("software", "Projectivy plugin")
                            .build()
                    )
                    .build()
            }
        } catch (_: Exception) {
        }
    }

    private fun refreshToken(context: Context): Boolean {
        val refresh = prefs(context).getString(KEY_REFRESH, null) ?: return false
        val res = call(context) { host ->
            oauthRequest(host, mapOf("grant_type" to "refresh_token", "refresh_token" to refresh))
        }
        if (!res.ok) return false
        return try {
            saveTokens(context, JsonParser.parseString(res.body).asJsonObject)
        } catch (_: Exception) {
            false
        }
    }

    private fun authorizedGet(context: Context, path: String): ApiResult {
        val expiresAt = prefs(context).getLong(KEY_EXPIRES_AT, 0L)
        if (prefs(context).getString(KEY_ACCESS, null).isNullOrEmpty() ||
            expiresAt - System.currentTimeMillis() < 60_000L
        ) {
            refreshToken(context)
        }
        var token = prefs(context).getString(KEY_ACCESS, null)
            ?: return ApiResult(false, 0, "", "not signed in")
        var res = call(context) { host ->
            Request.Builder().url(host + path).header("Authorization", "Bearer $token").build()
        }
        if (res.code == 401 && refreshToken(context)) {
            token = prefs(context).getString(KEY_ACCESS, null) ?: return res
            res = call(context) { host ->
                Request.Builder().url(host + path).header("Authorization", "Bearer $token").build()
            }
        }
        return res
    }

    // ---------------------------------------------------------------- trailer lookup

    /** Returns (trailer file URL or null, human readable explanation). */
    fun fetchTrailerUrl(context: Context, itemId: Int): Pair<String?, String> {
        val paths = listOf("v1/items/$itemId", "v1/items/trailer?id=$itemId")
        var explanation = ""
        for (path in paths) {
            val res = authorizedGet(context, path)
            if (res.error != null) {
                return Pair(null, "request failed: ${res.error}")
            }
            if (res.code == 401) {
                return Pair(null, "sign-in expired, sign in again in the plugin settings")
            }
            if (!res.ok) {
                explanation = "HTTP ${res.code} for $path: ${res.body.take(100)}"
                continue
            }
            try {
                val root = JsonParser.parseString(res.body)
                val trailer = findKey(root, "trailer")
                val url = if (trailer != null) findUrl(trailer) else null
                if (url != null) return Pair(url, "ok")
                explanation = "no trailer in the answer for $path"
            } catch (e: Exception) {
                explanation = "unreadable answer for $path: ${e.message}"
            }
        }
        return Pair(null, explanation.ifEmpty { "no trailer" })
    }

    private fun findKey(el: JsonElement, key: String): JsonElement? {
        if (el.isJsonObject) {
            val obj = el.asJsonObject
            if (obj.has(key)) return obj.get(key)
            for (entry in obj.entrySet()) {
                val found = findKey(entry.value, key)
                if (found != null) return found
            }
        } else if (el.isJsonArray) {
            for (child in el.asJsonArray) {
                val found = findKey(child, key)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findUrl(el: JsonElement): String? {
        if (el.isJsonPrimitive) {
            val s = el.asString
            return if (s.startsWith("http://") || s.startsWith("https://")) s else null
        }
        if (el.isJsonObject) {
            val obj = el.asJsonObject
            for (key in listOf("file", "url", "link", "src")) {
                if (obj.has(key)) {
                    val found = findUrl(obj.get(key))
                    if (found != null) return found
                }
            }
            for (entry in obj.entrySet()) {
                val found = findUrl(entry.value)
                if (found != null) return found
            }
        }
        if (el.isJsonArray) {
            val arr: JsonArray = el.asJsonArray
            for (child in arr) {
                val found = findUrl(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val e = this.get(key) ?: return null
        return if (e.isJsonPrimitive) e.asString else null
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        val e = this.get(key) ?: return null
        return try {
            if (e.isJsonPrimitive) e.asLong else null
        } catch (_: Exception) {
            null
        }
    }
}
