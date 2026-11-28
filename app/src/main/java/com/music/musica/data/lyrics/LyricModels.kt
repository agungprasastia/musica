package com.music.musica.data.lyrics

import androidx.compose.runtime.Immutable
import com.music.musica.core.model.LyricsSourceId
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.min

/**
 * Character range `[startChar, endChar)` inside a parent [LyricWord.text] timed to `[startMs, endMs]`.
 */
@Immutable
@Serializable
data class LyricSyllable(
    val startMs: Long,
    val endMs: Long,
    val startChar: Int,
    val endChar: Int,
) {
    companion object {
        /**
         * Validates, sorts, and snaps syllable bounds to `[0, length]`, bridging untimed
         * punctuation or apostrophes onto their adjacent syllable. Returns empty if fewer
         * than 2 distinct syllables exist (a single-syllable word animates as a whole word).
         */
        fun normalized(parts: List<LyricSyllable>, length: Int): List<LyricSyllable> {
            if (parts.size < 2 || length <= 0) return emptyList()
            val cleaned = parts
                .filter { it.endChar > it.startChar && it.startChar in 0 until length }
                .map {
                    it.copy(
                        endMs = it.endMs.coerceAtLeast(it.startMs),
                        endChar = it.endChar.coerceAtMost(length),
                    )
                }
                .sortedBy { it.startChar }
            if (cleaned.size < 2) return emptyList()

            val merged = ArrayList<LyricSyllable>(cleaned.size)
            var cursor = 0
            for (i in cleaned.indices) {
                val cur = cleaned[i]
                val start = if (i == 0) 0 else cur.startChar.coerceAtLeast(cursor)
                val end = if (i == cleaned.lastIndex) length else cur.endChar.coerceAtLeast(start)
                if (end > start) {
                    if (merged.isNotEmpty() && start > cursor) {
                        val prev = merged.removeAt(merged.lastIndex)
                        merged += prev.copy(endChar = start)
                    }
                    merged += cur.copy(startChar = start, endChar = end)
                    cursor = end
                }
            }
            return if (merged.size >= 2) merged else emptyList()
        }
    }
}

@Immutable
@Serializable
enum class LyricAlignment {
    START,
    END,
}

@Immutable
@Serializable
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val trailingSpace: Boolean = true,
    val syllables: List<LyricSyllable> = emptyList(),
) {
    /**
     * Exact fractional character count revealed within this word at [positionMs].
     * Piece-wise linear across syllables so inter-syllable pauses hold steady on boundaries.
     */
    fun charsSungAt(positionMs: Long): Float {
        if (text.isEmpty()) return 0f
        if (positionMs <= startMs) return 0f
        if (positionMs >= endMs) return text.length.toFloat()

        if (syllables.isNotEmpty()) {
            var revealed = 0f
            for (syl in syllables) {
                when {
                    positionMs >= syl.endMs -> revealed = syl.endChar.toFloat()
                    positionMs <= syl.startMs -> return revealed
                    else -> {
                        val dur = (syl.endMs - syl.startMs).coerceAtLeast(1L)
                        val frac = ((positionMs - syl.startMs).toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                        return syl.startChar + frac * (syl.endChar - syl.startChar)
                    }
                }
            }
            return revealed.coerceIn(0f, text.length.toFloat())
        }

        val dur = (endMs - startMs).coerceAtLeast(1L)
        val frac = ((positionMs - startMs).toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        return frac * text.length
    }

    /**
     * Whether this word qualifies for held-note letter-by-letter wave growth & bloom.
     * Restricted to 1..7 non-hyphenated Latin/Cyrillic/Greek words held long enough to feel musical.
     */
    fun canGrow(): Boolean {
        val len = text.length
        if (len !in 1..GROW_MAX_CHARS) return false
        if (text.contains('-')) return false
        for (ch in text) {
            val code = ch.code
            if (code in 0x4E00..0x9FFF || code in 0x3040..0x30FF || code in 0xAC00..0xD7AF) return false
            if (code in 0x0590..0x08FF) return false
        }
        val held = endMs - startMs
        return when (len) {
            1 -> held >= 1_100L
            2 -> held >= 1_360L
            3 -> held >= 1_500L
            4 -> held >= 1_050L
            else -> held >= 900L && held >= len * 200L
        }
    }

    /**
     * Strips characters matching [drop] (e.g. outer parentheses on background vocals) while
     * remapping syllable character ranges accurately.
     */
    fun withoutChars(drop: (Char) -> Boolean): LyricWord {
        if (text.none(drop)) return this
        val sb = StringBuilder(text.length)
        val map = IntArray(text.length + 1)
        for (i in text.indices) {
            map[i] = sb.length
            if (!drop(text[i])) sb.append(text[i])
        }
        map[text.length] = sb.length
        val newText = sb.toString()
        val remapped = if (syllables.isEmpty()) {
            emptyList()
        } else {
            LyricSyllable.normalized(
                syllables.map { s ->
                    s.copy(
                        startChar = map[s.startChar.coerceIn(0, text.length)],
                        endChar = map[s.endChar.coerceIn(0, text.length)],
                    )
                },
                newText.length,
            )
        }
        return copy(text = newText, syllables = remapped)
    }

    companion object {
        const val GROW_MAX_CHARS = 7
    }
}

/**
 * Precomputed per-letter wave peak parameters for a held word (`LyricWord.canGrow() == true`).
 */
@Immutable
data class GrowingWord(
    val wordIndex: Int,
    val textRange: IntRange,
    val startMs: Long,
    val endMs: Long,
    val scalePeaks: FloatArray,
    val shiftPeaks: FloatArray,
    val risePeaks: FloatArray,
    val bloomPeaks: FloatArray,
) {
    val restsAtMs: Long
        get() {
            val dur = (endMs - startMs).coerceAtLeast(1L).toFloat()
            val lastOffset = ((textRange.last - textRange.first).coerceAtLeast(0)) * GROW_STAGGER * dur
            return startMs + (lastOffset + GROW_SPAN * dur).toLong()
        }

    fun sampleInto(charIndexInWord: Int, positionMs: Long, out: CharGrowth): Boolean {
        val dur = (endMs - startMs).coerceAtLeast(1L).toFloat()
        val charStart = startMs + charIndexInWord * GROW_STAGGER * dur
        val span = GROW_SPAN * dur
        val t = (positionMs - charStart) / span
        if (t <= 0f) {
            out.scale = 1f; out.shiftEm = 0f; out.riseUnits = 0f; out.bloom = 0f
            return false
        }
        if (t >= 0.75f) {
            out.scale = 1f; out.shiftEm = 0f; out.riseUnits = GROW_REST; out.bloom = 0f
            return false
        }
        val peakScale = scalePeaks[charIndexInWord]
        val peakShift = shiftPeaks[charIndexInWord]
        val peakRise = risePeaks[charIndexInWord]
        val peakBloom = bloomPeaks[charIndexInWord]
        when {
            t < 0.25f -> {
                val u = smoothstep(t / 0.25f)
                out.scale = 1f + (peakScale - 1f) * u
                out.shiftEm = peakShift * u
                out.riseUnits = peakRise * u
                out.bloom = peakBloom * u
            }
            t < 0.30f -> {
                out.scale = peakScale
                out.shiftEm = peakShift
                out.riseUnits = peakRise
                out.bloom = peakBloom
            }
            else -> {
                val u = smoothstep((t - 0.30f) / 0.45f)
                out.scale = peakScale + (1f - peakScale) * u
                out.shiftEm = peakShift * (1f - u)
                out.riseUnits = peakRise + (GROW_REST - peakRise) * u
                out.bloom = peakBloom * (1f - u)
            }
        }
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GrowingWord) return false
        return wordIndex == other.wordIndex && textRange == other.textRange &&
            startMs == other.startMs && endMs == other.endMs
    }

    override fun hashCode(): Int {
        var r = wordIndex
        r = 31 * r + textRange.hashCode()
        r = 31 * r + startMs.hashCode()
        r = 31 * r + endMs.hashCode()
        return r
    }

    companion object {
        const val GROW_STAGGER = 0.09f
        const val GROW_SPAN = 1.5f
        const val GROW_REST = 1f
        const val GROW_HEADROOM = 3f

        fun build(wordIndex: Int, word: LyricWord, span: IntRange): GrowingWord? {
            if (!word.canGrow()) return null
            val len = span.last - span.first + 1
            if (len <= 0) return null
            val held = (word.endMs - word.startMs).coerceAtLeast(1L).toFloat()
            val linear = ((held - 400f) / 2_600f).coerceIn(0f, 1f)
            val earned = linear * linear * linear

            val decayRate = (
                (if (len > 5) ((len - 5) * 0.2f).coerceAtMost(0.4f) else 0f) +
                    (if (held < 1_200f) (if (len > 4) 0.3f else 0.1f) else 0f)
                ).coerceAtMost(0.7f)

            val base = if (len <= 3) 0.05f else 0.04f
            val spread = when {
                len <= 3 -> 0.85f
                len >= 6 -> 1.1f
                else -> 1f
            }

            val scales = FloatArray(len)
            val shifts = FloatArray(len)
            val rises = FloatArray(len)
            val blooms = FloatArray(len)
            for (i in 0 until len) {
                val frac = if (len == 1) 0.5f else i.toFloat() / (len - 1).toFloat()
                val damp = (1f - decayRate * frac).coerceAtLeast(0.3f)
                val reach = earned * damp
                val scale = (1f + base + reach * 0.08f) * 0.98f
                val centre = (i + 0.5f) / len.toFloat()
                scales[i] = scale
                shifts[i] = (centre - 0.5f) * 2f * (scale - 1f) * (25f / 34f) * 0.98f
                rises[i] = ((scale - 1f) / 0.1f) * (held / 2_000f).coerceIn(0.3f, 1f)
                blooms[i] = (0.35f + reach * 0.45f) * min(1.1f, held / 1_500f) * spread
            }
            return GrowingWord(
                wordIndex = wordIndex,
                textRange = span,
                startMs = word.startMs,
                endMs = word.endMs,
                scalePeaks = scales,
                shiftPeaks = shifts,
                risePeaks = rises,
                bloomPeaks = blooms,
            )
        }
    }
}

class CharGrowth(
    var scale: Float = 1f,
    var shiftEm: Float = 0f,
    var riseUnits: Float = 0f,
    var bloom: Float = 0f,
)

@Immutable
@Serializable
data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val isBackgroundVocal: Boolean = false,
    val sungUntilMs: Long? = null,
    val background: LyricLine? = null,
    val alignment: LyricAlignment = LyricAlignment.START,
    @Transient val timingSource: LyricLine? = null,
) {
    val isGap: Boolean get() = text.isEmpty()
    val isWordSynced: Boolean get() = words.isNotEmpty()
    val hasKnownEnd: Boolean get() = words.isNotEmpty() || sungUntilMs != null

    val leadEndMs: Long
        get() = words.lastOrNull()?.endMs ?: sungUntilMs ?: endMs.coerceAtLeast(startMs)

    val effectiveEndMs: Long
        get() = maxOf(leadEndMs, background?.effectiveEndMs ?: leadEndMs)

    @Transient
    val wordSpans: List<IntRange> = computeWordSpans(text, words)

    @Transient
    val sweepSpans: List<IntRange> = computeSweepSpans(wordSpans, words)

    @Transient
    val growingWords: List<GrowingWord> = if (timingSource != null || words.isEmpty()) {
        emptyList()
    } else {
        words.mapIndexedNotNull { idx, word ->
            val span = wordSpans.getOrNull(idx) ?: return@mapIndexedNotNull null
            GrowingWord.build(idx, word, span)
        }
    }

    @Transient
    val animatesFromMs: Long = if (timingSource != null) {
        timingSource.animatesFromMs
    } else {
        val firstWord = words.firstOrNull()?.startMs ?: startMs
        minOf(startMs, firstWord)
    }

    @Transient
    val animatesUntilMs: Long = if (timingSource != null) {
        timingSource.animatesUntilMs
    } else {
        val lastWordTail = words.lastOrNull()?.endMs?.plus(RISE_MS.toLong()) ?: effectiveEndMs
        val maxGrowTail = growingWords.maxOfOrNull { it.restsAtMs } ?: effectiveEndMs
        maxOf(effectiveEndMs, lastWordTail, maxGrowTail)
    }

    /**
     * Exact fractional character position revealed along [text] at [positionMs].
     * When [timingSource] is present (translated/romanized line), projects original vocal
     * progress linearly across the translated text length so pauses and holds match the singer.
     */
    fun revealedChars(positionMs: Long): Float {
        if (text.isEmpty()) return 0f
        val source = timingSource
        if (source != null && source.text.isNotEmpty()) {
            val ratio = (source.revealedChars(positionMs) / source.text.length.toFloat()).coerceIn(0f, 1f)
            return ratio * text.length
        }
        if (words.isEmpty()) {
            if (positionMs < startMs) return 0f
            val end = leadEndMs
            if (end <= startMs || positionMs >= end) return text.length.toFloat()
            return ((positionMs - startMs).toFloat() / (end - startMs).toFloat()).coerceIn(0f, 1f) * text.length
        }

        val spans = wordSpans
        if (spans.isEmpty()) return 0f
        val firstStart = words.first().startMs
        if (positionMs <= firstStart) return 0f

        for (i in words.indices) {
            val w = words[i]
            val span = spans.getOrNull(i) ?: continue
            if (positionMs < w.startMs) {
                val prevSpan = spans.getOrNull(i - 1) ?: return 0f
                val prevEndMs = words[i - 1].endMs
                val gapMs = (w.startMs - prevEndMs).coerceAtLeast(1L)
                val frac = ((positionMs - prevEndMs).toFloat() / gapMs.toFloat()).coerceIn(0f, 1f)
                val gapStartChar = prevSpan.last + 1
                val gapEndChar = span.first
                return gapStartChar + frac * (gapEndChar - gapStartChar)
            }
            if (positionMs <= w.endMs) {
                return span.first + w.charsSungAt(positionMs)
            }
        }
        return text.length.toFloat()
    }

    /**
     * Normalized vertical lift `[0f..1f]` for word at [wordIndex] at [positionMs].
     * Scales amplitude by hold duration (`LIFT_FLOOR..1f`) and eases smoothly up & down.
     */
    fun wordLift(wordIndex: Int, positionMs: Long): Float {
        val word = words.getOrNull(wordIndex) ?: return 0f
        if (positionMs <= word.startMs) return 0f
        val held = (word.endMs - word.startMs).coerceAtLeast(1L).toFloat()
        val amplitude = LIFT_FLOOR + (1f - LIFT_FLOOR) *
            smoothstep(((held - LIFT_QUICK_MS) / (LIFT_HELD_MS - LIFT_QUICK_MS)).coerceIn(0f, 1f))
        val riseDur = held.coerceIn(RISE_MIN_MS, RISE_MS)
        return when {
            positionMs < word.endMs -> {
                val up = ((positionMs - word.startMs).toFloat() / riseDur).coerceIn(0f, 1f)
                amplitude * smoothstep(up)
            }
            else -> {
                val settle = ((positionMs - word.endMs).toFloat() / RISE_MS).coerceIn(0f, 1f)
                amplitude * (1f - smoothstep(settle))
            }
        }
    }

    /**
     * Returns a display copy stripped of outer `(` / `)` brackets (used when rendering
     * [background] vocal lines cleanly without parenthetical clutter).
     */
    fun withoutOuterParentheses(): LyricLine {
        val trimmed = text.trim()
        val hasOuter = trimmed.length >= 2 && trimmed.startsWith('(') && trimmed.endsWith(')')
        if (!hasOuter) return this
        val strippedText = trimmed.substring(1, trimmed.length - 1).trim()
        val strippedWords = words.mapIndexedNotNull { idx, word ->
            val cleaned = when {
                idx == 0 && idx == words.lastIndex -> word.withoutChars { it == '(' || it == ')' }
                idx == 0 -> {
                    if (word.text.startsWith('(')) word.withoutChars { it == '(' } else word
                }
                idx == words.lastIndex -> {
                    if (word.text.endsWith(')')) word.withoutChars { it == ')' } else word
                }
                else -> word
            }
            cleaned.takeIf { it.text.isNotEmpty() }
        }
        return copy(text = strippedText, words = strippedWords)
    }

    companion object {
        const val RISE_MIN_MS = 250f
        const val RISE_MS = 700f
        const val LIFT_FLOOR = 0.25f
        const val LIFT_QUICK_MS = 150f
        const val LIFT_HELD_MS = 900f

        private fun computeWordSpans(text: String, words: List<LyricWord>): List<IntRange> {
            if (text.isEmpty() || words.isEmpty()) return emptyList()
            val spans = ArrayList<IntRange>(words.size)
            var cursor = 0
            for (word in words) {
                if (word.text.isEmpty()) continue
                val found = text.indexOf(word.text, startIndex = cursor)
                if (found >= 0) {
                    spans += found until (found + word.text.length)
                    cursor = found + word.text.length
                } else {
                    val safeStart = cursor.coerceAtMost(text.length - 1)
                    val safeEnd = (safeStart + word.text.length).coerceAtMost(text.length)
                    if (safeEnd > safeStart) {
                        spans += safeStart until safeEnd
                        cursor = safeEnd
                    }
                }
            }
            return spans
        }

        private fun computeSweepSpans(wordSpans: List<IntRange>, words: List<LyricWord>): List<IntRange> {
            if (wordSpans.isEmpty()) return emptyList()
            val out = ArrayList<IntRange>(wordSpans.size * 2)
            for (i in wordSpans.indices) {
                val wSpan = wordSpans[i]
                val w = words.getOrNull(i)
                if (w != null && w.syllables.isNotEmpty()) {
                    for (syl in w.syllables) {
                        val s = (wSpan.first + syl.startChar).coerceIn(wSpan.first, wSpan.last)
                        val e = (wSpan.first + syl.endChar - 1).coerceIn(s, wSpan.last)
                        out += s..e
                    }
                } else {
                    out += wSpan
                }
            }
            return out
        }
    }
}

internal fun smoothstep(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

@Immutable
data class LyricsResult(
    val source: LyricsSourceId,
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val isWordSynced: Boolean,
    val offsetMs: Long = 0L,
)

enum class LyricsProviderStatus {
    IDLE,
    FETCHING,
    FOUND,
    NOT_FOUND,
    NOT_FETCHED,
}

enum class LyricsDisplayMode {
    ORIGINAL,
    ROMANIZED,
    TRANSLATED,
}

data class LyricsQuery(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val isrc: String? = null,
)

interface LyricsProvider {
    val sourceId: LyricsSourceId
    suspend fun fetch(query: LyricsQuery): LyricsResult?
}
