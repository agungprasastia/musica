package com.music.musica.ui.player

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.music.musica.core.model.Song
import java.io.File
import java.io.FileOutputStream

data class LyricsShareLine(
    val primaryText: String,
    val secondaryText: String? = null,
    val isGap: Boolean = false,
)

enum class LyricsShareFormat {
    STORY,
    CARD,
}

/**
 * Pure Android Canvas renderer for high-resolution Musica lyric quote cards (`CARD` adaptive
 * 1080px and `STORY` 1080×1920 9:16 with frosted glass card), plus MediaStore Gallery saving
 * and FileProvider PNG sharing.
 */
object LyricsShareCardRenderer {

    const val SHARE_CHAR_BUDGET = 250
    private const val CARD_WIDTH = 1080
    private const val STORY_HEIGHT = 1920
    private const val STORY_PANEL_WIDTH = 780f
    private const val STORY_PANEL_MAX_HEIGHT = 1280f
    private const val STORY_RADIUS = 44f
    private const val CARD_PADDING_H = 88f
    private const val CARD_MIN_HEIGHT = 960
    private const val CARD_MAX_HEIGHT = 1920

    private val CARD_FONT_LADDER = floatArrayOf(88f, 80f, 72f, 64f, 56f, 50f, 44f, 38f, 32f)
    private val STORY_FONT_LADDER = floatArrayOf(60f, 54f, 48f, 42f, 36f, 32f)
    private const val SUB_FONT_FLOOR = 24f

    fun buildPickedLines(
        lines: List<com.music.musica.data.lyrics.LyricLine>,
        subLines: List<com.music.musica.data.lyrics.LyricLine>?,
        selectedIndices: Set<Int>,
    ): List<LyricsShareLine> {
        val sorted = selectedIndices.sorted().filter { it in lines.indices && !lines[it].isGap }
        if (sorted.isEmpty()) return emptyList()
        val out = mutableListOf<LyricsShareLine>()
        for (i in sorted.indices) {
            val idx = sorted[i]
            if (i > 0) {
                val prevIdx = sorted[i - 1]
                val hasSkippedTextLine = ((prevIdx + 1) until idx).any { !lines[it].isGap && lines[it].text.isNotBlank() }
                if (hasSkippedTextLine) {
                    out += LyricsShareLine(primaryText = "(...)", isGap = true)
                }
            }
            val lead = lines[idx].text.trim()
            val sub = subLines?.getOrNull(idx)?.text?.trim()?.takeIf { it.isNotBlank() && !it.equals(lead, ignoreCase = true) }
            out += LyricsShareLine(primaryText = lead, secondaryText = sub)
        }
        return out
    }

    fun charCountOf(
        lines: List<com.music.musica.data.lyrics.LyricLine>,
        selectedIndices: Set<Int>,
    ): Int = selectedIndices
        .filter { it in lines.indices && !lines[it].isGap }
        .sumOf { lines[it].text.trim().length }

    fun render(
        song: Song,
        pickedLines: List<LyricsShareLine>,
        format: LyricsShareFormat,
        showLogo: Boolean,
        backdropBitmap: Bitmap?,
        coverBitmap: Bitmap?,
        fallbackAccentArgb: Int = 0xFF28182E.toInt(),
    ): Bitmap {
        val safeBackdrop = backdropBitmap?.toSoftwareArgb()
        val safeCover = coverBitmap?.toSoftwareArgb()
        return when (format) {
            LyricsShareFormat.STORY -> renderStory(song, pickedLines, showLogo, safeBackdrop, safeCover, fallbackAccentArgb)
            LyricsShareFormat.CARD -> renderCard(song, pickedLines, showLogo, safeBackdrop, safeCover, fallbackAccentArgb)
        }
    }

    private fun renderCard(
        song: Song,
        pickedLines: List<LyricsShareLine>,
        showLogo: Boolean,
        backdrop: Bitmap?,
        cover: Bitmap?,
        fallbackAccent: Int,
    ): Bitmap {
        val textWidth = (CARD_WIDTH - CARD_PADDING_H * 2f).toInt()
        val headerSpace = if (showLogo) 136f else 88f
        val footerSpace = 210f
        val maxBodyHeight = CARD_MAX_HEIGHT - headerSpace - footerSpace - 40f

        val (layouts, bodyHeight) = fitLyricsBlock(pickedLines, textWidth, maxBodyHeight, CARD_FONT_LADDER)
        val totalHeight = (headerSpace + bodyHeight + footerSpace + 56f)
            .toInt()
            .coerceIn(CARD_MIN_HEIGHT, CARD_MAX_HEIGHT)

        val out = createBitmap(CARD_WIDTH, totalHeight)
        val canvas = Canvas(out)
        drawBackdrop(canvas, CARD_WIDTH.toFloat(), totalHeight.toFloat(), backdrop, fallbackAccent)

        // Subtle atmospheric readability gradient
        val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, totalHeight.toFloat(),
                intArrayOf(0x38000000, 0x26000000, 0x72000000),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, CARD_WIDTH.toFloat(), totalHeight.toFloat(), scrimPaint)

        if (showLogo) {
            drawMusicaEmblem(canvas, cx = CARD_WIDTH - CARD_PADDING_H - 26f, cy = 92f, size = 48f, color = 0xE6FFFFFF.toInt())
        }

        // Center lyric lines vertically between header and footer
        val availTop = headerSpace
        val availBottom = totalHeight - footerSpace
        var cursorY = availTop + ((availBottom - availTop - bodyHeight) / 2f).coerceAtLeast(0f)
        for (entry in layouts) {
            canvas.withTranslation(CARD_PADDING_H, cursorY) {
                entry.layout.draw(this)
            }
            cursorY += entry.layout.height + entry.spacingAfter
        }

        drawFooter(
            canvas = canvas,
            song = song,
            cover = cover,
            left = CARD_PADDING_H,
            bottom = totalHeight - 76f,
            maxWidth = textWidth.toFloat(),
            coverSize = 112f,
            titleSize = 36f,
            artistSize = 28f,
        )
        return out
    }

    private fun renderStory(
        song: Song,
        pickedLines: List<LyricsShareLine>,
        showLogo: Boolean,
        backdrop: Bitmap?,
        cover: Bitmap?,
        fallbackAccent: Int,
    ): Bitmap {
        val out = createBitmap(CARD_WIDTH, STORY_HEIGHT)
        val canvas = Canvas(out)
        drawBackdrop(canvas, CARD_WIDTH.toFloat(), STORY_HEIGHT.toFloat(), backdrop, fallbackAccent)

        // Outer ambient dim
        val bgScrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, STORY_HEIGHT.toFloat(),
                intArrayOf(0x40000000, 0x24000000, 0x66000000),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, CARD_WIDTH.toFloat(), STORY_HEIGHT.toFloat(), bgScrim)

        val panelPadH = 58f
        val panelPadTop = 64f
        val footerHeight = 148f
        val textWidth = (STORY_PANEL_WIDTH - panelPadH * 2f).toInt()
        val maxBodyHeight = STORY_PANEL_MAX_HEIGHT - panelPadTop - footerHeight - 52f

        val (layouts, bodyHeight) = fitLyricsBlock(pickedLines, textWidth, maxBodyHeight, STORY_FONT_LADDER)
        val panelHeight = (panelPadTop + bodyHeight + 52f + footerHeight).coerceIn(520f, STORY_PANEL_MAX_HEIGHT)
        val panelLeft = (CARD_WIDTH - STORY_PANEL_WIDTH) / 2f
        val panelTop = (STORY_HEIGHT - panelHeight) / 2f
        val panelRect = RectF(panelLeft, panelTop, panelLeft + STORY_PANEL_WIDTH, panelTop + panelHeight)

        // Frosted elevated story card
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55000000
            setShadowLayer(42f, 0f, 20f, 0x66000000)
        }
        canvas.drawRoundRect(panelRect, STORY_RADIUS, STORY_RADIUS, shadowPaint)

        val panelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                panelLeft, panelTop, panelLeft, panelRect.bottom,
                0x6E18181E, 0x99121218.toInt(),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(panelRect, STORY_RADIUS, STORY_RADIUS, panelFill)

        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            shader = LinearGradient(
                panelLeft, panelTop, panelLeft, panelRect.bottom,
                0x52FFFFFF, 0x18FFFFFF,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(panelRect, STORY_RADIUS, STORY_RADIUS, rimPaint)

        var cursorY = panelTop + panelPadTop
        for (entry in layouts) {
            canvas.withTranslation(panelLeft + panelPadH, cursorY) {
                entry.layout.draw(this)
            }
            cursorY += entry.layout.height + entry.spacingAfter
        }

        // Subtle divider above story credit footer
        val divY = panelRect.bottom - footerHeight + 12f
        canvas.drawLine(
            panelLeft + panelPadH,
            divY,
            panelRect.right - panelPadH,
            divY,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x24FFFFFF
                strokeWidth = 1.5f
            },
        )

        val logoReserve = if (showLogo) 60f else 0f
        drawFooter(
            canvas = canvas,
            song = song,
            cover = cover,
            left = panelLeft + panelPadH,
            bottom = panelRect.bottom - 38f,
            maxWidth = textWidth - logoReserve,
            coverSize = 86f,
            titleSize = 29f,
            artistSize = 23f,
        )

        if (showLogo) {
            drawMusicaEmblem(
                canvas = canvas,
                cx = panelRect.right - panelPadH - 22f,
                cy = panelRect.bottom - 38f - 43f,
                size = 42f,
                color = 0xD9FFFFFF.toInt(),
            )
        }

        return out
    }

    private data class LaidOutEntry(
        val layout: StaticLayout,
        val spacingAfter: Float,
    )

    private fun fitLyricsBlock(
        pickedLines: List<LyricsShareLine>,
        widthPx: Int,
        maxHeightPx: Float,
        ladder: FloatArray,
    ): Pair<List<LaidOutEntry>, Float> {
        var bestLayouts = emptyList<LaidOutEntry>()
        var bestHeight = 0f

        for (fontSize in ladder) {
            val primaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = fontSize
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = -0.02f
            }
            val gapPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x80FFFFFF.toInt()
                textSize = (fontSize * 0.65f).coerceAtLeast(SUB_FONT_FLOOR)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xB8FFFFFF.toInt()
                textSize = (fontSize * 0.62f).coerceAtLeast(SUB_FONT_FLOOR)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }

            val entries = mutableListOf<LaidOutEntry>()
            var totalH = 0f
            val lineGap = fontSize * 0.42f
            val subGap = fontSize * 0.14f

            pickedLines.forEachIndexed { idx, line ->
                val isLast = idx == pickedLines.lastIndex
                val hasSub = !line.isGap && !line.secondaryText.isNullOrBlank()
                val pLayout = buildStaticLayout(line.primaryText, if (line.isGap) gapPaint else primaryPaint, widthPx)
                val pSpace = when {
                    hasSub -> subGap
                    isLast -> 0f
                    else -> lineGap
                }
                entries += LaidOutEntry(pLayout, pSpace)
                totalH += pLayout.height + pSpace

                if (hasSub) {
                    val sLayout = buildStaticLayout(line.secondaryText, subPaint, widthPx)
                    val sSpace = if (isLast) 0f else lineGap
                    entries += LaidOutEntry(sLayout, sSpace)
                    totalH += sLayout.height + sSpace
                }
            }

            bestLayouts = entries
            bestHeight = totalH
            if (totalH <= maxHeightPx) break
        }
        return bestLayouts to bestHeight
    }

    private fun buildStaticLayout(text: String, paint: TextPaint, widthPx: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.14f)
            .setIncludePad(false)
            .build()

    private fun drawBackdrop(
        canvas: Canvas,
        width: Float,
        height: Float,
        backdrop: Bitmap?,
        fallbackAccent: Int,
    ) {
        if (backdrop != null && backdrop.width > 0 && backdrop.height > 0) {
            val scale = maxOf(width / backdrop.width, height / backdrop.height)
            val drawW = backdrop.width * scale
            val drawH = backdrop.height * scale
            val left = (width - drawW) / 2f
            val top = (height - drawH) / 2f
            canvas.drawBitmap(
                backdrop,
                null,
                RectF(left, top, left + drawW, top + drawH),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        } else {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, width, height,
                    fallbackAccent, 0xFF0E0E14.toInt(),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, width, height, bgPaint)
        }
    }

    private fun drawFooter(
        canvas: Canvas,
        song: Song,
        cover: Bitmap?,
        left: Float,
        bottom: Float,
        maxWidth: Float,
        coverSize: Float,
        titleSize: Float,
        artistSize: Float,
    ) {
        val top = bottom - coverSize
        val coverRect = RectF(left, top, left + coverSize, bottom)
        val radius = coverSize * 0.16f
        val clipPath = Path().apply { addRoundRect(coverRect, radius, radius, Path.Direction.CW) }
        if (cover != null && cover.width > 0 && cover.height > 0) {
            canvas.withClip(clipPath) {
                val scale = maxOf(coverSize / cover.width, coverSize / cover.height)
                val dw = cover.width * scale
                val dh = cover.height * scale
                drawBitmap(
                    cover,
                    null,
                    RectF(
                        coverRect.centerX() - dw / 2f,
                        coverRect.centerY() - dh / 2f,
                        coverRect.centerX() + dw / 2f,
                        coverRect.centerY() + dh / 2f,
                    ),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
            }
        } else {
            canvas.drawRoundRect(coverRect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
        }
        canvas.drawRoundRect(
            coverRect,
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = 0x30FFFFFF
            },
        )

        val textLeft = left + coverSize + 24f
        val textMaxW = (maxWidth - coverSize - 24f).coerceAtLeast(80f)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = titleSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xC4FFFFFF.toInt()
            textSize = artistSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val titleEllipsized = ellipsizeSingleLine(song.title, titlePaint, textMaxW)
        val artistEllipsized = ellipsizeSingleLine(song.artistText, artistPaint, textMaxW)
        val centerY = (top + bottom) / 2f
        canvas.drawText(titleEllipsized, textLeft, centerY - 6f, titlePaint)
        canvas.drawText(artistEllipsized, textLeft, centerY + artistSize + 4f, artistPaint)
    }

    /** Draws Musica's official 3-ribbon + rhythm dot emblem matching `MusicaBrandEmblem`. */
    private fun drawMusicaEmblem(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val ox = cx - size / 2f
        val oy = cy - size / 2f
        val box = 44f
        fun px(vx: Float): Float = ox + (vx - 32.15f) / box * size
        fun py(vy: Float): Float = oy + (vy - 32.0f) / box * size

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = (7.8f / box) * size
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawLine(px(36.5f), py(68.9f), px(43.8f), py(48.9f), linePaint)
        canvas.drawLine(px(49.8f), py(70.9f), px(61.4f), py(39.1f), linePaint)
        canvas.drawLine(px(64.5f), py(68.9f), px(71.8f), py(48.9f), linePaint)
        canvas.drawCircle(px(48.1f), py(37.1f), (3.9f / box) * size, dotPaint)
    }

    private fun ellipsizeSingleLine(text: String, paint: TextPaint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellW = paint.measureText(ellipsis)
        val count = paint.breakText(text, true, (maxWidth - ellW).coerceAtLeast(0f), null)
        return text.take(count).trimEnd() + ellipsis
    }

    private fun Bitmap.toSoftwareArgb(): Bitmap =
        if (config == Bitmap.Config.HARDWARE) {
            copy(Bitmap.Config.ARGB_8888, false)
        } else {
            this
        }

    // ---- Save to Gallery & Share via FileProvider ---------------------------

    fun saveToGallery(context: Context, bitmap: Bitmap, songTitle: String): Boolean = runCatching {
        val slug = songTitle.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-').take(36).ifBlank { "track" }
        val fileName = "musica-lyrics-$slug-${System.currentTimeMillis() % 100_000}.png"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Musica")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return@runCatching false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Musica")
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, fileName)
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        }
    }.getOrDefault(false)

    fun sharePng(context: Context, bitmap: Bitmap, songTitle: String): Boolean = runCatching {
        val dir = File(context.cacheDir, "shared")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "musica-lyrics-share.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, songTitle)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, songTitle))
        true
    }.getOrDefault(false)
}
