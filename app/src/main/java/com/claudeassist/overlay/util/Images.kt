package com.claudeassist.overlay.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object Images {

    private const val FILE_PROVIDER_AUTHORITY = "com.claudeassist.overlay.fileprovider"

    /** Downscales so the longest edge is at most [maxDim] px (no-op if smaller). */
    fun downscale(src: Bitmap, maxDim: Int = 1280): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxDim) return src
        val scale = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * Writes the bitmap into the FileProvider-backed cache dir and returns a
     * content:// URI another app can be granted read access to.
     */
    fun writeForShare(context: Context, bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "ask-${System.currentTimeMillis()}.png")
        file.outputStream().use { out ->
            downscale(bitmap).compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
    }

    /** Saves a small JPEG thumbnail for the history list; returns its path. */
    fun writeThumbnail(context: Context, bitmap: Bitmap): String {
        val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
        val file = File(dir, "thumb-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { out ->
            downscale(bitmap, maxDim = 320).compress(Bitmap.CompressFormat.JPEG, 75, out)
        }
        return file.absolutePath
    }

    /** Best-effort cleanup of share files older than an hour. */
    fun pruneShared(context: Context) {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000
        File(context.cacheDir, "shared").listFiles()
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }
}
