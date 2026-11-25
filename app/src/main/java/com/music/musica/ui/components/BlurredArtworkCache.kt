package com.music.musica.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.musica.core.model.upsizeArtworkUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val blurredArtworkLru = LruCache<String, ImageBitmap>(96)

/**
 * Loads a tiny 36x36 artwork copy once off the UI thread, applies a fast 2-pass CPU box blur,
 * and caches the resulting [ImageBitmap]. Drawing the cached bitmap costs 0 GPU blur passes
 * per frame during LazyColumn / LazyRow scrolling (eliminating live Modifier.blur jank).
 */
@Composable
fun rememberBlurredArtwork(url: String?, radius: Int = 4): ImageBitmap? {
    val context = LocalPlatformContext.current
    val cacheKey = remember(url, radius) {
        url?.takeIf { it.isNotBlank() }?.let { "${upsizeArtworkUrl(it, 72)}#$radius" }
    }
    var bitmap by remember(cacheKey) {
        mutableStateOf(cacheKey?.let { blurredArtworkLru.get(it) })
    }

    LaunchedEffect(cacheKey) {
        if (cacheKey == null || url.isNullOrBlank()) return@LaunchedEffect
        blurredArtworkLru.get(cacheKey)?.let {
            bitmap = it
            return@LaunchedEffect
        }
        val computed = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(upsizeArtworkUrl(url, 72))
                    .size(36)
                    .allowHardware(false)
                    .build()
                val result = SingletonImageLoader.get(context).execute(request)
                val src = (result as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                withContext(Dispatchers.Default) {
                    fastBoxBlur(src, radius).asImageBitmap()
                }
            }.getOrNull()
        }
        if (computed != null) {
            blurredArtworkLru.put(cacheKey, computed)
            bitmap = computed
        }
    }
    return bitmap
}

/**
 * 2-pass separable box blur over a small ARGB bitmap (~0.15ms on 36x36).
 */
internal fun fastBoxBlur(source: Bitmap, radius: Int): Bitmap {
    val w = source.width.coerceAtLeast(1)
    val h = source.height.coerceAtLeast(1)
    val r = radius.coerceIn(1, 8)
    val srcPixels = IntArray(w * h)
    val copy = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
    copy.getPixels(srcPixels, 0, w, 0, 0, w, h)
    if (copy !== source) copy.recycle()

    val temp = IntArray(w * h)
    val out = IntArray(w * h)
    repeat(2) { pass ->
        val input = if (pass == 0) srcPixels else out
        // Horizontal pass
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                var a = 0; var rd = 0; var g = 0; var b = 0; var count = 0
                val x0 = (x - r).coerceAtLeast(0)
                val x1 = (x + r).coerceAtMost(w - 1)
                for (ix in x0..x1) {
                    val p = input[rowOff + ix]
                    a += (p ushr 24) and 0xFF
                    rd += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF
                    b += p and 0xFF
                    count++
                }
                temp[rowOff + x] = ((a / count) shl 24) or ((rd / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
        // Vertical pass
        for (x in 0 until w) {
            for (y in 0 until h) {
                var a = 0; var rd = 0; var g = 0; var b = 0; var count = 0
                val y0 = (y - r).coerceAtLeast(0)
                val y1 = (y + r).coerceAtMost(h - 1)
                for (iy in y0..y1) {
                    val p = temp[iy * w + x]
                    a += (p ushr 24) and 0xFF
                    rd += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF
                    b += p and 0xFF
                    count++
                }
                out[y * w + x] = ((a / count) shl 24) or ((rd / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}
