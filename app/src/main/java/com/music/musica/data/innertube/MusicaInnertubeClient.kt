package com.music.musica.data.innertube

import com.music.musica.core.auth.SapisidHashSigner
import com.music.musica.core.model.AccountProfile
import com.music.musica.core.model.BrowsePage
import com.music.musica.core.model.DetailPage
import com.music.musica.core.model.HomeShelf
import com.music.musica.core.model.LikeStatus
import com.music.musica.core.model.SearchFilter
import com.music.musica.core.model.SearchPage
import com.music.musica.core.model.Song
import com.music.musica.core.model.TopPickMood
import com.music.musica.core.network.HttpClients
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Clean-room YouTube Music InnerTube API client supporting both Guest and Signed-In (SAPISIDHASH) modes.
 */
class MusicaInnertubeClient(
    private val http: HttpClient = HttpClients.ktor,
    private val baseUrl: String = MUSIC_API_BASE,
    private val cookieProvider: () -> String? = { null },
    private val visitorDataProvider: () -> String? = { null },
    private val onVisitorDataUpdated: (String) -> Unit = {},
    private val languageProvider: () -> String = { "en" },
    private val regionProvider: () -> String = { "US" },
) {

    suspend fun browseHome(continuation: String? = null): BrowsePage {
        val root = if (continuation == null) {
            postMusic("browse") {
                put("browseId", "FEmusic_home")
            }
        } else {
            postMusic("browse", continuation = continuation) {}
        } ?: return BrowsePage(emptyList())
        return InnertubeParsers.parseBrowseHome(root)
    }

    suspend fun browseExplore(): BrowsePage {
        val root = postMusic("browse") {
            put("browseId", "FEmusic_explore")
        } ?: return BrowsePage(emptyList())
        return InnertubeParsers.parseBrowseHome(root)
    }

    suspend fun browseMoodsAndGenres(): BrowsePage {
        val root = postMusic("browse") {
            put("browseId", "FEmusic_moods_and_genres")
        } ?: return BrowsePage(emptyList())
        return InnertubeParsers.parseBrowseHome(root)
    }

    suspend fun browseMoodCategory(params: String): List<HomeShelf> {
        val root = postMusic("browse") {
            put("browseId", TopPickMood.CATEGORY_BROWSE_ID)
            put("params", params)
        } ?: return emptyList()
        return InnertubeParsers.parseBrowseHome(root).shelves
    }

    suspend fun browseHistory(): List<Song> {
        if (cookieProvider().isNullOrBlank()) return emptyList()
        val root = postMusic("browse") {
            put("browseId", "FEmusic_history")
        } ?: return emptyList()
        return InnertubeParsers.parseHistorySongs(root)
    }

    suspend fun browseMixedForYou(languageOverride: String? = null): List<com.music.musica.core.model.ShelfItem> {
        val root = postMusic("browse", languageOverride = languageOverride) {
            put("browseId", "FEmusic_mixed_for_you")
        } ?: return emptyList()
        return InnertubeParsers.parseTwoRowItemsDeep(root)
    }

    suspend fun browseMixes(): BrowsePage = browseMoodsAndGenres()

    suspend fun browseLibrary(browseId: String = "FEmusic_liked_playlists"): BrowsePage {
        val root = postMusic("browse") {
            put("browseId", browseId)
        } ?: return BrowsePage(emptyList())
        return InnertubeParsers.parseBrowseHome(root)
    }

    suspend fun browseDetail(browseId: String, params: String? = null): DetailPage {
        val root = postMusic("browse") {
            put("browseId", browseId)
            if (!params.isNullOrBlank()) put("params", params)
        } ?: return InnertubeParsers.parseDetailPage(browseId, JsonObject(emptyMap()))
        return InnertubeParsers.parseDetailPage(browseId, root)
    }

    suspend fun browseDetailContinuation(
        browseId: String,
        continuation: String,
        fallbackCover: String? = null,
        fallbackAlbum: com.music.musica.core.model.AlbumRef? = null,
        fallbackArtist: com.music.musica.core.model.ArtistRef? = null,
    ): Pair<List<Song>, String?> {
        val root = postMusic("browse", continuation = continuation) {}
            ?: return emptyList<Song>() to null
        return InnertubeParsers.parseDetailContinuation(
            browseId = browseId,
            root = root,
            fallbackCover = fallbackCover,
            fallbackAlbum = fallbackAlbum,
            fallbackArtist = fallbackArtist,
        )
    }

    suspend fun browseLikedSongs(continuation: String? = null): Pair<List<Song>, String?> {
        return if (continuation == null) {
            val page = browseDetail("VLLM")
            page.tracks to page.continuation
        } else {
            browseDetailContinuation("VLLM", continuation)
        }
    }

    suspend fun search(
        query: String,
        filter: SearchFilter = SearchFilter.ALL,
        continuation: String? = null,
    ): SearchPage {
        val root = postMusic("search", continuation = continuation) {
            if (continuation == null) {
                put("query", query)
                filter.innertubeParams?.let { put("params", it) }
            }
        } ?: return SearchPage(emptyList())
        return InnertubeParsers.parseSearchPage(root)
    }

    suspend fun searchSuggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val root = postMusic("music/get_search_suggestions") {
            put("input", query)
        } ?: return emptyList()
        return InnertubeParsers.parseSearchSuggestions(root)
    }

    suspend fun upNext(videoId: String, playlistId: String? = null): List<Song> =
        nextWatchQueue(
            playlistId = playlistId ?: "RDAMVM$videoId",
            videoId = videoId,
            params = null,
            preferAudio = false,
        )

    suspend fun nextWatchQueue(
        playlistId: String,
        videoId: String? = null,
        params: String? = null,
        preferAudio: Boolean = false,
    ): List<Song> {
        val root = postMusic("next") {
            if (!videoId.isNullOrBlank()) put("videoId", videoId)
            put("playlistId", playlistId)
            if (!params.isNullOrBlank()) put("params", params)
            put("isAudioOnly", true)
            put("enablePersistentPlaylistPanel", true)
        } ?: return emptyList()
        return InnertubeParsers.parseUpNext(root, preferAudio = preferAudio)
    }

    suspend fun fetchYouTubeLyrics(videoId: String): YouTubeLyricsPayload? {
        val nextRoot = postMusic("next") {
            put("videoId", videoId)
            put("isAudioOnly", true)
        } ?: return null
        val lyricsBrowseId = InnertubeParsers.parseLyricsBrowseId(nextRoot) ?: return null

        // Try ANDROID_MUSIC first for timed lyrics, then fallback to WEB_REMIX plain lyrics.
        val mobileRoot = postAndroidMusic("browse") {
            put("browseId", lyricsBrowseId)
        }
        val timed = mobileRoot?.let { InnertubeParsers.parseTimedLyricsLines(it) }.orEmpty()
        if (timed.isNotEmpty()) {
            return YouTubeLyricsPayload(timedLines = timed, plainText = null)
        }

        val webRoot = postMusic("browse") {
            put("browseId", lyricsBrowseId)
        }
        val plain = webRoot?.let { InnertubeParsers.parsePlainLyrics(it) }
        return if (!plain.isNullOrBlank()) YouTubeLyricsPayload(timedLines = emptyList(), plainText = plain) else null
    }

    suspend fun fetchYouTubeTranscript(videoId: String): List<InnertubeParsers.TimedLyricCue> {
        if (videoId.length != 11) return emptyList()
        val params = encodeTranscriptParams(videoId)
        val root = postYouTubeWeb("get_transcript") {
            put("params", params)
        } ?: return emptyList()
        return InnertubeParsers.parseTranscriptCues(root)
    }

    private suspend fun postYouTubeWeb(
        endpoint: String,
        bodyBuilder: JsonObjectBuilder.() -> Unit,
    ): JsonObject? {
        val hl = normalizeLanguage(languageProvider())
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20260101.01.00")
                    put("hl", hl)
                    put("gl", regionProvider())
                }
            }
            bodyBuilder()
        }
        val response = http.post("https://www.youtube.com/youtubei/v1/$endpoint") {
            parameter("prettyPrint", "false")
            contentType(ContentType.Application.Json)
            header("User-Agent", WEB_USER_AGENT)
            header("Origin", "https://www.youtube.com")
            header("Referer", "https://www.youtube.com/")
            setBody(payload.toString())
        }
        if (response.status.value !in 200..299) return null
        return runCatching { HttpClients.json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
    }

    suspend fun setLikeStatus(videoId: String, status: LikeStatus): Boolean {
        if (cookieProvider().isNullOrBlank()) return false
        val endpoint = when (status) {
            LikeStatus.LIKE -> "like/like"
            LikeStatus.DISLIKE -> "like/dislike"
            LikeStatus.INDIFFERENT -> "like/removelike"
        }
        return postMusic(endpoint) {
            putJsonObject("target") { put("videoId", videoId) }
        } != null
    }

    suspend fun getAccountProfile(): AccountProfile? {
        if (cookieProvider().isNullOrBlank()) return null
        val root = postMusic("account/account_menu") {
            put("deviceTheme", "DEVICE_THEME_SUPPORTED")
        } ?: return null
        return InnertubeParsers.parseAccountProfile(root)
    }

    suspend fun createPlaylist(title: String, description: String = ""): String? {
        if (cookieProvider().isNullOrBlank()) return null
        val root = postMusic("playlist/create") {
            put("title", title)
            put("description", description)
            put("privacyStatus", "PRIVATE")
        } ?: return null
        return (root["playlistId"] as? JsonPrimitive)?.contentOrNull
    }

    suspend fun addSongToPlaylist(playlistId: String, videoId: String): Boolean {
        if (cookieProvider().isNullOrBlank()) return false
        val cleanId = playlistId.removePrefix("VL")
        return postMusic("browse/edit_playlist") {
            put("playlistId", cleanId)
            putJsonArray("actions") {
                add(
                    buildJsonObject {
                        put("action", "ACTION_ADD_VIDEO")
                        put("addedVideoId", videoId)
                    },
                )
            }
        } != null
    }

    suspend fun deletePlaylist(playlistId: String): Boolean {
        if (cookieProvider().isNullOrBlank()) return false
        val cleanId = playlistId.removePrefix("VL")
        return postMusic("playlist/delete") {
            put("playlistId", cleanId)
        } != null
    }

    suspend fun renamePlaylist(playlistId: String, title: String, description: String = ""): Boolean {
        if (cookieProvider().isNullOrBlank()) return false
        val cleanId = playlistId.removePrefix("VL")
        return postMusic("browse/edit_playlist") {
            put("playlistId", cleanId)
            putJsonArray("actions") {
                add(
                    buildJsonObject {
                        put("action", "ACTION_SET_PLAYLIST_NAME")
                        put("playlistName", title.trim())
                    },
                )
                if (description.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("action", "ACTION_SET_PLAYLIST_DESCRIPTION")
                            put("playlistDescription", description.trim())
                        },
                    )
                }
            }
        } != null
    }

    suspend fun removeSongFromPlaylist(playlistId: String, videoId: String, setVideoId: String?): Boolean {
        if (cookieProvider().isNullOrBlank()) return false
        val cleanId = playlistId.removePrefix("VL")
        return postMusic("browse/edit_playlist") {
            put("playlistId", cleanId)
            putJsonArray("actions") {
                add(
                    buildJsonObject {
                        put("action", "ACTION_REMOVE_VIDEO")
                        put("removedVideoId", videoId)
                        if (!setVideoId.isNullOrBlank()) put("setVideoId", setVideoId)
                    },
                )
            }
        } != null
    }

    suspend fun movePlaylistItem(
        playlistId: String,
        setVideoId: String,
        movedBeforeSetVideoId: String?,
    ): Boolean {
        if (cookieProvider().isNullOrBlank()) return false
        val cleanId = playlistId.removePrefix("VL")
        return postMusic("browse/edit_playlist") {
            put("playlistId", cleanId)
            putJsonArray("actions") {
                add(
                    buildJsonObject {
                        put("action", "ACTION_MOVE_VIDEO_BEFORE")
                        put("setVideoId", setVideoId)
                        if (!movedBeforeSetVideoId.isNullOrBlank()) {
                            put("movedSetVideoIdSuccessor", movedBeforeSetVideoId)
                        }
                    },
                )
            }
        } != null
    }

    suspend fun setSubscribed(channelId: String, subscribe: Boolean): Boolean {
        if (cookieProvider().isNullOrBlank()) return false
        val endpoint = if (subscribe) "subscription/subscribe" else "subscription/unsubscribe"
        return postMusic(endpoint) {
            putJsonArray("channelIds") {
                add(JsonPrimitive(channelId))
            }
        } != null
    }

    suspend fun ratePlaylist(playlistId: String, like: Boolean): Boolean {
        if (cookieProvider().isNullOrBlank()) return false
        val cleanId = playlistId.removePrefix("VL")
        val endpoint = if (like) "like/like" else "like/removelike"
        return postMusic(endpoint) {
            putJsonObject("target") {
                put("playlistId", cleanId)
            }
        } != null
    }

    suspend fun fetchPlaybackTracking(videoId: String): PlaybackTrackingUrls? {
        if (cookieProvider().isNullOrBlank()) return null
        val root = postMusic("player") {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        } ?: return null
        return InnertubeParsers.parsePlaybackTracking(root)
    }

    suspend fun pingTrackingUrl(
        baseUrl: String,
        queryParams: Map<String, String>,
    ): Int {
        val cookie = cookieProvider()?.takeIf { it.isNotBlank() } ?: return 0
        val scope = ensureSessionScope(cookie)
        val response = http.get(baseUrl) {
            queryParams.forEach { (k, v) -> parameter(k, v) }
            header("User-Agent", WEB_USER_AGENT)
            header("Origin", MUSIC_ORIGIN)
            header("Referer", "$MUSIC_ORIGIN/")
            header("Cookie", cookie)
            header("X-Goog-AuthUser", scope?.authUser ?: "0")
            scope?.pageId?.let { header("X-Goog-PageId", it) }
            SapisidHashSigner.authorizationHeader(cookie, MUSIC_ORIGIN)?.let {
                header("Authorization", it)
            }
        }
        return response.status.value
    }

    private data class SessionScope(
        val cookieHash: Int,
        val dataSyncId: String?,
        val pageId: String?,
        val authUser: String,
        val clientVersion: String?,
        val visitorData: String?,
    )

    @Volatile
    private var sessionScope: SessionScope? = null
    private val scopeMutex = Mutex()

    fun clearSessionScope() {
        sessionScope = null
    }

    private suspend fun ensureSessionScope(cookie: String): SessionScope? {
        if (baseUrl != MUSIC_API_BASE) return null
        val hash = cookie.hashCode()
        sessionScope?.takeIf { it.cookieHash == hash }?.let { return it }
        return scopeMutex.withLock {
            sessionScope?.takeIf { it.cookieHash == hash }?.let { return@withLock it }
            val resolved = runCatching { fetchSessionScope(cookie, hash) }.getOrNull()
            if (resolved != null) {
                sessionScope = resolved
                resolved.visitorData?.let(onVisitorDataUpdated)
            }
            resolved
        }
    }

    private suspend fun fetchSessionScope(cookie: String, cookieHash: Int): SessionScope? {
        val hl = normalizeLanguage(languageProvider())
        val response = http.get("$MUSIC_ORIGIN/") {
            header("User-Agent", WEB_USER_AGENT)
            header("Accept-Language", acceptLanguageHeader(hl))
            header("Cookie", cookie)
            SapisidHashSigner.authorizationHeader(cookie, MUSIC_ORIGIN)?.let {
                header("Authorization", it)
            }
        }
        if (response.status.value !in 200..299) return null
        val html = response.bodyAsText()
        val loggedIn = CONFIG_LOGGED_IN.find(html)?.groupValues?.get(1) == "true"
        val clientVersion = CONFIG_CLIENT_VERSION.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        if (!loggedIn) {
            return SessionScope(
                cookieHash = cookieHash,
                dataSyncId = null,
                pageId = null,
                authUser = "0",
                clientVersion = clientVersion,
                visitorData = null,
            )
        }
        val pageId = CONFIG_PAGE_ID.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val dataSyncId = pageId ?: normalizeDataSyncId(
            CONFIG_DATASYNC_ID.find(html)?.groupValues?.get(1),
        )
        val authUser = CONFIG_SESSION_INDEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: "0"
        val visitor = CONFIG_VISITOR_DATA.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        return SessionScope(
            cookieHash = cookieHash,
            dataSyncId = dataSyncId,
            pageId = pageId,
            authUser = authUser,
            clientVersion = clientVersion,
            visitorData = visitor,
        )
    }

    private suspend fun postMusic(
        endpoint: String,
        continuation: String? = null,
        languageOverride: String? = null,
        bodyBuilder: JsonObjectBuilder.() -> Unit,
    ): JsonObject? {
        val cookie = cookieProvider()?.takeIf { it.isNotBlank() }
        val scope = if (cookie != null) ensureSessionScope(cookie) else null
        val visitor = scope?.visitorData ?: visitorDataProvider()?.takeIf { it.isNotBlank() }
        val hl = normalizeLanguage(languageOverride?.takeIf { it.isNotBlank() } ?: languageProvider())
        val clientVersion = scope?.clientVersion ?: WEB_REMIX_VERSION
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", clientVersion)
                    put("hl", hl)
                    put("gl", regionProvider())
                    if (visitor != null) put("visitorData", visitor)
                }
                putJsonObject("user") {
                    put("lockedSafetyMode", false)
                    scope?.dataSyncId?.let { put("onBehalfOfUser", it) }
                }
                putJsonObject("request") {
                    put("useSsl", true)
                }
            }
            bodyBuilder()
        }

        val response = http.post("${baseUrl.trimEnd('/')}/$endpoint") {
            parameter("prettyPrint", "false")
            parameter("hl", hl)
            if (!continuation.isNullOrBlank()) {
                parameter("ctoken", continuation)
                parameter("continuation", continuation)
                parameter("type", "next")
            }
            contentType(ContentType.Application.Json)
            header("User-Agent", WEB_USER_AGENT)
            header("Accept-Language", acceptLanguageHeader(hl))
            header("Origin", MUSIC_ORIGIN)
            header("Referer", "$MUSIC_ORIGIN/")
            header("X-Origin", MUSIC_ORIGIN)
            header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
            header("X-YouTube-Client-Version", clientVersion)
            if (visitor != null) header("X-Goog-Visitor-Id", visitor)
            if (cookie != null) {
                header("Cookie", cookie)
                header("X-Goog-AuthUser", scope?.authUser ?: "0")
                scope?.pageId?.let { header("X-Goog-PageId", it) }
                SapisidHashSigner.authorizationHeader(cookie, MUSIC_ORIGIN)?.let {
                    header("Authorization", it)
                }
            }
            setBody(payload.toString())
        }

        if (response.status.value !in 200..299) return null
        val text = response.bodyAsText()
        val obj = runCatching { HttpClients.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val newVisitor = (obj["responseContext"] as? JsonObject)
            ?.let { it["visitorData"] as? JsonPrimitive }
            ?.contentOrNull
        if (!newVisitor.isNullOrBlank() && newVisitor != visitor && scope?.visitorData == null) {
            onVisitorDataUpdated(newVisitor)
        }
        return obj
    }

    private suspend fun postAndroidMusic(
        endpoint: String,
        bodyBuilder: JsonObjectBuilder.() -> Unit,
    ): JsonObject? {
        val cookie = cookieProvider()?.takeIf { it.isNotBlank() }
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "ANDROID_MUSIC")
                    put("clientVersion", ANDROID_MUSIC_VERSION)
                    put("androidSdkVersion", 34)
                    put("hl", languageProvider())
                    put("gl", regionProvider())
                }
            }
            bodyBuilder()
        }
        val response = http.post("${baseUrl.trimEnd('/')}/$endpoint") {
            parameter("prettyPrint", "false")
            contentType(ContentType.Application.Json)
            header("User-Agent", ANDROID_MUSIC_UA)
            header("X-YouTube-Client-Name", ANDROID_MUSIC_CLIENT_ID)
            header("X-YouTube-Client-Version", ANDROID_MUSIC_VERSION)
            if (cookie != null) {
                header("Cookie", cookie)
                header("Origin", MUSIC_ORIGIN)
                header("X-Origin", MUSIC_ORIGIN)
                header("X-Goog-AuthUser", "0")
                SapisidHashSigner.authorizationHeader(cookie, MUSIC_ORIGIN)?.let {
                    header("Authorization", it)
                }
            }
            setBody(payload.toString())
        }
        if (response.status.value !in 200..299) return null
        return runCatching { HttpClients.json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
    }

    data class YouTubeLyricsPayload(
        val timedLines: List<InnertubeParsers.TimedLyricCue>,
        val plainText: String?,
    )

    companion object {
        const val MUSIC_API_BASE = "https://music.youtube.com/youtubei/v1"
        const val MUSIC_ORIGIN = "https://music.youtube.com"
        private const val WEB_REMIX_CLIENT_ID = "67"
        private const val WEB_REMIX_VERSION = "1.20260101.01.00"
        private const val ANDROID_MUSIC_CLIENT_ID = "21"
        private const val ANDROID_MUSIC_VERSION = "7.21.50"
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        private const val ANDROID_MUSIC_UA =
            "com.google.android.apps.youtube.music/7.21.50 (Linux; U; Android 14) gzip"

        private val CONFIG_LOGGED_IN = Regex(""""LOGGED_IN"\s*:\s*(true|false)""")
        private val CONFIG_DATASYNC_ID = Regex(""""DATASYNC_ID"\s*:\s*"([^"]+)"""")
        private val CONFIG_PAGE_ID = Regex(""""DELEGATED_SESSION_ID"\s*:\s*"([^"]+)"""")
        private val CONFIG_SESSION_INDEX = Regex(""""SESSION_INDEX"\s*:\s*"?(\d+)""")
        private val CONFIG_VISITOR_DATA = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
        private val CONFIG_CLIENT_VERSION = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""")

        internal fun normalizeLanguage(raw: String?): String = when (raw?.trim()?.lowercase()) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            null, "" -> "en"
            else -> raw.trim().lowercase()
        }

        internal fun normalizeDataSyncId(raw: String?): String? {
            val value = raw?.takeIf { it.isNotBlank() } ?: return null
            if (!value.contains("||")) return value
            return value.substringAfter("||").takeIf { it.isNotBlank() }
                ?: value.substringBefore("||").takeIf { it.isNotBlank() }
        }

        private fun acceptLanguageHeader(hl: String): String =
            if (hl.startsWith("en")) "en-US,en;q=0.9" else "$hl,en-US;q=0.8,en;q=0.7"

        /** Encodes YouTube's `get_transcript` protobuf parameter: field 1 = videoId (length-delimited). */
        private fun encodeTranscriptParams(videoId: String): String {
            val idBytes = videoId.toByteArray(Charsets.UTF_8)
            val raw = ByteArray(idBytes.size + 2)
            raw[0] = 0x0A
            raw[1] = idBytes.size.toByte()
            System.arraycopy(idBytes, 0, raw, 2, idBytes.size)
            return java.util.Base64.getUrlEncoder().encodeToString(raw)
        }
    }
}
