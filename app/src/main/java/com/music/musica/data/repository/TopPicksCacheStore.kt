package com.music.musica.data.repository

import android.content.Context
import com.music.musica.core.model.TopPick
import com.music.musica.core.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.LocalDateTime
import java.util.Locale

@Serializable
data class StoredTopPicks(
    val identity: String,
    val hourSlot: String,
    val languageTag: String = "",
    val picks: List<TopPick> = emptyList(),
    val artistPhotos: Map<String, String> = emptyMap(),
)

/**
 * Atomic on-disk cache for the current clock hour's Top Picks row so Home paints
 * immediately on cold start before network requests complete.
 */
class TopPicksCacheStore(
    private val cacheFile: File,
) {
    constructor(context: Context) : this(File(context.filesDir, CACHE_FILE_NAME))

    suspend fun load(): StoredTopPicks? = withContext(Dispatchers.IO) {
        runCatching {
            if (!cacheFile.exists()) return@runCatching null
            decode(cacheFile.readText())
        }.getOrNull()
    }

    suspend fun save(stored: StoredTopPicks) = withContext(Dispatchers.IO) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tmp.writeText(encode(stored))
            if (!tmp.renameTo(cacheFile)) {
                tmp.copyTo(cacheFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    companion object {
        private const val CACHE_FILE_NAME = "top_picks_v1.json"

        fun hourSlotOf(now: LocalDateTime = LocalDateTime.now()): String =
            String.format(Locale.ROOT, "%sT%02d", now.toLocalDate().toString(), now.hour)

        fun encode(stored: StoredTopPicks): String =
            HttpClients.json.encodeToString(stored)

        fun decode(raw: String): StoredTopPicks? =
            runCatching { HttpClients.json.decodeFromString<StoredTopPicks>(raw) }.getOrNull()
    }
}
