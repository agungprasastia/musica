package com.music.musica

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath
import com.music.musica.core.auth.KeystoreAuthStore
import com.music.musica.core.database.CachedLyricsEntity
import com.music.musica.core.database.MusicaDatabase
import com.music.musica.core.model.LyricsSourceId
import com.music.musica.core.model.Song
import com.music.musica.core.network.HttpClients
import com.music.musica.core.network.NetworkMonitor
import com.music.musica.core.network.NetworkType
import com.music.musica.core.settings.SettingsStore
import com.music.musica.core.settings.UserSettings
import com.music.musica.data.download.AudioCacheProvider
import com.music.musica.data.download.DownloadRepository
import com.music.musica.data.download.MusicaDownloadService
import com.music.musica.data.innertube.MusicaInnertubeClient
import com.music.musica.data.lyrics.BetterLyricsProvider
import com.music.musica.data.lyrics.LrclibProvider
import com.music.musica.data.lyrics.LyricLine
import com.music.musica.data.lyrics.LyricsPlusProvider
import com.music.musica.data.lyrics.LyricsRepository
import com.music.musica.data.lyrics.LyricsResult
import com.music.musica.data.lyrics.YouTubeLyricsProvider
import com.music.musica.data.repository.LibraryRepository
import com.music.musica.data.repository.YtMusicRepository
import com.music.musica.data.stream.InnerTubeXSource
import com.music.musica.data.stream.NewPipeFallbackSource
import com.music.musica.data.stream.PoTokenWebViewProvider
import com.music.musica.data.stream.StreamResolver
import com.music.musica.playback.AutoplayController
import com.music.musica.playback.PlayerController
import com.music.musica.playback.SleepTimerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.Locale

@OptIn(UnstableApi::class)
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsStore: SettingsStore = SettingsStore(appContext)
    val loadedSettings: StateFlow<UserSettings?> = settingsStore.settings.stateIn(
        scope = appScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )
    val settingsState: StateFlow<UserSettings> = settingsStore.settings.stateIn(
        scope = appScope,
        started = SharingStarted.Eagerly,
        initialValue = UserSettings(),
    )

    val authStore: KeystoreAuthStore = KeystoreAuthStore(appContext)
    val networkMonitor: NetworkMonitor = NetworkMonitor(appContext)
    val database: MusicaDatabase = MusicaDatabase.build(appContext)
    val audioCacheProvider: AudioCacheProvider = AudioCacheProvider(appContext)

    val downloadManager: DownloadManager by lazy {
        MusicaDownloadService.createDownloadManager(appContext, audioCacheProvider)
    }

    private fun resolvedYtLanguage(): String {
        val raw = settingsState.value.appLanguage.tag.ifBlank {
            Locale.getDefault().language
        }.trim().lowercase(Locale.ROOT)
        return when (raw) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            "" -> "en"
            else -> raw
        }
    }

    val innertubeClient: MusicaInnertubeClient = MusicaInnertubeClient(
        cookieProvider = { authStore.currentCookie },
        visitorDataProvider = { authStore.getVisitorData() },
        onVisitorDataUpdated = { authStore.saveVisitorData(it) },
        languageProvider = ::resolvedYtLanguage,
        regionProvider = { Locale.getDefault().country.ifBlank { "US" } },
    )

    val ytMusicRepository: YtMusicRepository = YtMusicRepository(innertubeClient)

    val libraryRepository: LibraryRepository = LibraryRepository(
        db = database,
        ytMusicRepository = ytMusicRepository,
        isSignedIn = { authStore.isSignedIn },
    )

    private val poTokenProvider: PoTokenWebViewProvider = PoTokenWebViewProvider(appContext)

    val innerTubeXSource: InnerTubeXSource = InnerTubeXSource(
        context = appContext,
        cookieProvider = { authStore.currentCookie },
        visitorDataProvider = { authStore.getVisitorData() },
        languageProvider = ::resolvedYtLanguage,
        regionProvider = { Locale.getDefault().country.ifBlank { "US" } },
        tokenProvider = poTokenProvider,
    )

    val streamResolver: StreamResolver = StreamResolver(
        primary = innerTubeXSource,
        fallback = NewPipeFallbackSource(),
        qualityKbpsProvider = {
            val s = settingsState.value
            when (networkMonitor.networkType.value) {
                NetworkType.CELLULAR -> s.cellularQuality.maxKbps
                else -> s.wifiQuality.maxKbps
            }
        },
    )

    val downloadRepository: DownloadRepository = DownloadRepository(
        appContext = appContext,
        db = database,
        libraryRepository = libraryRepository,
        streamResolver = streamResolver,
        cacheProvider = audioCacheProvider,
    )

    val lyricsRepository: LyricsRepository = LyricsRepository(
        providers = listOf(
            BetterLyricsProvider(),
            LyricsPlusProvider(),
            LrclibProvider(),
            YouTubeLyricsProvider(innertubeClient),
        ),
        cacheRead = { videoId ->
            database.lyricsCacheDao().get(videoId)?.let { cached ->
                val lines = runCatching {
                    HttpClients.json.decodeFromString<List<LyricLine>>(cached.payloadJson)
                }.getOrDefault(emptyList())
                val source = runCatching { LyricsSourceId.valueOf(cached.sourceName) }
                    .getOrDefault(LyricsSourceId.LRCLIB)
                if (lines.isEmpty()) null
                else LyricsResult(
                    source = source,
                    lines = lines,
                    isSynced = cached.isSynced,
                    isWordSynced = cached.isWordSynced,
                    offsetMs = cached.offsetMs,
                )
            }
        },
        cacheWrite = { videoId, result ->
            database.lyricsCacheDao().upsert(
                CachedLyricsEntity(
                    videoId = videoId,
                    sourceName = result.source.name,
                    isSynced = result.isSynced,
                    isWordSynced = result.isWordSynced,
                    payloadJson = HttpClients.json.encodeToString(result.lines),
                    offsetMs = result.offsetMs,
                ),
            )
        },
        offsetWrite = { videoId, offsetMs ->
            database.lyricsCacheDao().updateOffset(videoId, offsetMs)
        },
    )

    val autoplayController: AutoplayController = AutoplayController(ytMusicRepository)
    val playerController: PlayerController = PlayerController(appContext)

    val sleepTimerManager: SleepTimerManager = SleepTimerManager(
        onTimerFinished = { playerController.pauseForSleepTimer() },
        onStateChanged = { state ->
            playerController.updateSleepTimerUi(state.remainingMs, state.stopAfterCurrentTrack)
        },
    )

    fun onTrackPlayed(song: Song, listenedMs: Long) {
        appScope.launch {
            runCatching { libraryRepository.recordPlay(song, listenedMs) }
        }
    }

    init {
        innerTubeXSource.prewarmAsync()
    }
}

class MusicaApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { HttpClients.okHttp }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(80L * 1024L * 1024L)
                    .build()
            }
            .build()
}
