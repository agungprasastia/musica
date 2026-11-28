package com.music.musica.data.lyrics

import com.music.musica.core.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class TranslationLanguage(
    val code: String,
    val curatedName: String? = null,
) {
    fun displayName(uiLocale: Locale = Locale.getDefault()): String {
        if (!curatedName.isNullOrBlank()) return curatedName
        val loc = Locale.forLanguageTag(code)
        val name = loc.getDisplayName(uiLocale).trim()
        return if (name.isNotBlank() && !name.equals(code, ignoreCase = true)) {
            name.replaceFirstChar { it.titlecase(uiLocale) }
        } else {
            code.uppercase(Locale.ROOT)
        }
    }
}

sealed interface LyricsTranslationOutcome {
    data class Success(val lines: List<LyricLine>, val detectedSourceLang: String? = null) : LyricsTranslationOutcome
    data class AlreadyInTargetLanguage(val targetDisplayName: String) : LyricsTranslationOutcome
    data object AlreadyRomanized : LyricsTranslationOutcome
    data class Failure(val reason: String) : LyricsTranslationOutcome
}

@Serializable
private data class CachedTranslationPayload(
    val version: Int = CACHE_VERSION,
    val slots: List<String>,
    val detectedSourceLang: String? = null,
) {
    companion object {
        const val CACHE_VERSION = 1
    }
}

/**
 * On-the-fly lyrics translation (133 languages) and romanization engine with:
 * - Private-use delimiter batching (`<= 3500` chars/batch, max 2 parallel requests)
 * - Automatic Genius `[Section]` header unwrapping/rewrapping
 * - Romanized-source repair via Google Input Tools before NMT retry
 * - Exact vocal sweep timing preservation (`LyricLine.timingSource`)
 * - 2-tier cache: 12-entry memory LRU + 2 MiB GZIP disk cache (`cacheDir/lyrics_translation_v1`)
 */
class LyricsTranslationEngine(
    private val cacheDir: File? = null,
    baseClient: OkHttpClient = HttpClients.okHttp,
    private val translateBatchTransport: (suspend (text: String, sourceLang: String, targetLang: String, dt: String) -> TranslateBatchResponse?)? = null,
    private val inputToolsTransport: (suspend (text: String, sourceLang: String) -> String?)? = null,
) {
    data class TranslateBatchResponse(
        val translatedText: String,
        val romanizedText: String,
        val detectedSourceLang: String?,
    )

    private val client: OkHttpClient = baseClient.newBuilder()
        .callTimeout(12, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val memoryCache: MutableMap<String, CachedTranslationPayload> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedTranslationPayload>(MEM_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedTranslationPayload>?): Boolean =
                size > MEM_CACHE_ENTRIES
        },
    )

    private val diskDir: File? = cacheDir?.let { File(it, DISK_CACHE_DIR_NAME) }

    suspend fun translate(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageCode: String,
        targetDisplayName: String,
    ): LyricsTranslationOutcome {
        val slots = flattenSlots(lines)
        if (slots.isEmpty()) return LyricsTranslationOutcome.Failure("No text to translate")

        val normalizedTarget = normalizeLanguageTag(targetLanguageCode)
        val cacheKey = buildCacheKey(trackId, "tr:$normalizedTarget", slots)

        readCache(cacheKey)?.let { cached ->
            if (cached.slots.size == slots.size) {
                if (isSameLanguage(cached.detectedSourceLang, normalizedTarget) &&
                    unchangedRatio(slots, cached.slots) >= SAME_LANG_UNCHANGED_RATIO
                ) {
                    return LyricsTranslationOutcome.AlreadyInTargetLanguage(targetDisplayName)
                }
                return LyricsTranslationOutcome.Success(
                    lines = rebuildLines(lines, cached.slots),
                    detectedSourceLang = cached.detectedSourceLang,
                )
            }
        }

        val initial = runBatched(slots, sourceLang = "auto", targetLang = normalizedTarget, romanizeOnly = false)
            ?: return LyricsTranslationOutcome.Failure("Translation request failed")

        var finalSlots = initial.slots
        val detected = initial.detectedSourceLang?.let(::normalizeLanguageTag)

        if (isSameLanguage(detected, normalizedTarget) && unchangedRatio(slots, finalSlots) >= SAME_LANG_UNCHANGED_RATIO) {
            writeCache(cacheKey, CachedTranslationPayload(slots = finalSlots, detectedSourceLang = detected))
            return LyricsTranslationOutcome.AlreadyInTargetLanguage(targetDisplayName)
        }

        // Romanized-source NMT repair (e.g., Romanized Hindi/Urdu/Punjabi -> native script -> re-translate)
        if (detected != null &&
            !isSameLanguage(detected, normalizedTarget) &&
            detected in INPUT_TOOLS_LANGUAGES &&
            isPredominantlyLatin(slots) &&
            unchangedRatio(slots, finalSlots) >= ROMANIZED_REPAIR_THRESHOLD
        ) {
            val nativeSlots = convertRomanizedToNative(slots, detected)
            if (nativeSlots != null && nativeSlots.size == slots.size) {
                val retried = runBatched(nativeSlots, sourceLang = detected, targetLang = normalizedTarget, romanizeOnly = false)
                if (retried != null && retried.slots.size == slots.size &&
                    unchangedRatio(slots, retried.slots) < unchangedRatio(slots, finalSlots)
                ) {
                    finalSlots = retried.slots
                }
            }
        }

        writeCache(cacheKey, CachedTranslationPayload(slots = finalSlots, detectedSourceLang = detected))
        return LyricsTranslationOutcome.Success(
            lines = rebuildLines(lines, finalSlots),
            detectedSourceLang = detected,
        )
    }

    suspend fun romanize(
        trackId: String,
        lines: List<LyricLine>,
    ): LyricsTranslationOutcome {
        val slots = flattenSlots(lines)
        if (slots.isEmpty()) return LyricsTranslationOutcome.Failure("No text to romanize")
        if (!hasNonLatinLetters(slots)) return LyricsTranslationOutcome.AlreadyRomanized

        val cacheKey = buildCacheKey(trackId, "rm", slots)
        readCache(cacheKey)?.let { cached ->
            if (cached.slots.size == slots.size) {
                return LyricsTranslationOutcome.Success(
                    lines = rebuildLines(lines, cached.slots),
                    detectedSourceLang = cached.detectedSourceLang,
                )
            }
        }

        val outcome = runBatched(slots, sourceLang = "auto", targetLang = "en", romanizeOnly = true)
            ?: return LyricsTranslationOutcome.Failure("Romanization request failed")

        writeCache(cacheKey, CachedTranslationPayload(slots = outcome.slots, detectedSourceLang = outcome.detectedSourceLang))
        return LyricsTranslationOutcome.Success(
            lines = rebuildLines(lines, outcome.slots),
            detectedSourceLang = outcome.detectedSourceLang,
        )
    }

    private data class BatchedOutcome(
        val slots: List<String>,
        val detectedSourceLang: String?,
    )

    private suspend fun runBatched(
        slots: List<String>,
        sourceLang: String,
        targetLang: String,
        romanizeOnly: Boolean,
    ): BatchedOutcome? = coroutineScope {
        val batches = partitionBatches(slots)
        val sem = Semaphore(MAX_PARALLEL_REQUESTS)
        val dt = if (romanizeOnly) "rm" else "t"

        val results = batches.map { batch ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val joined = batch.mapIndexed { idx, text ->
                        "$MARKER_OPEN${idx.toString().padStart(4, '0')}$MARKER_CLOSE$text"
                    }.joinToString("\n")
                    val resp = if (translateBatchTransport != null) {
                        translateBatchTransport.invoke(joined, sourceLang, targetLang, dt)
                    } else {
                        executeGoogleTranslate(joined, sourceLang, targetLang, dt)
                    } ?: return@withPermit null
                    val rawOut = if (romanizeOnly) {
                        resp.romanizedText.ifBlank { resp.translatedText }
                    } else {
                        resp.translatedText
                    }
                    val parsed = splitDelimitedSlots(rawOut, batch)
                    parsed to resp.detectedSourceLang
                }
            }
        }.awaitAll()

        if (results.any { it == null }) return@coroutineScope null
        val allSlots = results.flatMap { it!!.first }
        val lang = results.firstNotNullOfOrNull { it?.second }
        BatchedOutcome(slots = allSlots, detectedSourceLang = lang)
    }

    private suspend fun convertRomanizedToNative(slots: List<String>, sourceLang: String): List<String>? = coroutineScope {
        val batches = partitionBatches(slots)
        val sem = Semaphore(MAX_PARALLEL_REQUESTS)
        val converted = batches.map { batch ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val joined = batch.mapIndexed { idx, text ->
                        "$MARKER_OPEN${idx.toString().padStart(4, '0')}$MARKER_CLOSE$text"
                    }.joinToString("\n")
                    val resp = if (inputToolsTransport != null) {
                        inputToolsTransport.invoke(joined, sourceLang)
                    } else {
                        executeGoogleInputTools(joined, sourceLang)
                    } ?: return@withPermit null
                    splitDelimitedSlots(resp, batch)
                }
            }
        }.awaitAll()
        if (converted.any { it == null }) null else converted.flatMap { it!! }
    }

    private fun executeGoogleTranslate(
        text: String,
        sourceLang: String,
        targetLang: String,
        dt: String,
    ): TranslateBatchResponse? {
        val url = "https://translate.googleapis.com/translate_a/single" +
            "?client=dict-chrome-ex&sl=${LyricsHttp.enc(sourceLang)}&tl=${LyricsHttp.enc(targetLang)}&dt=$dt"
        val form = FormBody.Builder()
            .add("q", text)
            .build()
        val req = Request.Builder()
            .url(url)
            .post(form)
            .header("User-Agent", USER_AGENT)
            .build()
        val raw = runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body.string() else null
            }
        }.getOrNull() ?: return null

        val root = runCatching { HttpClients.json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return null
        val sentences = root.getOrNull(0) as? JsonArray ?: return null
        val tr = StringBuilder()
        val rm = StringBuilder()
        for (elem in sentences) {
            val tuple = elem as? JsonArray ?: continue
            (tuple.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.let { tr.append(it) }
            (tuple.getOrNull(3) as? JsonPrimitive)?.contentOrNull?.let { rm.append(it) }
        }
        val detected = (root.getOrNull(2) as? JsonPrimitive)?.contentOrNull
        return TranslateBatchResponse(
            translatedText = tr.toString(),
            romanizedText = rm.toString(),
            detectedSourceLang = detected,
        )
    }

    private fun executeGoogleInputTools(text: String, sourceLang: String): String? {
        val itc = "$sourceLang-t-i0-und"
        val url = "https://inputtools.google.com/request?text=${LyricsHttp.enc(text)}&itc=${LyricsHttp.enc(itc)}&num=1&cp=0&cs=1&ie=utf-8&oe=utf-8"
        val req = Request.Builder()
            .url(url)
            .post(FormBody.Builder().build())
            .header("User-Agent", USER_AGENT)
            .build()
        val raw = runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body.string() else null
            }
        }.getOrNull() ?: return null
        val root = runCatching { HttpClients.json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return null
        if ((root.getOrNull(0) as? JsonPrimitive)?.contentOrNull != "SUCCESS") return null
        val candidates = (((root.getOrNull(1) as? JsonArray)?.getOrNull(0) as? JsonArray)?.getOrNull(1) as? JsonArray)
            ?: return null
        return (candidates.firstOrNull() as? JsonPrimitive)?.contentOrNull
    }

    // ---- Slot Flattening, Batching & Reconstruction -------------------------

    internal fun flattenSlots(lines: List<LyricLine>): List<String> {
        val out = mutableListOf<String>()
        for (line in lines) {
            if (line.isGap || line.text.isBlank()) continue
            val clean = unwrapSectionHeader(line.text)
            out += clean
            val bg = line.background
            if (bg != null && bg.text.isNotBlank()) {
                out += bg.withoutOuterParentheses().text
            }
        }
        return out
    }

    internal fun rebuildLines(original: List<LyricLine>, translatedSlots: List<String>): List<LyricLine> {
        var idx = 0
        return original.map { line ->
            if (line.isGap || line.text.isBlank()) return@map line
            val rawTr = translatedSlots.getOrNull(idx++)?.trim().orEmpty()
            val finalLead = if (SyncedLyricsParser.isSectionHeader(line.text)) {
                "[${rawTr.trim('[', ']')}]"
            } else {
                rawTr.ifBlank { line.text }
            }

            val origBg = line.background
            val rebuiltBg = if (origBg != null && origBg.text.isNotBlank()) {
                val bgTr = translatedSlots.getOrNull(idx++)?.trim().orEmpty().ifBlank { origBg.withoutOuterParentheses().text }
                retimeLine(origBg, bgTr)
            } else {
                null
            }
            retimeLine(line, finalLead).copy(background = rebuiltBg)
        }
    }

    private fun retimeLine(original: LyricLine, translatedText: String): LyricLine {
        val retimedWords = if (original.isWordSynced && translatedText.isNotBlank()) {
            val start = original.words.first().startMs
            val end = original.words.last().endMs.coerceAtLeast(start)
            listOf(LyricWord(startMs = start, endMs = end, text = translatedText, trailingSpace = false))
        } else {
            emptyList()
        }
        return LyricLine(
            startMs = original.startMs,
            endMs = original.endMs,
            text = translatedText,
            words = retimedWords,
            isBackgroundVocal = original.isBackgroundVocal,
            sungUntilMs = original.sungUntilMs,
            alignment = original.alignment,
            timingSource = if (original.isWordSynced) original else null,
        )
    }

    internal fun partitionBatches(slots: List<String>): List<List<String>> {
        val batches = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var chars = 0
        for (slot in slots) {
            val cost = slot.length + 12
            if (current.isNotEmpty() && chars + cost > MAX_BATCH_CHARS) {
                batches += current
                current = mutableListOf()
                chars = 0
            }
            current += slot
            chars += cost
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    internal fun splitDelimitedSlots(raw: String, fallbackBatch: List<String>): List<String> {
        val matches = DELIMITER_REGEX.findAll(raw).toList()
        if (matches.size == fallbackBatch.size) {
            return matches.indices.map { i ->
                val start = matches[i].range.last + 1
                val end = matches.getOrNull(i + 1)?.range?.first ?: raw.length
                raw.substring(start, end).trim().ifBlank { fallbackBatch[i] }
            }
        }
        val byLines = raw.lineSequence().map { DELIMITER_REGEX.replace(it, "").trim() }.filter { it.isNotEmpty() }.toList()
        if (byLines.size == fallbackBatch.size) return byLines
        return fallbackBatch.indices.map { i -> byLines.getOrNull(i) ?: fallbackBatch[i] }
    }

    private fun unwrapSectionHeader(text: String): String =
        if (SyncedLyricsParser.isSectionHeader(text)) text.trim().removePrefix("[").removeSuffix("]").trim() else text

    // ---- Cache Helpers (Memory + 2 MiB GZIP Disk) ---------------------------

    private suspend fun readCache(key: String): CachedTranslationPayload? {
        memoryCache[key]?.let { return it }
        val dir = diskDir ?: return null
        return withContext(Dispatchers.IO) {
            val file = File(dir, "$key.gz")
            if (!file.exists()) return@withContext null
            runCatching {
                GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).use { reader ->
                    val payload = HttpClients.json.decodeFromString<CachedTranslationPayload>(reader.readText())
                    if (payload.version == CachedTranslationPayload.CACHE_VERSION) {
                        file.setLastModified(System.currentTimeMillis())
                        memoryCache[key] = payload
                        payload
                    } else null
                }
            }.getOrNull()
        }
    }

    private suspend fun writeCache(key: String, payload: CachedTranslationPayload) {
        memoryCache[key] = payload
        val dir = diskDir ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                val tmp = File(dir, "$key.gz.tmp")
                val target = File(dir, "$key.gz")
                GZIPOutputStream(tmp.outputStream()).bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(HttpClients.json.encodeToString(payload))
                }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                pruneDiskCache(dir)
            }
        }
    }

    private fun pruneDiskCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".gz") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_CACHE_BYTES) return
        val oldestFirst = files.sortedBy { it.lastModified() }
        for (f in oldestFirst) {
            if (total <= MAX_DISK_CACHE_BYTES * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    private fun buildCacheKey(trackId: String, mode: String, slots: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(trackId.toByteArray(Charsets.UTF_8))
        md.update('|'.code.toByte())
        md.update(mode.toByteArray(Charsets.UTF_8))
        for (slot in slots) {
            md.update('\n'.code.toByte())
            md.update(slot.toByteArray(Charsets.UTF_8))
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    companion object {
        private const val MEM_CACHE_ENTRIES = 12
        private const val MAX_DISK_CACHE_BYTES = 2L * 1024L * 1024L
        private const val DISK_CACHE_DIR_NAME = "lyrics_translation_v1"
        private const val MAX_BATCH_CHARS = 3_500
        private const val MAX_PARALLEL_REQUESTS = 2
        private const val SAME_LANG_UNCHANGED_RATIO = 0.80f
        private const val ROMANIZED_REPAIR_THRESHOLD = 0.33f
        private const val MARKER_OPEN = '\uE000'
        private const val MARKER_CLOSE = '\uE001'
        private val DELIMITER_REGEX = Regex("""\uE000[^\uE000\uE001]*[\uE000\uE001]""")
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"

        private val INPUT_TOOLS_LANGUAGES = setOf(
            "hi", "ur", "pa", "bn", "gu", "kn", "ml", "mr", "ne", "or", "sa", "si", "ta", "te",
            "ar", "fa", "he", "el", "ru", "uk", "sr", "be", "am", "ti", "th", "zh-CN", "ja",
        )

        fun hasNonLatinLetters(slots: List<String>): Boolean = slots.any { slot ->
            slot.any { ch ->
                Character.isLetter(ch) && ch.code > 0x024F && ch.code !in 0x1E00..0x1EFF
            }
        }

        private fun isPredominantlyLatin(slots: List<String>): Boolean {
            var latin = 0
            var other = 0
            for (slot in slots) {
                for (ch in slot) {
                    if (!Character.isLetter(ch)) continue
                    if (ch.code <= 0x024F || ch.code in 0x1E00..0x1EFF) latin++ else other++
                }
            }
            return latin > 0 && latin >= other * 4
        }

        private fun unchangedRatio(a: List<String>, b: List<String>): Float {
            var totalChars = 0
            var sameChars = 0
            val count = minOf(a.size, b.size)
            for (i in 0 until count) {
                val orig = a[i].trim().lowercase(Locale.ROOT)
                val tr = b[i].trim().lowercase(Locale.ROOT)
                totalChars += orig.length
                if (orig == tr) sameChars += orig.length
            }
            return if (totalChars == 0) 1f else sameChars.toFloat() / totalChars.toFloat()
        }

        private fun isSameLanguage(detected: String?, target: String): Boolean {
            if (detected.isNullOrBlank()) return false
            val d = normalizeLanguageTag(detected)
            val t = normalizeLanguageTag(target)
            if (d.equals(t, ignoreCase = true)) return true
            return d.substringBefore('-').equals(t.substringBefore('-'), ignoreCase = true) &&
                !t.startsWith("zh-", ignoreCase = true)
        }

        fun normalizeLanguageTag(raw: String): String {
            val tag = raw.trim().replace('_', '-')
            if (tag.isEmpty()) return "en"
            val lower = tag.lowercase(Locale.ROOT)
            return when {
                lower == "in" || lower.startsWith("in-") -> "id"
                lower == "iw" || lower.startsWith("iw-") -> "he"
                lower == "ji" || lower.startsWith("ji-") -> "yi"
                lower == "zh" || lower == "zh-cn" || lower == "zh-sg" || lower == "zh-my" || lower.startsWith("zh-hans") -> "zh-CN"
                lower == "zh-tw" || lower == "zh-hk" || lower == "zh-mo" || lower.startsWith("zh-hant") -> "zh-TW"
                lower == "mni-mtei" -> "mni-Mtei"
                else -> tag.substringBefore('-').lowercase(Locale.ROOT)
            }
        }

        fun resolveTargetLanguageCode(explicitSetting: String, appLanguageTag: String): String {
            if (explicitSetting.isNotBlank()) return normalizeLanguageTag(explicitSetting)
            val fallback = appLanguageTag.ifBlank { Locale.getDefault().toLanguageTag() }
            return normalizeLanguageTag(fallback)
        }

        /** Complete 133-language catalog supported by Google Translate. */
        val LANGUAGES: List<TranslationLanguage> = listOf(
            TranslationLanguage("af"), TranslationLanguage("sq"), TranslationLanguage("am"),
            TranslationLanguage("ar"), TranslationLanguage("hy"), TranslationLanguage("as"),
            TranslationLanguage("ay"), TranslationLanguage("az"), TranslationLanguage("bm"),
            TranslationLanguage("eu"), TranslationLanguage("be"), TranslationLanguage("bn"),
            TranslationLanguage("bho", "Bhojpuri"), TranslationLanguage("bs"), TranslationLanguage("bg"),
            TranslationLanguage("ca"), TranslationLanguage("ceb", "Cebuano"), TranslationLanguage("ny", "Chichewa"),
            TranslationLanguage("zh-CN", "Chinese (Simplified)"), TranslationLanguage("zh-TW", "Chinese (Traditional)"),
            TranslationLanguage("co"), TranslationLanguage("hr"), TranslationLanguage("cs"),
            TranslationLanguage("da"), TranslationLanguage("dv", "Dhivehi"), TranslationLanguage("doi", "Dogri"),
            TranslationLanguage("nl"), TranslationLanguage("en"), TranslationLanguage("eo"),
            TranslationLanguage("et"), TranslationLanguage("ee"), TranslationLanguage("tl", "Filipino"),
            TranslationLanguage("fi"), TranslationLanguage("fr"), TranslationLanguage("fy"),
            TranslationLanguage("gl"), TranslationLanguage("ka"), TranslationLanguage("de"),
            TranslationLanguage("el"), TranslationLanguage("gn"), TranslationLanguage("gu"),
            TranslationLanguage("ht", "Haitian Creole"), TranslationLanguage("ha"), TranslationLanguage("haw", "Hawaiian"),
            TranslationLanguage("he"), TranslationLanguage("hi"), TranslationLanguage("hmn", "Hmong"),
            TranslationLanguage("hu"), TranslationLanguage("is"), TranslationLanguage("ig"),
            TranslationLanguage("ilo", "Ilocano"), TranslationLanguage("id"), TranslationLanguage("ga"),
            TranslationLanguage("it"), TranslationLanguage("ja"), TranslationLanguage("jw", "Javanese"),
            TranslationLanguage("kn"), TranslationLanguage("kk"), TranslationLanguage("km"),
            TranslationLanguage("rw"), TranslationLanguage("gom", "Konkani"), TranslationLanguage("ko"),
            TranslationLanguage("kri", "Krio"), TranslationLanguage("ku"), TranslationLanguage("ckb", "Kurdish (Sorani)"),
            TranslationLanguage("ky"), TranslationLanguage("lo"), TranslationLanguage("la"),
            TranslationLanguage("lv"), TranslationLanguage("ln"), TranslationLanguage("lt"),
            TranslationLanguage("lg"), TranslationLanguage("lb"), TranslationLanguage("mk"),
            TranslationLanguage("mai", "Maithili"), TranslationLanguage("mg"), TranslationLanguage("ms"),
            TranslationLanguage("ml"), TranslationLanguage("mt"), TranslationLanguage("mi"),
            TranslationLanguage("mr"), TranslationLanguage("mni-Mtei", "Meiteilon (Manipuri)"),
            TranslationLanguage("lus", "Mizo"), TranslationLanguage("mn"), TranslationLanguage("my"),
            TranslationLanguage("ne"), TranslationLanguage("no"), TranslationLanguage("or"),
            TranslationLanguage("om"), TranslationLanguage("ps"), TranslationLanguage("fa"),
            TranslationLanguage("pl"), TranslationLanguage("pt"), TranslationLanguage("pa"),
            TranslationLanguage("qu"), TranslationLanguage("ro"), TranslationLanguage("ru"),
            TranslationLanguage("sm"), TranslationLanguage("sa"), TranslationLanguage("gd"),
            TranslationLanguage("nso", "Sepedi"), TranslationLanguage("sr"), TranslationLanguage("st"),
            TranslationLanguage("sn"), TranslationLanguage("sd"), TranslationLanguage("si"),
            TranslationLanguage("sk"), TranslationLanguage("sl"), TranslationLanguage("so"),
            TranslationLanguage("es"), TranslationLanguage("su"), TranslationLanguage("sw"),
            TranslationLanguage("sv"), TranslationLanguage("tg"), TranslationLanguage("ta"),
            TranslationLanguage("tt"), TranslationLanguage("te"), TranslationLanguage("th"),
            TranslationLanguage("ti"), TranslationLanguage("ts"), TranslationLanguage("tr"),
            TranslationLanguage("tk"), TranslationLanguage("ak"), TranslationLanguage("uk"),
            TranslationLanguage("ur"), TranslationLanguage("ug"), TranslationLanguage("uz"),
            TranslationLanguage("vi"), TranslationLanguage("cy"), TranslationLanguage("xh"),
            TranslationLanguage("yi"), TranslationLanguage("yo"), TranslationLanguage("zu"),
        )
    }
}
