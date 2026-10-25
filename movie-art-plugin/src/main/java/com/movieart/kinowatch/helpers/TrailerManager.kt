package com.movieart.kinowatch.helpers

import android.content.Context
import android.content.Intent
import com.movieart.kinowatch.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps track of which Kino.Watch title is focused in Projectivy, looks up the link to its trailer
 * in the background and tells the wallpaper service when the trailer is due to replace the still image.
 *
 * The trailer is NOT downloaded: Kino.Watch serves it as an HLS stream (.m3u8) and Projectivy's own
 * player (ExoPlayer) plays such links directly, so the plugin only hands over the link.
 *
 * Flow: card focused -> (after a short debounce) look up the trailer link and check that it answers
 * -> a later TimeElapsed event from Projectivy finds the link ready and at least [TRAILER_DELAY_MS]
 * after the focus, and the service answers with the video.
 */
object TrailerManager {

    const val TRAILER_DELAY_MS = 5_000L
    private const val DEBOUNCE_MS = 1_200L
    // Links may be time-limited, so a looked-up link is only reused for a while
    private const val LINK_TTL_MS = 10L * 60L * 1000L

    // Projectivy ignores two "please refresh" requests from one plugin that are less than 5 s apart
    private const val MIN_KICK_GAP_MS = 5_600L
    private const val KICK_MARGIN_MS = 250L
    private const val PROJECTIVY_PACKAGE = "com.spocky.projengmenu"
    private const val ACTION_PROVIDER_UPDATED = "tv.projectivy.plugin.action.WALLPAPER_PROVIDER_UPDATED"
    private const val EXTRA_PROVIDER_ID = "tv.projectivy.plugin.extra.PROVIDER_ID"

    private class ResolvedLink(val url: String, val resolvedAtMs: Long)

    private val executor = Executors.newSingleThreadScheduledExecutor()
    // separate thread so that a waiting "start the trailer" request is never stuck behind a network lookup
    private val kickScheduler = Executors.newSingleThreadScheduledExecutor()
    private val resolved = ConcurrentHashMap<Int, ResolvedLink>()
    private val noTrailerIds: MutableSet<Int> = Collections.synchronizedSet(HashSet())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var focusedId: Int = -1
    @Volatile private var focusStartMs: Long = 0L
    @Volatile private var sentForThisFocus: Boolean = false
    @Volatile private var kickScheduledForThisFocus: Boolean = false
    @Volatile private var lastKickMs: Long = 0L

    /** Kino.Watch poster URLs end with the title's id: .../poster/item/big/126307.jpg */
    fun itemIdFromPosterUrl(url: String?): Int? {
        if (url == null) return null
        return Regex("/(\\d+)\\.[A-Za-z0-9]+(\\?.*)?$").find(url)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun clearFocus() {
        focusedId = -1
        sentForThisFocus = false
        kickScheduledForThisFocus = false
    }

    fun onCardFocused(context: Context, itemId: Int) {
        val appContext = context.applicationContext
        if (focusedId != itemId) {
            focusedId = itemId
            focusStartMs = System.currentTimeMillis()
            sentForThisFocus = false
            kickScheduledForThisFocus = false
        }
        if (freshLink(itemId) != null) {
            scheduleKick(appContext, itemId)
            return
        }
        if (noTrailerIds.contains(itemId)) return
        if (!KinoApi.isSignedIn(appContext)) {
            KinoApi.setStatus(appContext, "not signed in to Kino.Watch")
            return
        }
        executor.schedule(Runnable {
            try {
                if (focusedId == itemId) prepare(appContext, itemId)
            } catch (e: Exception) {
                KinoApi.setStatus(appContext, "error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Called on Projectivy's TimeElapsed event. Returns the trailer link if the focused title's
     * trailer is ready, [delayMs] have passed since it was focused and it was not shown yet.
     */
    fun takeDueTrailerUrl(context: Context, delayMs: Long): String? {
        val id = focusedId
        if (id <= 0 || sentForThisFocus) return null
        if (System.currentTimeMillis() - focusStartMs < delayMs) return null
        val url = freshLink(id) ?: return null
        sentForThisFocus = true
        KinoApi.setStatus(context.applicationContext, "#$id: trailer stream sent to the background")
        return url
    }

    private fun freshLink(itemId: Int): String? {
        val link = resolved[itemId] ?: return null
        return if (System.currentTimeMillis() - link.resolvedAtMs < LINK_TTL_MS) link.url else null
    }

    // ---------------------------------------------------------------- background work

    private fun prepare(context: Context, itemId: Int) {
        KinoApi.setStatus(context, "#$itemId: looking up the trailer ...")
        val (url, explanation) = KinoApi.fetchTrailerUrl(context, itemId)
        if (url == null) {
            if (explanation.startsWith("no trailer")) noTrailerIds.add(itemId)
            KinoApi.setStatus(context, "#$itemId: $explanation")
            return
        }
        val problem = checkLink(url)
        if (problem != null) {
            KinoApi.setStatus(context, "#$itemId: trailer link does not work: $problem")
            return
        }
        resolved[itemId] = ResolvedLink(url, System.currentTimeMillis())
        val kind = if (url.contains(".m3u8", ignoreCase = true)) "HLS stream" else "video link"
        KinoApi.setStatus(context, "#$itemId: trailer ready ($kind), starts about ${TRAILER_DELAY_MS / 1000} s after focus")
        scheduleKick(context, itemId)
    }

    /**
     * Projectivy only asks the plugin for a new wallpaper on its own schedule, which does not fit
     * "5 seconds after focus". So at that moment the plugin itself tells Projectivy "I have an update":
     * Projectivy then drops its cached wallpaper and sends the plugin a TimeElapsed event, which
     * [takeDueTrailerUrl] answers with the trailer.
     */
    private fun scheduleKick(context: Context, itemId: Int) {
        if (focusedId != itemId || kickScheduledForThisFocus) return
        kickScheduledForThisFocus = true
        val now = System.currentTimeMillis()
        val at = maxOf(focusStartMs + TRAILER_DELAY_MS + KICK_MARGIN_MS, lastKickMs + MIN_KICK_GAP_MS)
        val wait = maxOf(0L, at - now)
        kickScheduler.schedule(Runnable {
            try {
                kick(context.applicationContext, itemId)
            } catch (e: Exception) {
                KinoApi.setStatus(context, "#$itemId: could not ask Projectivy: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, wait, TimeUnit.MILLISECONDS)
    }

    private fun kick(context: Context, itemId: Int) {
        if (focusedId != itemId || sentForThisFocus) return
        if (freshLink(itemId) == null) return
        lastKickMs = System.currentTimeMillis()
        val intent = Intent(ACTION_PROVIDER_UPDATED)
            .setPackage(PROJECTIVY_PACKAGE)
            .putExtra(EXTRA_PROVIDER_ID, context.getString(R.string.plugin_uuid))
        context.sendBroadcast(intent)
        KinoApi.setStatus(context, "#$itemId: asked Projectivy to start the trailer")
    }

    /** Quick check that the link answers (and, for HLS, really is a playlist). Returns a problem text or null. */
    private fun checkLink(url: String): String? {
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return "HTTP ${resp.code}"
                if (url.contains(".m3u8", ignoreCase = true)) {
                    val text = resp.body.string().trimStart('\uFEFF', ' ', '\n', '\r')
                    if (!text.startsWith("#EXTM3U")) return "not an HLS playlist"
                }
                null
            }
        } catch (e: Exception) {
            // Could be just this check failing (e.g. a network hiccup): do not block the trailer
            null
        }
    }
}
