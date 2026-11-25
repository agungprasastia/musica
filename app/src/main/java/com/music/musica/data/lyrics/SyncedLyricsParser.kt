package com.music.musica.data.lyrics

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Clean-room parser for Standard LRC, Enhanced LRC (word timestamps), Apple Music TTML,
 * and plain-text lyrics with defensive timestamp normalization.
 */
object SyncedLyricsParser {

    private val LINE_TAG_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG_REGEX = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>([^<]*)""")

    fun parseLrc(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val parsed = mutableListOf<LyricLine>()

        raw.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach

            val lineMatches = LINE_TAG_REGEX.findAll(trimmed).toList()
            if (lineMatches.isEmpty()) return@forEach

            val contentStart = lineMatches.last().range.last + 1
            val body = trimmed.substring(contentStart).trim()
            if (body.isEmpty()) return@forEach

            val words = parseEnhancedWords(body)
            val cleanText = if (words.isNotEmpty()) {
                words.joinToString(" ") { it.text }.trim()
            } else {
                body
            }
            if (cleanText.isEmpty()) return@forEach

            lineMatches.forEach { m ->
                val startMs = toMillis(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                parsed += LyricLine(
                    startMs = startMs,
                    endMs = startMs + 4_000L,
                    text = cleanText,
                    words = words,
                )
            }
        }

        val sorted = parsed.sortedBy { it.startMs }
        return normalizeEndTimes(sorted)
    }

    fun parseTtml(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val doc = runCatching { Jsoup.parse(raw, "", Parser.xmlParser()) }.getOrNull() ?: return emptyList()
        val lines = mutableListOf<LyricLine>()

        doc.select("p").forEach { p ->
            val lineBegin = parseTtmlTimestamp(p.attr("begin")) ?: return@forEach
            val lineEnd = parseTtmlTimestamp(p.attr("end"))?.coerceAtLeast(lineBegin) ?: (lineBegin + 4_000L)
            val isBg = p.attr("ttm:role").equals("x-bg", ignoreCase = true)

            // Select only leaf <span begin="..."> elements so parent x-bg spans are not duplicated.
            val spans = p.select("span[begin]:not(:has(span[begin]))")
            val words = spans.mapIndexedNotNull { idx, span ->
                val rawText = span.text()
                val wordText = rawText.trim()
                if (wordText.isEmpty()) return@mapIndexedNotNull null
                val wStart = parseTtmlTimestamp(span.attr("begin")) ?: lineBegin
                val wEnd = (parseTtmlTimestamp(span.attr("end")) ?: wStart).coerceAtLeast(wStart)
                val nextSib = span.nextSibling()
                val hasFollowingWhitespace = rawText.lastOrNull()?.isWhitespace() == true ||
                    (nextSib is org.jsoup.nodes.TextNode && nextSib.wholeText.any { it.isWhitespace() })
                val isLast = idx == spans.lastIndex
                LyricWord(
                    startMs = wStart,
                    endMs = wEnd,
                    text = wordText,
                    trailingSpace = !isLast && hasFollowingWhitespace,
                )
            }

            val fullText = if (words.isNotEmpty()) {
                buildString {
                    words.forEach { w ->
                        append(w.text)
                        if (w.trailingSpace) append(' ')
                    }
                }.trim()
            } else {
                p.text().trim()
            }
            if (fullText.isNotEmpty()) {
                lines += LyricLine(
                    startMs = lineBegin,
                    endMs = lineEnd,
                    text = fullText,
                    words = words,
                    isBackgroundVocal = isBg,
                )
            }
        }

        return normalizeEndTimes(lines.sortedBy { it.startMs })
    }

    fun parsePlainText(raw: String): List<LyricLine> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { LyricLine(startMs = 0L, endMs = 0L, text = it) }
            .toList()

    fun activeLineIndex(lines: List<LyricLine>, positionMs: Long, offsetMs: Long = 0L): Int {
        if (lines.isEmpty()) return -1
        val effectivePos = positionMs + offsetMs
        if (effectivePos < lines.first().startMs) return -1

        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= effectivePos) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun parseEnhancedWords(body: String): List<LyricWord> {
        val matches = WORD_TAG_REGEX.findAll(body).toList()
        if (matches.isEmpty()) return emptyList()
        val raw = matches.mapNotNull { m ->
            val word = m.groupValues[4].trim()
            if (word.isEmpty()) return@mapNotNull null
            val start = toMillis(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            start to word
        }
        if (raw.isEmpty()) return emptyList()

        return raw.mapIndexed { idx, (start, text) ->
            val nextStart = raw.getOrNull(idx + 1)?.first ?: (start + 500L)
            LyricWord(startMs = start, endMs = nextStart.coerceAtLeast(start), text = text)
        }
    }

    private fun normalizeEndTimes(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return emptyList()
        return lines.mapIndexed { idx, line ->
            val nextStart = lines.getOrNull(idx + 1)?.startMs
            val computedEnd = when {
                nextStart != null && nextStart > line.startMs -> minOf(line.endMs, nextStart)
                else -> line.endMs.coerceAtLeast(line.startMs)
            }
            val fixedWords = line.words.mapIndexed { wIdx, w ->
                val nextWordStart = line.words.getOrNull(wIdx + 1)?.startMs ?: computedEnd
                val wEnd = if (w.endMs <= w.startMs) nextWordStart.coerceAtLeast(w.startMs) else w.endMs
                w.copy(endMs = wEnd.coerceAtLeast(w.startMs))
            }
            line.copy(endMs = computedEnd, words = fixedWords)
        }
    }

    private fun parseTtmlTimestamp(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (s.endsWith("ms")) return s.removeSuffix("ms").toDoubleOrNull()?.toLong()
        if (s.endsWith("s")) return (s.removeSuffix("s").toDoubleOrNull()?.times(1000.0))?.toLong()

        val parts = s.split(':')
        return when (parts.size) {
            2 -> {
                val min = parts[0].toLongOrNull() ?: return null
                val sec = parts[1].toDoubleOrNull() ?: return null
                min * 60_000L + (sec * 1000.0).toLong()
            }
            3 -> {
                val hrs = parts[0].toLongOrNull() ?: return null
                val min = parts[1].toLongOrNull() ?: return null
                val sec = parts[2].toDoubleOrNull() ?: return null
                hrs * 3_600_000L + min * 60_000L + (sec * 1000.0).toLong()
            }
            else -> s.toDoubleOrNull()?.times(1000.0)?.toLong()
        }
    }

    private fun toMillis(minStr: String, secStr: String, fracStr: String): Long {
        val min = minStr.toLongOrNull() ?: 0L
        val sec = secStr.toLongOrNull() ?: 0L
        val ms = when (fracStr.length) {
            0 -> 0L
            1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
            2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
            else -> fracStr.take(3).toLongOrNull() ?: 0L
        }
        return min * 60_000L + sec * 1000L + ms
    }
}
