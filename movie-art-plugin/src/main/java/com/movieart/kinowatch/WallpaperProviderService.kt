package com.movieart.kinowatch

import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.webkit.MimeTypeMap
import com.movieart.kinowatch.helpers.BackgroundPickerHelper
import com.movieart.kinowatch.helpers.LottieEditorRegex
import com.movieart.kinowatch.helpers.cleanExpiredCache
import com.movieart.kinowatch.helpers.exposeFileToOtherApps
import com.movieart.kinowatch.helpers.getCacheFile
import com.movieart.kinowatch.helpers.downloadBitmap
import com.movieart.kinowatch.helpers.saveBitmapToFile
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

// This fork intentionally does NOT use TMDB (or any title-matching against a third-party
// database) to find posters. For a card coming from Kinowatch ("Я смотрю" row), Projectivy
// already hands us the exact poster/backdrop URI that Kinowatch itself published into the
// Android TV PreviewProgram/WatchNext data (event.iconUri). We just forward that URI back to
// Projectivy inside the Wallpaper it renders - we never open/copy that image ourselves, so we
// never need a FileProvider or permission grant for it: Projectivy already has whatever access
// it needed to read it in the first place when it built the ProgramCardFocused event.
//
// This trades a small amount of visual polish (no auto-cropped backdrop + logo compositing like
// TMDB gave us) for correctness: the poster shown is always exactly Kinowatch's own poster for
// the exact item focused, never a wrong/mismatched title from fuzzy search. We deliberately use
// the default WallpaperDisplayMode (CROP), matching exactly what this plugin already did for its
// "fallback to card icon" path before this fork - that's the one battle-tested way this plugin
// has ever passed a raw (possibly network) image URI straight to Projectivy.
@Suppress("WrongConstant") // false positive linting issue for WallpaperType
class WallpaperProviderService : Service() {

    val that = this

    // Which image was actually used for the last ProgramCardFocused (shown on the debug screen)
    @Volatile private var lastImageSource: String = ""

    private fun describeEventForDebug(event: Event?): String {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val callNum = PreferencesManager.getWallpapersCallCount
        val body = when (event) {
            is Event.ProgramCardFocused ->
                "ProgramCardFocused\ntitle=${event.title}\ntype=${event.type}\n" +
                    "iconUri=${event.iconUri}\niconAspectRatio=${event.iconAspectRatio}"
            is Event.CardFocused ->
                "CardFocused\nlightColor=${event.lightColor}, darkColor=${event.darkColor}"
            is Event.NowPlayingChanged ->
                "NowPlayingChanged\ntitle=${event.title}, isPlaying=${event.isPlaying}"
            is Event.LauncherIdleModeChanged ->
                "LauncherIdleModeChanged\nisIdle=${event.isIdle}"
            is Event.TimeElapsed -> "TimeElapsed"
            null -> "null event"
        }
        return "[$time] вызов #$callNum\n$body"
    }

    private fun dimColor(color: Int): Int {
        val factor = (PreferencesManager.dynamicColorsBrightness / 100f).coerceIn(0.1f, 1f)
        if (factor >= 0.999f) return color
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1] * (0.6f + 0.4f * factor) // a bit less saturated as it gets darker
        hsv[2] = hsv[2] * factor
        return android.graphics.Color.HSVToColor(android.graphics.Color.alpha(color), hsv)
    }

    fun fileUriExists(context: Context, fileUri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(fileUri)?.close() // Try opening the URI
            true // If no exception, file exists
        } catch (_: Exception) {
            false // If an exception occurs, file does not exist
        }
    }

    override fun onCreate() {
        super.onCreate()
        PreferencesManager.init(this)

        val currentTime = System.currentTimeMillis()
        val oneDayInMillis = 24 * 60 * 60 * 1000L
        if ((currentTime - PreferencesManager.lastCacheClean) > oneDayInMillis) {
            println("Cleaning Cache once in 24hrs")
            cleanExpiredCache(this)
            PreferencesManager.lastCacheClean = currentTime
        }

        println("Service created")
    }

    override fun onDestroy() {
        super.onDestroy()

        // When the service is destroyed, check if the screen is off to detect sleep states.
        // If it is off, we should reset the wallpaper
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            println("Service destroyed while screen is OFF. Assuming Sleep or Power off. Reset Last Wallpaper.")
            PreferencesManager.lastWallpaper = ""
        } else {
            println("Service destroyed while screen is ON (likely app switch).")
        }
    }

    override fun onBind(intent: Intent): IBinder {
        // Return the interface.
        return binder
    }

    private val binder = object : IWallpaperProviderService.Stub() {
        override fun getWallpapers(event: Event?): List<Wallpaper> {

            // Debug bookkeeping, so we can diagnose what Projectivy is actually sending us from
            // the Settings screen alone, without ADB. See KINOWATCH_FORK_README.md.
            PreferencesManager.getWallpapersCallCount += 1
            PreferencesManager.lastEventDebug = describeEventForDebug(event)
            if (event is Event.ProgramCardFocused) {
                PreferencesManager.programCardCount += 1
                PreferencesManager.lastProgramCardDebug = describeEventForDebug(event)
            }

            // Check if Projectivy was restarted since the last call:
            val currentPid = getCallingPid()
            val lastPid = PreferencesManager.lastCallingPid
            if (lastPid != currentPid) {
                // If the app was restarted, we can't be sure that it remembers the the last wallpaper
                // we sent. So we always send it in that case.
                PreferencesManager.lastWallpaper = ""
                // After a full device reboot, the first call to set a larger video wallpaper sometimes fails.
                // So we always send videos for the first few calls after Projectivy was started.
                // (accepting that this is also triggered on every app restart, but reboots are more likely for a luncher I guess)
                PreferencesManager.videoForcedUpdateCount = 2

                PreferencesManager.lastCallingPid = currentPid
            }

            return when (event) {
                // When the focused card changes (app icons)
                is Event.CardFocused -> {
                    fallbackWallpaper(event)
                }

                // When the focused "program" card changes (e.g. Kinowatch's "Я смотрю" row)
                is Event.ProgramCardFocused -> {
                    // Use the poster/backdrop URI exactly as the source app (Kinowatch) published
                    // it into Android TV's PreviewProgram/WatchNext data. No title search, no
                    // third-party matching - if this is null or fails to load, we fall back to
                    // the generic background rather than ever guessing a different title's art.
                    event.iconUri?.let { iconUri ->
                        val wallpaperUri = buildLocalLandscapeWallpaper(iconUri)

                        if (wallpaperUri != null) {
                            updateLastWallpaperSent(wallpaperUri)
                            PreferencesManager.lastProgramCardDebug =
                                describeEventForDebug(event) + "\nИСТОЧНИК КАРТИНКИ: $lastImageSource"
                            println("Movie Art: Sending local 16:9 wallpaper: $wallpaperUri")

                            return listOf(
                                Wallpaper(
                                    uri = wallpaperUri,
                                    type = WallpaperType.IMAGE,
                                    displayMode = tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode.STRETCH,
                                    title = event.title,
                                    source = iconUri
                                )
                            )
                        }

                        println("Movie Art: Failed to create local wallpaper from $iconUri")
                    }

                    fallbackWallpaper(event)
                }
                // It's unexpected that we receive any other kind of event, but in case we do we ignore it.
                // Returning an empty list won't change the currently displayed wallpaper.
                else -> emptyList()
            }
        }

        /**
         * Builds the local 16:9 wallpaper for a Kinowatch card.
         *
         * Kinowatch's own item model has a "Posters" object with big/medium/small/wide variants
         * (confirmed from the APK: Posters(big=, medium=, small=, wide=)). The PreviewProgram gives
         * us the "big" (portrait) URL, e.g. .../poster/item/big/126307.jpg. The horizontal image is
         * the "wide" variant of the SAME item id on the SAME CDN - we derive it by swapping the size
         * segment (this URL pattern is inferred from the "big" URL, not read from code, so it is
         * always verified by actually downloading it).
         *
         * Order: 1) cached wide image, 2) cached portrait fallback, 3) download wide,
         * 4) download portrait and letterbox it on black 16:9 (previous, known-working behavior).
         * The image is always the exact item's own art - never a search match.
         */
        private fun buildLocalLandscapeWallpaper(sourceUrl: String): String? {
            return try {
                val safeName = sourceUrl
                    .substringAfterLast('/')
                    .substringBefore('?')
                    .ifBlank { "poster" }
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")

                val hash = java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(sourceUrl.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                    .take(16)

                val wideFile = getCacheFile(that, "kinowatch_wide_${hash}_$safeName.jpg")
                // NOTE: deliberately NOT the old "kinowatch_16x9_" name. Vertical-poster images cached by the
                // previous build would otherwise be found here first, and "wide" would never be tried
                // for titles that were already viewed.
                val posterFile = getCacheFile(that, "kinowatch_fb_${hash}_$safeName.jpg")

                // 1) Wide image already cached
                if (wideFile.exists() && wideFile.length() >= 1024) {
                    lastImageSource = "wide (из кэша)"
                    return exposeFileToOtherApps(that, wideFile).toString()
                }
                // 2) Wide previously unavailable, portrait fallback already cached
                if (posterFile.exists() && posterFile.length() >= 1024) {
                    lastImageSource = "вертикальный постер (wide недоступен, из кэша)"
                    return exposeFileToOtherApps(that, posterFile).toString()
                }

                val width = 1920
                val height = 1080

                // 3) Try the horizontal "wide" image of the same item
                val wideUrl = Regex("/(big|medium|small)/(\\d+\\.[A-Za-z0-9]+)(\\?.*)?$")
                    .replace(sourceUrl) { "/wide/${it.groupValues[2]}" }
                    .takeIf { it != sourceUrl }
                if (wideUrl != null) {
                    val wide = downloadBitmap(wideUrl)
                    if (wide != null) {
                        try {
                            if (wide.width > wide.height) {
                                val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(output)
                                canvas.drawColor(android.graphics.Color.BLACK)
                                // "cover": fill the whole 16:9 frame, cropping the overflow evenly
                                val scale = maxOf(
                                    width.toFloat() / wide.width.toFloat(),
                                    height.toFloat() / wide.height.toFloat()
                                )
                                val srcW = width / scale
                                val srcH = height / scale
                                val srcLeft = (wide.width - srcW) / 2f
                                val srcTop = (wide.height - srcH) / 2f
                                val src = android.graphics.Rect(
                                    srcLeft.toInt(), srcTop.toInt(),
                                    (srcLeft + srcW).toInt(), (srcTop + srcH).toInt()
                                )
                                val dst = android.graphics.Rect(0, 0, width, height)
                                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                                canvas.drawBitmap(wide, src, dst, paint)
                                saveBitmapToFile(output, wideFile)
                                output.recycle()
                            } else {
                                println("Movie Art: wide image is not landscape ($wideUrl), ignoring")
                            }
                        } finally {
                            wide.recycle()
                        }
                        if (wideFile.exists() && wideFile.length() >= 1024) {
                            lastImageSource = "wide: $wideUrl"
                            return exposeFileToOtherApps(that, wideFile).toString()
                        }
                    } else {
                        println("Movie Art: wide image not available: $wideUrl")
                    }
                }

                // 4) Fallback: the portrait poster from Kinowatch, centered on black 16:9
                val poster = downloadBitmap(sourceUrl) ?: return null
                try {
                    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(output)
                    canvas.drawColor(android.graphics.Color.BLACK)

                    val scale = minOf(
                        width.toFloat() / poster.width.toFloat(),
                        height.toFloat() / poster.height.toFloat()
                    )
                    val drawWidth = poster.width * scale
                    val drawHeight = poster.height * scale
                    val left = (width - drawWidth) / 2f
                    val top = (height - drawHeight) / 2f
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(
                        poster,
                        null,
                        android.graphics.RectF(left, top, left + drawWidth, top + drawHeight),
                        paint
                    )
                    saveBitmapToFile(output, posterFile)
                    output.recycle()
                } finally {
                    poster.recycle()
                }

                if (!posterFile.exists() || posterFile.length() < 1024) return null
                lastImageSource = "вертикальный постер (wide не найден)"
                exposeFileToOtherApps(that, posterFile).toString()
            } catch (e: Exception) {
                println("Movie Art: buildLocalLandscapeWallpaper failed: ${e.message}")
                e.printStackTrace()
                null
            }
        }

        private fun updateLastWallpaperSent(uri: String) {
            PreferencesManager.lastWallpaper = uri
        }

        override fun getPreferences(): String {
            return PreferencesManager.export()
        }

        override fun setPreferences(params: String) {
            PreferencesManager.import(params)
        }

        fun getDrawableUri(drawableId: Int): Uri {
            return Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(resources.getResourcePackageName(drawableId))
                .appendPath(resources.getResourceTypeName(drawableId))
                .appendPath(resources.getResourceEntryName(drawableId))
                .build()
        }

        private fun isVideoFile(fileName: String): Boolean {
            val extension = MimeTypeMap.getFileExtensionFromUrl(fileName)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            return mimeType?.startsWith("video/") == true
        }

        private fun fallbackWallpaper(
            event: Event
        ): List<Wallpaper> {

            // Check if custom background is enabled and exists
            if (PreferencesManager.fallbackBackground == PreferencesManager.FallbackBackground.CustomBackground
                && PreferencesManager.customFallbackBackgroundName != null) {
                val fileName = PreferencesManager.customFallbackBackgroundName!!
                val customBgFile = BackgroundPickerHelper.getCustomBackgroundFile(that, fileName)
                if (customBgFile.exists()) {
                    try {
                        val shareableUri = exposeFileToOtherApps(that, customBgFile).toString()
                        val type: @WallpaperType Int =
                            if (isVideoFile(fileName)) WallpaperType.VIDEO else WallpaperType.IMAGE

                        // Don't Resend wallpaper if it's a VIDEO that hasn't changed.
                        if (type == WallpaperType.VIDEO && PreferencesManager.lastWallpaper == shareableUri) {
                            if (PreferencesManager.videoForcedUpdateCount > 0) {
                                PreferencesManager.videoForcedUpdateCount--
                                println("Force update count: ${PreferencesManager.videoForcedUpdateCount}")
                            } else {
                                // To prevent videos from restarting on every card change we return empty list,
                                // if we return a custom video multiples in a row
                                println("Returning emptyList to prevent video from getting restarted")
                                return emptyList()
                            }
                        }

                        updateLastWallpaperSent(shareableUri)
                        println("Returning wallpaper")
                        return listOf(
                            Wallpaper(shareableUri, type)
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // Fall through to color extraction
                    }
                }
            }

            // Fallback to dynamic colors
            if (event is Event.CardFocused) {
                try {
                    // Projectivy's colors are very bright; dim and slightly mute them so the result
                    // is close to the launcher's own dynamic colors (user setting, default 60%).
                    val lightColor = dimColor(event.lightColor)
                    val darkColor = dimColor(event.darkColor)
                    val file = getCacheFile(
                        that,
                        "gradient_${lightColor}_${darkColor}.json"
                    )
                    if (!fileUriExists(that, Uri.fromFile(file))) {
                        val lottieEditor =
                            LottieEditorRegex(that, getDrawableUri(R.raw.two_color_gradient))
                        lottieEditor
                            .load()
                            .replaceGradientColors(
                                positions = listOf(0f, 1f), // Positions
                                colors = listOf(lightColor, darkColor) // Colors
                            )
                            .save(Uri.fromFile(file))
                    }
                    val shareableUri = exposeFileToOtherApps(that, file).toString()
                    updateLastWallpaperSent(shareableUri)
                    return listOf(
                        Wallpaper(shareableUri, WallpaperType.LOTTIE),
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Final fallback: return default gradient
            val defaultUri = getDrawableUri(R.raw.gradient).toString()
            updateLastWallpaperSent(defaultUri)
            return listOf(
                Wallpaper(defaultUri, WallpaperType.LOTTIE)
            )
        }

    }
}
