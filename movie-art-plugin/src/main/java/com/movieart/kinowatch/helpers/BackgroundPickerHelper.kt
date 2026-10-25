package com.movieart.kinowatch.helpers

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

object BackgroundPickerHelper {

    /**
     * Get the file where custom background is stored
     */
    fun getCustomBackgroundFile(context: Context, fileName: String): File {
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        return File(cacheDir, fileName)
    }

    /**
     * Copy an image or video chosen in a file manager / gallery into our own cache.
     *
     * File managers (Solid Explorer and others) are inconsistent about the MIME type they report
     * (often null or application/octet-stream) and about names (paths with ':' and '/'), so the
     * file is copied first and its real type is then detected from its first bytes.
     */
    fun copyBackgroundFromUri(context: Context, sourceUri: Uri): File? {
        var temp: File? = null
        return try {
            val resolver = context.contentResolver
            val input: InputStream = openInput(resolver, sourceUri) ?: return null

            val tmp = getCustomBackgroundFile(context, "custom_bg_tmp_${System.currentTimeMillis()}")
            temp = tmp
            input.use { src ->
                FileOutputStream(tmp).use { out -> src.copyTo(out) }
            }
            if (tmp.length() == 0L) {
                tmp.delete()
                return null
            }

            val extension = detectExtension(
                header = readHeader(tmp),
                mimeType = try { resolver.getType(sourceUri) } catch (_: Exception) { null },
                displayName = queryDisplayName(resolver, sourceUri) ?: sourceUri.lastPathSegment
            )
            val target = getCustomBackgroundFile(context, "custom_bg_${System.currentTimeMillis()}.$extension")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target
        } catch (e: Exception) {
            e.printStackTrace()
            temp?.delete()
            null
        }
    }

    private fun openInput(resolver: ContentResolver, uri: Uri): InputStream? {
        return if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { FileInputStream(File(it)) }
        } else {
            resolver.openInputStream(uri)
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readHeader(file: File): ByteArray {
        val buf = ByteArray(16)
        val n = FileInputStream(file).use { it.read(buf) }
        return if (n <= 0) ByteArray(0) else buf.copyOf(n)
    }

    private fun detectExtension(header: ByteArray, mimeType: String?, displayName: String?): String {
        fun b(i: Int): Int = if (i < header.size) header[i].toInt() and 0xFF else -1
        fun ascii(from: Int, text: String) = text.indices.all { b(from + it) == text[it].code }

        // 1) Real content type from the first bytes
        when {
            b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> return "jpg"
            b(0) == 0x89 && ascii(1, "PNG") -> return "png"
            ascii(0, "GIF8") -> return "gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> return "webp"
            ascii(0, "RIFF") && ascii(8, "AVI ") -> return "avi"
            ascii(4, "ftyp") -> return "mp4"
            b(0) == 0x1A && b(1) == 0x45 && b(2) == 0xDF && b(3) == 0xA3 -> return "mkv"
        }
        // 2) MIME type reported by the picker (ignore the generic ones)
        if (mimeType != null && mimeType != "application/octet-stream") {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                ?.takeIf { it.isNotBlank() && it != "bin" }
                ?.let { return it }
        }
        // 3) Extension in the file name
        val fromName = displayName?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
        if (fromName != null) return fromName
        // 4) Most likely an image
        return "jpg"
    }
}
