package com.music.musica.data.stream

import android.content.Context
import android.util.Log
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.models.YouTubeLocale
import com.music.musica.core.network.HttpClients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Primary YouTube stream extractor backed by the open-source InnerTubeX library.
 */
class InnerTubeXSource(
    context: Context,
    private val cookieProvider: () -> String?,
    private val visitorDataProvider: () -> String?,
    private val languageProvider: () -> String = { "en" },
    private val regionProvider: () -> String = { "US" },
    tokenProvider: TokenProvider,
) : StreamSource {

    override val name: String = "InnerTubeX"

    private val prefs = context.getSharedPreferences("musica_itx_config", Context.MODE_PRIVATE)
    private val playerCacheDir = File(context.filesDir, "itx_players").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val logger = InnerTubeLogger { event ->
        Log.d(TAG, "${event.tag}: ${event.message}")
    }

    private val configRepository = object : PlayerConfigRepository {
        override val enabled: Boolean = true
        override val sourceUrl: String = DEFAULT_CONFIG_URL
        override val defaultSourceUrl: String = DEFAULT_CONFIG_URL
        override var cachedJson: String
            get() = prefs.getString("json", "").orEmpty()
            set(value) { prefs.edit().putString("json", value).apply() }
        override var cachedAtMs: Long
            get() = prefs.getLong("cached_at", 0L)
            set(value) { prefs.edit().putLong("cached_at", value).apply() }
        override var cachedSourceUrl: String
            get() = prefs.getString("source_url", "").orEmpty()
            set(value) { prefs.edit().putString("source_url", value).apply() }
        override var cachedEtag: String
            get() = prefs.getString("etag", "").orEmpty()
            set(value) { prefs.edit().putString("etag", value).apply() }
    }

    private val innerTube = InnerTube(HttpClients.ktor, logger = logger)
    private val remoteStore = RemotePlayerConfigStore(HttpClients.ktor, configRepository, logger)
    private val cipherService = YouTubeCipherService(HttpClients.ktor, remoteStore, logger)
    private val extractor = InnerTubeExtractor(
        configParser = YtConfigParserImpl(HttpClients.ktor, innerTube, remoteStore, logger),
        cipherService = cipherService,
        innerTube = innerTube,
        tokenProvider = tokenProvider,
        logger = logger,
    )

    fun prewarmAsync() {
        scope.launch {
            runCatching {
                cipherService.setPreprocessedPlayerCache(::readPlayerCache, ::writePlayerCache)
                syncContext()
                extractor.prewarm()
            }.onFailure { Log.w(TAG, "Prewarm skipped: ${it.message}") }
        }
    }

    override suspend fun resolve(videoId: String, maxKbps: Int): ResolvedStream? {
        syncContext()
        val quality = when {
            maxKbps <= 80 -> AudioQuality.LOW
            maxKbps >= 256 -> AudioQuality.HIGH
            else -> AudioQuality.AUTO
        }
        val stream = extractor.extract(
            videoId = videoId,
            hints = ContentHints()
                .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true),
            excludedClients = emptySet(),
            audioQuality = quality,
            clientPlaybackNonce = generateClientPlaybackNonce(),
        ) ?: return null

        return ResolvedStream(
            videoId = videoId,
            url = stream.audioUrl,
            mimeType = stream.mimeType ?: "audio/webm",
            codecs = stream.codecs ?: "opus",
            bitrateKbps = ((stream.bitrate ?: 128_000) / 1000).coerceAtLeast(32),
            loudnessDb = stream.loudnessDb,
            sourceClient = "InnerTubeX:${stream.clientName}",
            headers = stream.headers,
        )
    }

    @Synchronized
    private fun syncContext() {
        innerTube.cookie = cookieProvider()
        innerTube.visitorData = visitorDataProvider()
        innerTube.locale = YouTubeLocale(gl = regionProvider(), hl = languageProvider())
    }

    @Synchronized
    private fun readPlayerCache(key: String): String? =
        File(playerCacheDir, key).takeIf { it.isFile }?.readText()

    @Synchronized
    private fun writePlayerCache(key: String, value: String?) {
        val target = File(playerCacheDir, key)
        if (value == null) {
            target.delete()
            return
        }
        val tmp = File(playerCacheDir, "$key.tmp")
        tmp.writeText(value)
        tmp.renameTo(target)
        playerCacheDir.listFiles()
            ?.filter { !it.name.endsWith(".tmp") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(3)
            ?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "MusicaITX"
        private const val DEFAULT_CONFIG_URL =
            "https://raw.githubusercontent.com/MetrolistGroup/innertubex/main/config/player-config.json"
    }
}
