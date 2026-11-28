package com.music.musica.data.lyrics

import com.music.musica.core.network.HttpClients
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Full-featured lyrics parser & post-processor supporting:
 * - Apple Music TTML (word/syllable spans, `ttm:role="x-bg"` background vocals, `ttm:agent` duets)
 * - Standard LRC & Enhanced LRC A2 (`<mm:ss.xx>` word/syllable stamps, HTML entities, closing gaps)
 * - QQ QRC & NetEase YRC karaoke formats (`[lineStart,lineDur](start,dur)text`)
 * - Nested provider JSON envelopes & Genius plain text with `[Section]` headers
 * - Post-processing: CJK/Thai-aware syllable gluing, duet alternating alignment + mostly-right
 *   safeguard, trailing `(...)` background vocal extraction, `>= 4s` instrumental break insertion,
 *   and strict catalog title/artist/version query cleaning.
 */
object SyncedLyricsParser {

    data class TimedRun(
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    private val LINE_TAG_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG_REGEX = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>([^<]*)""")
    private val QRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)](.+)$""")
    private val QRC_PREFIX_TOKEN = Regex("""\((\d+),(\d+)(?:,\d+)?\)([^()\r\n]+)""")
    private val QRC_SUFFIX_TOKEN = Regex("""([^()\r\n]+)\((\d+),(\d+)(?:,\d+)?\)""")
    private val QRC_LYRIC_CONTENT_ATTR = Regex("""LyricContent="([^"]+)"""")

    private val BRACKET_FEAT_REGEX = Regex("""(?i)\s*[\[(](?:feat\.?|ft\.?|featuring|with)\s+[^)\]]+[)\]]""")
    private val TRAILING_FEAT_REGEX = Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring)\s+.+$""")
    private val PACKAGING_TAG_REGEX = Regex(
        """(?i)\s*[\[(](?:official\s*(?:music\s*)?(?:video|audio|visualizer|lyric\s*video|mv)?|music\s*video|lyric\s*video|audio|visualizer|mv|hd|hq|4k|full\s*song|explicit|clean)[\])]""",
    )
    private val TOPIC_SUFFIX_REGEX = Regex("""(?i)\s*-\s*topic$""")
    private val HTML_HEX_ENTITY = Regex("""&#x([0-9a-fA-F]{1,6});""")
    private val HTML_DEC_ENTITY = Regex("""&#(\d{1,7});""")
    private val CREDIT_LINE_REGEX = Regex("""^(?:作词|作曲|编曲|制作人|混音|母带|词|曲|演唱|歌手|lyricist|composer|arranger|producer|written\s+by|mixed\s+by|mastered\s+by)\s*[:：].+$""", RegexOption.IGNORE_CASE)
    private val INSTRUMENTAL_NOTICE_REGEX = Regex("""纯音乐.*欣赏|instrumental\s+only|no\s+lyrics\s+available""", RegexOption.IGNORE_CASE)
    private val SECTION_HEADER_REGEX = Regex("""^\[[^\]]{1,58}]$""")

    private val VERSION_WORDS = setOf(
        "live", "remix", "acoustic", "sped", "slowed", "nightcore", "reverb",
        "instrumental", "karaoke", "demo", "unplugged", "radio", "edit", "extended",
        "deluxe", "remastered", "remaster", "stripped", "piano", "orchestral", "cover",
    )

    const val MIN_GAP_MS = 4_000L
    private const val TAIL_WORD_MS = 800L
    private const val MOSTLY_RIGHT_RATIO = 0.85f

    // ---- Query Cleaning & Catalog Match Helpers -----------------------------

    fun cleanTitleForLyrics(raw: String): String {
        var s = raw.trim()
        s = BRACKET_FEAT_REGEX.replace(s, "")
        s = PACKAGING_TAG_REGEX.replace(s, "")
        s = TRAILING_FEAT_REGEX.replace(s, "")
        return s.replace(Regex("""\s{2,}"""), " ").trim()
    }

    fun cleanArtistForLyrics(raw: String): String =
        TOPIC_SUFFIX_REGEX.replace(raw.trim(), "").trim()

    fun normalizeCore(raw: String): String {
        val cleaned = cleanTitleForLyrics(raw)
            .replace(Regex("""[\[(][^)\]]*[)\]]"""), " ")
            .lowercase(Locale.ROOT)
        return Normalizer.normalize(cleaned, Normalizer.Form.NFD)
            .filter { it.isLetterOrDigit() }
    }

    fun extractVersionWords(raw: String): Set<String> {
        val tokens = raw.lowercase(Locale.ROOT)
            .split(Regex("""[^a-z0-9]+"""))
            .filter { it in VERSION_WORDS }
        return tokens.toSet()
    }

    fun splitArtistsNormalized(raw: String): Set<String> =
        raw.split(Regex("""(?i)\s*(?:,|&|/|;|\bx\b|\bfeat\.?\b|\bft\.?\b|\band\b)\s*"""))
            .map { Normalizer.normalize(it.lowercase(Locale.ROOT), Normalizer.Form.NFD).filter { ch -> ch.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
            .toSet()

    fun matchesCatalogCandidate(
        queryTitle: String,
        queryArtist: String,
        queryDurationMs: Long,
        candTitle: String,
        candArtist: String,
        candDurationMs: Long,
        maxDurationDeltaMs: Long = 3_000L,
    ): Boolean {
        val qCore = normalizeCore(queryTitle)
        val cCore = normalizeCore(candTitle)
        if (qCore.isEmpty() || qCore != cCore) return false
        if (extractVersionWords(queryTitle) != extractVersionWords(candTitle)) return false
        val qArtists = splitArtistsNormalized(cleanArtistForLyrics(queryArtist))
        val cArtists = splitArtistsNormalized(cleanArtistForLyrics(candArtist))
        if (qArtists.isNotEmpty() && cArtists.isNotEmpty()) {
            val shared = qArtists.any { qa -> cArtists.any { ca -> qa == ca || qa.contains(ca) || ca.contains(qa) } }
            if (!shared) return false
        }
        if (queryDurationMs > 0L && candDurationMs > 0L) {
            if (abs(queryDurationMs - candDurationMs) > maxDurationDeltaMs) return false
        }
        return true
    }

    // ---- Envelope Unwrapper -------------------------------------------------

    /**
     * Unwraps provider payloads (raw TTML, QRC/YRC, Enhanced LRC, standard LRC, or nested JSON
     * envelopes) into parsed [LyricLine]s.
     */
    fun parseProviderPayload(raw: String): List<LyricLine> {
        val stripped = stripBomAndFences(raw)
        if (stripped.isEmpty()) return emptyList()

        if (stripped.startsWith('{') || stripped.startsWith('[')) {
            val root = runCatching { HttpClients.json.parseToJsonElement(stripped) }.getOrNull()
            if (root != null) {
                val unwrapped = unwrapJsonPayload(root, depth = 0)
                if (unwrapped.isNotEmpty()) return unwrapped
            }
        }

        val unescaped = if (stripped.contains("&lt;tt", ignoreCase = true)) {
            decodeHtmlEntities(stripped)
        } else {
            stripped
        }

        if (unescaped.contains("<tt", ignoreCase = true) && unescaped.contains("<p", ignoreCase = true)) {
            val ttml = parseTtml(unescaped)
            if (ttml.isNotEmpty()) return ttml
        }
        if (unescaped.contains("LyricContent=") || QRC_LINE_REGEX.containsMatchIn(unescaped)) {
            val karaoke = parseKaraokeLrc(unescaped)
            if (karaoke.isNotEmpty()) return karaoke
        }
        if (LINE_TAG_REGEX.containsMatchIn(unescaped)) {
            val lrc = parseLrc(unescaped)
            if (lrc.isNotEmpty()) return lrc
        }
        return parsePlainText(unescaped)
    }

    private fun unwrapJsonPayload(element: JsonElement, depth: Int): List<LyricLine> {
        if (depth > 5) return emptyList()
        when (element) {
            is JsonPrimitive -> {
                val text = element.contentOrNull?.trim().orEmpty()
                if (text.isEmpty() || text.startsWith("http://") || text.startsWith("https://")) return emptyList()
                return parseProviderPayload(text)
            }
            is JsonObject -> {
                if ((element["isError"] as? JsonPrimitive)?.booleanOrNull == true) return emptyList()
                if ((element["ok"] as? JsonPrimitive)?.booleanOrNull == false) return emptyList()
                if ((element["success"] as? JsonPrimitive)?.booleanOrNull == false) return emptyList()
                val err = (element["error"] as? JsonPrimitive)?.contentOrNull
                if (!err.isNullOrBlank()) return emptyList()

                val priorityKeys = listOf(
                    "ttml", "ttmlContent", "richSyncLyrics", "syncedLyrics",
                    "lyrics", "lrc", "content", "data", "result", "response", "plainLyrics", "text",
                )
                for (key in priorityKeys) {
                    val child = element[key] ?: continue
                    val parsed = unwrapJsonPayload(child, depth + 1)
                    if (parsed.isNotEmpty()) return parsed
                }
            }
            is JsonArray -> {
                // Attempt structured line objects (`{ time/startTimeMs, text/words }`)
                val structured = element.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val text = (obj["text"] as? JsonPrimitive)?.contentOrNull
                        ?: (obj["words"] as? JsonPrimitive)?.contentOrNull
                        ?: (obj["line"] as? JsonPrimitive)?.contentOrNull
                        ?: return@mapNotNull null
                    val startMs = (obj["time"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                        ?: (obj["startTimeMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                        ?: 0L
                    val durMs = (obj["duration"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                    val endMs = (obj["endTimeMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                        ?: durMs?.let { startMs + it }
                    val clean = text.trim()
                    if (clean.isEmpty()) null
                    else LyricLine(
                        startMs = startMs,
                        endMs = (endMs ?: (startMs + 4_000L)).coerceAtLeast(startMs),
                        text = clean,
                        sungUntilMs = endMs?.takeIf { it > startMs },
                    )
                }
                if (structured.isNotEmpty()) {
                    return if (structured.any { it.startMs > 0L }) {
                        normalizeEndTimes(structured.sortedBy { it.startMs })
                    } else {
                        structured
                    }
                }
                for (item in element) {
                    val parsed = unwrapJsonPayload(item, depth + 1)
                    if (parsed.isNotEmpty()) return parsed
                }
            }
        }
        return emptyList()
    }

    // ---- LRC & Enhanced LRC A2 Parser ---------------------------------------

    fun parseLrc(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val parsed = mutableListOf<LyricLine>()
        val closingMarks = mutableListOf<Long>()

        raw.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach

            val lineMatches = LINE_TAG_REGEX.findAll(trimmed).toList()
            if (lineMatches.isEmpty()) return@forEach

            val contentStart = lineMatches.last().range.last + 1
            val rawBody = decodeHtmlEntities(trimmed.substring(contentStart)).trim()

            // Bare timestamp line acts as explicit end-of-phrase / instrumental gap marker
            if (rawBody.isEmpty()) {
                lineMatches.forEach { m ->
                    closingMarks += toMillis(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                }
                return@forEach
            }

            val words = parseEnhancedWords(rawBody)
            val cleanText = if (words.isNotEmpty()) {
                buildWordText(words)
            } else {
                rawBody
            }
            if (cleanText.isEmpty()) return@forEach

            lineMatches.forEach { m ->
                val startMs = toMillis(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                val lastWordEnd = words.lastOrNull()?.endMs
                parsed += LyricLine(
                    startMs = startMs,
                    endMs = (lastWordEnd ?: (startMs + 4_000L)).coerceAtLeast(startMs),
                    text = cleanText,
                    words = words,
                    sungUntilMs = lastWordEnd?.takeIf { it > startMs },
                )
            }
        }

        val sorted = parsed.sortedBy { it.startMs }.toMutableList()
        if (closingMarks.isNotEmpty() && sorted.isNotEmpty()) {
            for (mark in closingMarks) {
                val prevIdx = sorted.indexOfLast { it.startMs < mark }
                if (prevIdx >= 0) {
                    val prev = sorted[prevIdx]
                    val nextStart = sorted.getOrNull(prevIdx + 1)?.startMs
                    if (nextStart == null || mark <= nextStart) {
                        val currentEnd = prev.sungUntilMs ?: prev.words.lastOrNull()?.endMs
                        if (currentEnd == null || mark < currentEnd) {
                            sorted[prevIdx] = prev.copy(
                                endMs = mark.coerceAtLeast(prev.startMs),
                                sungUntilMs = mark.takeIf { it > prev.startMs },
                            )
                        }
                    }
                }
            }
        }
        return normalizeEndTimes(sorted)
    }

    // ---- QQ QRC / NetEase YRC Karaoke Parser --------------------------------

    fun parseKaraokeLrc(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val unwrapped = QRC_LYRIC_CONTENT_ATTR.find(raw)?.groupValues?.get(1)?.let(::decodeHtmlEntities) ?: raw
        val lines = mutableListOf<LyricLine>()

        unwrapped.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            val match = QRC_LINE_REGEX.matchEntire(trimmed) ?: return@forEach
            val lineStart = match.groupValues[1].toLongOrNull() ?: return@forEach
            val lineDur = match.groupValues[2].toLongOrNull() ?: 0L
            val body = decodeHtmlEntities(match.groupValues[3])
            val lineEnd = (lineStart + lineDur).coerceAtLeast(lineStart)

            val prefixMatches = QRC_PREFIX_TOKEN.findAll(body).toList()
            val suffixMatches = QRC_SUFFIX_TOKEN.findAll(body).toList()
            val prefixChars = prefixMatches.sumOf { it.groupValues[3].length }
            val suffixChars = suffixMatches.sumOf { it.groupValues[1].length }

            val runs = if (prefixChars >= suffixChars && prefixMatches.isNotEmpty()) {
                prefixMatches.mapNotNull { m ->
                    val s = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                    val d = m.groupValues[2].toLongOrNull() ?: 0L
                    val t = m.groupValues[3]
                    if (t.isEmpty()) null else TimedRun(s, (s + d).coerceAtLeast(s), t)
                }
            } else if (suffixMatches.isNotEmpty()) {
                suffixMatches.mapNotNull { m ->
                    val t = m.groupValues[1]
                    val s = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                    val d = m.groupValues[3].toLongOrNull() ?: 0L
                    if (t.isEmpty()) null else TimedRun(s, (s + d).coerceAtLeast(s), t)
                }
            } else {
                emptyList()
            }

            if (runs.isNotEmpty()) {
                val words = wordsFromRuns(runs, spacingIsExplicit = false)
                val text = buildWordText(words)
                if (text.isNotEmpty()) {
                    lines += LyricLine(
                        startMs = lineStart,
                        endMs = maxOf(lineEnd, words.lastOrNull()?.endMs ?: lineEnd),
                        text = text,
                        words = words,
                        sungUntilMs = maxOf(lineEnd, words.lastOrNull()?.endMs ?: lineEnd).takeIf { it > lineStart },
                    )
                }
            } else {
                val plain = body.trim()
                if (plain.isNotEmpty()) {
                    lines += LyricLine(
                        startMs = lineStart,
                        endMs = lineEnd.coerceAtLeast(lineStart),
                        text = plain,
                        sungUntilMs = lineEnd.takeIf { it > lineStart },
                    )
                }
            }
        }
        return normalizeEndTimes(lines.sortedBy { it.startMs })
    }

    // ---- Apple Music TTML Parser (Syllables + x-bg + Duet Agents) -----------

    fun parseTtml(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val doc = runCatching { Jsoup.parse(raw, "", Parser.xmlParser()) }.getOrNull() ?: return emptyList()

        // Map declared <ttm:agent xml:id="..." type="person|group|other">
        val agentTypes = HashMap<String, String>()
        doc.select("ttm|agent, agent").forEach { agent ->
            val id = agent.attrOrLocal("xml:id", "id").takeIf { it.isNotBlank() } ?: return@forEach
            val type = agent.attr("type").lowercase(Locale.ROOT).ifBlank { "person" }
            agentTypes[id] = type
        }

        val rawEntries = mutableListOf<Pair<String?, LyricLine>>()

        doc.select("p").forEach { p ->
            val lineBegin = parseTtmlTimestamp(p.attr("begin")) ?: return@forEach
            val explicitEnd = parseTtmlTimestamp(p.attr("end"))?.coerceAtLeast(lineBegin)
            val lineEnd = explicitEnd ?: (lineBegin + 4_000L)
            val pRole = p.attrOrLocal("ttm:role", "role").lowercase(Locale.ROOT)
            if (pRole == "x-translation" || pRole == "x-roman") return@forEach
            val isBgParagraph = pRole == "x-bg"
            val agentId = p.attrOrLocal("ttm:agent", "agent").takeIf { it.isNotBlank() }

            val leadRuns = mutableListOf<TimedRun>()
            val bgRuns = mutableListOf<TimedRun>()
            val bgPlainText = StringBuilder()

            fun collectNodes(parent: Element, inBg: Boolean) {
                for (child: Node in parent.childNodes()) {
                    when (child) {
                        is TextNode -> {
                            val text = child.wholeText
                            if (text.isBlank()) {
                                if (text.isNotEmpty()) {
                                    val target = if (inBg) bgRuns else leadRuns
                                    if (target.isNotEmpty() && !target.last().text.endsWith(' ')) {
                                        val last = target.removeAt(target.lastIndex)
                                        target += last.copy(text = last.text + " ")
                                    }
                                }
                            } else if (inBg) {
                                bgPlainText.append(text)
                            }
                        }
                        is Element -> {
                            if (!child.tagName().equals("span", ignoreCase = true)) {
                                collectNodes(child, inBg)
                                continue
                            }
                            val role = child.attrOrLocal("ttm:role", "role").lowercase(Locale.ROOT)
                            if (role == "x-translation" || role == "x-roman") continue
                            val childBg = inBg || role == "x-bg"
                            val hasNestedTimedSpan = child.select("span[begin]").any { it !== child }
                            val spanBegin = parseTtmlTimestamp(child.attr("begin"))
                            val spanEnd = parseTtmlTimestamp(child.attr("end"))
                            if (hasNestedTimedSpan) {
                                collectNodes(child, childBg)
                            } else if (spanBegin != null) {
                                val rawText = child.text()
                                if (rawText.isNotBlank()) {
                                    val target = if (childBg) bgRuns else leadRuns
                                    target += TimedRun(
                                        startMs = spanBegin,
                                        endMs = (spanEnd ?: spanBegin).coerceAtLeast(spanBegin),
                                        text = rawText,
                                    )
                                }
                            } else {
                                collectNodes(child, childBg)
                            }
                        }
                    }
                }
            }

            collectNodes(p, inBg = isBgParagraph)

            val leadWords = if (leadRuns.isNotEmpty()) wordsFromRuns(leadRuns, spacingIsExplicit = true) else emptyList()
            val bgWords = if (bgRuns.isNotEmpty()) wordsFromRuns(bgRuns, spacingIsExplicit = true) else emptyList()

            val bgLine = when {
                bgWords.isNotEmpty() -> {
                    val bgText = buildWordText(bgWords)
                    val bgStart = bgWords.first().startMs
                    val bgEnd = bgWords.last().endMs.coerceAtLeast(bgStart)
                    if (bgText.isNotEmpty()) {
                        LyricLine(
                            startMs = bgStart,
                            endMs = bgEnd,
                            text = bgText,
                            words = bgWords,
                            isBackgroundVocal = true,
                            sungUntilMs = bgEnd.takeIf { it > bgStart },
                        )
                    } else null
                }
                bgPlainText.toString().trim().isNotEmpty() -> {
                    LyricLine(
                        startMs = lineBegin,
                        endMs = lineEnd,
                        text = bgPlainText.toString().trim(),
                        isBackgroundVocal = true,
                        sungUntilMs = explicitEnd?.takeIf { it > lineBegin },
                    )
                }
                else -> null
            }

            if (isBgParagraph && leadWords.isEmpty()) {
                // Standalone x-bg <p> element
                val text = bgLine?.text ?: p.text().trim()
                if (text.isNotEmpty()) {
                    rawEntries += agentId to LyricLine(
                        startMs = bgLine?.startMs ?: lineBegin,
                        endMs = bgLine?.endMs ?: lineEnd,
                        text = text,
                        words = bgLine?.words.orEmpty(),
                        isBackgroundVocal = true,
                        sungUntilMs = explicitEnd?.takeIf { it > lineBegin },
                    )
                }
                return@forEach
            }

            val fullLeadText = if (leadWords.isNotEmpty()) {
                buildWordText(leadWords)
            } else {
                // Strip any x-bg/x-translation/x-roman text when falling back to plain <p>
                val clone = p.clone()
                clone.select("span").forEach { sp ->
                    val r = sp.attrOrLocal("ttm:role", "role").lowercase(Locale.ROOT)
                    if (r == "x-bg" || r == "x-translation" || r == "x-roman") sp.remove()
                }
                clone.text().trim()
            }

            if (fullLeadText.isNotEmpty()) {
                rawEntries += agentId to LyricLine(
                    startMs = lineBegin,
                    endMs = lineEnd,
                    text = fullLeadText,
                    words = leadWords,
                    isBackgroundVocal = false,
                    sungUntilMs = explicitEnd?.takeIf { it > lineBegin } ?: leadWords.lastOrNull()?.endMs,
                    background = bgLine,
                )
            }
        }

        val sorted = rawEntries.sortedBy { it.second.startMs }
        val alignments = assignDuetAlignments(
            agents = sorted.map { it.first },
            agentTypes = agentTypes,
        )
        val aligned = sorted.mapIndexed { idx, (_, line) ->
            val align = alignments.getOrElse(idx) { LyricAlignment.START }
            line.copy(
                alignment = align,
                background = line.background?.copy(alignment = align),
            )
        }
        return normalizeEndTimes(aligned)
    }

    // ---- Plain Text & Utility Helpers ---------------------------------------

    fun parsePlainText(raw: String): List<LyricLine> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { LyricLine(startMs = 0L, endMs = 0L, text = it) }
            .toList()

    /**
     * Glues adjacent [TimedRun]s into [LyricWord]s carrying per-run [LyricSyllable]s when
     * no whitespace separates them, keeping unspaced CJK/Kana/Thai runs individual.
     */
    fun wordsFromRuns(runs: List<TimedRun>, spacingIsExplicit: Boolean): List<LyricWord> {
        if (runs.isEmpty()) return emptyList()
        val anySpaceInLine = runs.any { it.text.any(Char::isWhitespace) }
        val shouldGlue = spacingIsExplicit || anySpaceInLine

        val out = mutableListOf<LyricWord>()
        val curText = StringBuilder()
        val curSyllables = mutableListOf<LyricSyllable>()
        var curStart = 0L
        var curEnd = 0L

        fun flush(trailingSpace: Boolean) {
            val wordStr = curText.toString().trim()
            if (wordStr.isNotEmpty()) {
                val syllables = LyricSyllable.normalized(curSyllables, wordStr.length)
                out += LyricWord(
                    startMs = curStart,
                    endMs = curEnd.coerceAtLeast(curStart),
                    text = wordStr,
                    trailingSpace = trailingSpace,
                    syllables = syllables,
                )
            }
            curText.clear()
            curSyllables.clear()
        }

        for (i in runs.indices) {
            val run = runs[i]
            val raw = run.text
            if (raw.isBlank()) {
                if (curText.isNotEmpty()) flush(trailingSpace = true)
                continue
            }
            val leadingSpace = raw.first().isWhitespace()
            val trailingSpace = raw.last().isWhitespace()
            val piece = raw.trim()
            if (piece.isEmpty()) continue

            val unspacedScript = isUnspacedScript(piece)
            if (leadingSpace && curText.isNotEmpty()) {
                flush(trailingSpace = true)
            }
            if ((!shouldGlue || unspacedScript) && curText.isNotEmpty()) {
                flush(trailingSpace = false)
            }

            val startChar = curText.length
            if (curText.isEmpty()) {
                curStart = run.startMs
                curEnd = run.endMs
            } else {
                curStart = minOf(curStart, run.startMs)
                curEnd = maxOf(curEnd, run.endMs)
            }
            curText.append(piece)
            curSyllables += LyricSyllable(
                startMs = run.startMs,
                endMs = run.endMs.coerceAtLeast(run.startMs),
                startChar = startChar,
                endChar = curText.length,
            )

            if (trailingSpace || !shouldGlue || unspacedScript) {
                val hasNext = i < runs.lastIndex
                flush(trailingSpace = hasNext && trailingSpace)
            }
        }
        if (curText.isNotEmpty()) flush(trailingSpace = false)

        // Ensure trailingSpace is false on the final word
        if (out.isNotEmpty() && out.last().trailingSpace) {
            out[out.lastIndex] = out.last().copy(trailingSpace = false)
        }
        return out
    }

    /**
     * Computes duet alignments: alternates side each time the active singer changes (supporting 3+
     * vocalists cleanly), keeps `group` agents on [LyricAlignment.START] without flipping the turn,
     * and flips all lines if `>= 85%` landed on [LyricAlignment.END] (single-voice `other` guard).
     */
    fun assignDuetAlignments(
        agents: List<String?>,
        agentTypes: Map<String, String> = emptyMap(),
    ): List<LyricAlignment> {
        if (agents.isEmpty()) return emptyList()
        val distinctNonGroup = agents.filterNotNull()
            .filter { agentTypes[it] != "group" && it != "v1000" }
            .distinct()
        if (distinctNonGroup.size <= 1) {
            return List(agents.size) { LyricAlignment.START }
        }

        val out = MutableList(agents.size) { LyricAlignment.START }
        var currentSide = LyricAlignment.START
        var lastSinger: String? = null
        var placed = 0
        var rightward = 0

        for (i in agents.indices) {
            val id = agents[i]
            if (id.isNullOrBlank()) {
                out[i] = currentSide
                continue
            }
            val type = agentTypes[id] ?: when {
                id == "v1000" -> "group"
                id == "v2000" -> "other"
                else -> "person"
            }
            if (type == "group") {
                out[i] = LyricAlignment.START
                continue
            }
            if (lastSinger == null) {
                currentSide = if (type == "other") LyricAlignment.END else LyricAlignment.START
                lastSinger = id
            } else if (id != lastSinger) {
                currentSide = if (currentSide == LyricAlignment.START) LyricAlignment.END else LyricAlignment.START
                lastSinger = id
            }
            out[i] = currentSide
            placed++
            if (currentSide == LyricAlignment.END) rightward++
        }

        if (placed > 0 && (rightward.toFloat() / placed.toFloat()) >= MOSTLY_RIGHT_RATIO) {
            return out.map { if (it == LyricAlignment.END) LyricAlignment.START else LyricAlignment.END }
        }
        return out
    }

    /**
     * Splits balanced trailing parenthetical vocals `(...)` (e.g. `"Hold on (hold on)"`) into
     * [LyricLine.background] when no structural `x-bg` is already present. Never splits a whole
     * parenthesized line or mid-word suffixes like `wait(ing)`.
     */
    fun withBackgroundVocals(lines: List<LyricLine>): List<LyricLine> =
        lines.map { line ->
            if (line.isGap || line.background != null || line.isBackgroundVocal) return@map line
            val t = line.text.trimEnd()
            if (!t.endsWith(')')) return@map line
            var depth = 0
            var openIdx = -1
            for (i in t.lastIndex downTo 0) {
                when (t[i]) {
                    ')' -> depth++
                    '(' -> {
                        depth--
                        if (depth == 0) {
                            openIdx = i
                            break
                        }
                    }
                }
            }
            if (openIdx <= 0) return@map line
            if (!t[openIdx - 1].isWhitespace()) return@map line
            val leadPart = t.substring(0, openIdx).trimEnd()
            val bgPart = t.substring(openIdx).trim()
            if (leadPart.isEmpty() || bgPart.length < 3 || !bgPart.any(Char::isLetterOrDigit)) {
                return@map line
            }

            if (line.words.isEmpty()) {
                val bgLine = LyricLine(
                    startMs = line.startMs,
                    endMs = line.endMs,
                    text = bgPart,
                    isBackgroundVocal = true,
                    sungUntilMs = line.sungUntilMs,
                    alignment = line.alignment,
                )
                return@map line.copy(text = leadPart, background = bgLine)
            }

            val splitWordIdx = line.wordSpans.indexOfFirst { it.first >= openIdx }
            if (splitWordIdx <= 0 || splitWordIdx >= line.words.size) return@map line
            val leadWords = line.words.subList(0, splitWordIdx).toMutableList()
            if (leadWords.isNotEmpty()) {
                leadWords[leadWords.lastIndex] = leadWords.last().copy(trailingSpace = false)
            }
            val bgWords = line.words.subList(splitWordIdx, line.words.size)
            val bgStart = bgWords.first().startMs
            val bgEnd = bgWords.last().endMs.coerceAtLeast(bgStart)
            val bgLine = LyricLine(
                startMs = bgStart,
                endMs = bgEnd,
                text = buildWordText(bgWords),
                words = bgWords,
                isBackgroundVocal = true,
                sungUntilMs = bgEnd.takeIf { it > bgStart },
                alignment = line.alignment,
            )
            val leadEnd = leadWords.lastOrNull()?.endMs ?: line.endMs
            line.copy(
                endMs = maxOf(leadEnd, bgEnd),
                text = buildWordText(leadWords),
                words = leadWords,
                sungUntilMs = leadEnd.takeIf { it > line.startMs },
                background = bgLine,
            )
        }

    /**
     * Inserts empty instrumental gap markers (`text = ""`) for `>= 4s` intros and mid-song silences
     * so the synced lyrics UI renders smooth accordion 3-dot countdowns.
     */
    fun withInstrumentalGaps(lines: List<LyricLine>): List<LyricLine> {
        val nonGaps = lines.filterNot { it.isGap }
        if (nonGaps.isEmpty()) return emptyList()
        if (nonGaps.none { it.startMs > 0L }) return nonGaps

        val out = ArrayList<LyricLine>(nonGaps.size + 4)
        val first = nonGaps.first()
        if (first.startMs >= MIN_GAP_MS) {
            out += LyricLine(startMs = 0L, endMs = first.startMs, text = "")
        }
        for (i in nonGaps.indices) {
            val cur = nonGaps[i]
            out += cur
            val next = nonGaps.getOrNull(i + 1) ?: continue
            val curEnd = cur.effectiveEndMs
            if (cur.hasKnownEnd && curEnd > cur.startMs && (next.startMs - curEnd) >= MIN_GAP_MS) {
                out += LyricLine(startMs = curEnd, endMs = next.startMs, text = "")
            }
        }
        return out
    }

    fun stripHeaderCredits(lines: List<LyricLine>): List<LyricLine> {
        if (lines.size <= 3) return lines
        val filtered = lines.filterIndexed { idx, line ->
            val nearEdges = idx < 12 || idx >= lines.size - 10
            !(nearEdges && CREDIT_LINE_REGEX.matches(line.text.trim()))
        }
        return filtered.ifEmpty { lines }
    }

    fun isInstrumentalNotice(lines: List<LyricLine>): Boolean {
        val nonEmpty = lines.filter { it.text.isNotBlank() }
        if (nonEmpty.isEmpty() || nonEmpty.size > 3) return false
        return nonEmpty.any { INSTRUMENTAL_NOTICE_REGEX.containsMatchIn(it.text) }
    }

    fun isSectionHeader(text: String): Boolean =
        text.length in 3..60 && SECTION_HEADER_REGEX.matches(text.trim())

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

    /**
     * Multi-row overlap focus: keeps every vocal row active simultaneously while still being sung
     * (`!isGap && hasKnownEnd && startMs <= pos < effectiveEndMs`), plus the latest started row.
     */
    fun activeLyricRows(lines: List<LyricLine>, effectivePosMs: Long): List<Int> {
        if (lines.isEmpty()) return emptyList()
        val latest = activeLineIndex(lines, effectivePosMs, offsetMs = 0L)
        if (latest < 0) return emptyList()

        val active = mutableListOf<Int>()
        val scanStart = (latest - 6).coerceAtLeast(0)
        for (i in scanStart..latest) {
            val line = lines[i]
            if (i == latest) {
                active += i
            } else if (!line.isGap && line.hasKnownEnd && line.startMs <= effectivePosMs && effectivePosMs < line.effectiveEndMs) {
                active += i
            }
        }
        return active
    }

    fun decodeHtmlEntities(raw: String): String {
        if (!raw.contains('&')) return raw
        var s = HTML_HEX_ENTITY.replace(raw) { m ->
            val cp = m.groupValues[1].toIntOrNull(16) ?: return@replace m.value
            if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
        }
        s = HTML_DEC_ENTITY.replace(s) { m ->
            val cp = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
        }
        return s
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    fun buildWordText(words: List<LyricWord>): String = buildString {
        words.forEachIndexed { idx, w ->
            append(w.text)
            if (w.trailingSpace && idx < words.lastIndex) append(' ')
        }
    }.trim()

    // ---- Internal Helpers ---------------------------------------------------

    private fun parseEnhancedWords(body: String): List<LyricWord> {
        val matches = WORD_TAG_REGEX.findAll(body).toList()
        if (matches.isEmpty()) return emptyList()
        val runs = mutableListOf<TimedRun>()
        for (i in matches.indices) {
            val m = matches[i]
            val start = toMillis(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            val nextStart = matches.getOrNull(i + 1)?.let { n ->
                toMillis(n.groupValues[1], n.groupValues[2], n.groupValues[3])
            } ?: (start + TAIL_WORD_MS)
            val text = m.groupValues[4]
            if (text.isNotBlank()) {
                runs += TimedRun(start, nextStart.coerceAtLeast(start), text)
            }
        }
        if (runs.isEmpty()) return emptyList()
        return wordsFromRuns(runs, spacingIsExplicit = false)
    }

    private fun normalizeEndTimes(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return emptyList()
        return lines.mapIndexed { idx, line ->
            val nextStart = lines.getOrNull(idx + 1)?.startMs
            val computedEnd = when {
                line.sungUntilMs != null -> line.sungUntilMs.coerceAtLeast(line.startMs)
                nextStart != null && nextStart > line.startMs -> minOf(line.endMs, nextStart)
                else -> line.endMs.coerceAtLeast(line.startMs)
            }
            val fixedWords = line.words.mapIndexed { wIdx, w ->
                val nextWordStart = line.words.getOrNull(wIdx + 1)?.startMs ?: computedEnd
                val wEnd = if (w.endMs <= w.startMs) nextWordStart.coerceAtLeast(w.startMs) else w.endMs
                w.copy(endMs = wEnd.coerceAtLeast(w.startMs))
            }
            line.copy(
                endMs = maxOf(computedEnd, line.background?.effectiveEndMs ?: computedEnd),
                words = fixedWords,
            )
        }
    }

    private fun Element.attrOrLocal(qualified: String, local: String): String =
        attr(qualified).ifBlank { attr(local) }

    private fun isUnspacedScript(text: String): Boolean = text.any { ch ->
        val c = ch.code
        c in 0x3000..0x30FF || c in 0x3400..0x9FFF || c in 0xFF00..0xFFEF || c in 0x0E00..0x0E7F
    }

    private fun stripBomAndFences(raw: String): String {
        var s = raw.removePrefix("\uFEFF").trim()
        if (s.startsWith("```")) {
            s = s.substringAfter('\n', "").substringBeforeLast("```").trim()
        }
        return s
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
