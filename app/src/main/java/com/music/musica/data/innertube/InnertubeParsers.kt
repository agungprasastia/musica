package com.music.musica.data.innertube

import com.music.musica.core.model.AccountProfile
import com.music.musica.core.model.AlbumRef
import com.music.musica.core.model.ArtistRef
import com.music.musica.core.model.BrowsePage
import com.music.musica.core.model.BrowseType
import com.music.musica.core.model.DetailPage
import com.music.musica.core.model.HomeShelf
import com.music.musica.core.model.SearchPage
import com.music.musica.core.model.ShelfItem
import com.music.musica.core.model.Song
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Defensive, null-safe parsers for YouTube Music WEB_REMIX and ANDROID_MUSIC InnerTube JSON payloads.
 */
object InnertubeParsers {

    private val DURATION_REGEX = Regex("""^\d{1,3}[:.]\d{2}([:.]\d{2})?$""")

    fun parseDurationMs(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val trimmed = text.trim()
        if (!DURATION_REGEX.matches(trimmed)) return 0L
        val parts = trimmed.split(':', '.').map { it.toLongOrNull() ?: return 0L }
        return when (parts.size) {
            2 -> if (parts[1] < 60L) (parts[0] * 60L + parts[1]) * 1000L else 0L
            3 -> if (parts[1] < 60L && parts[2] < 60L) {
                (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L
            } else {
                0L
            }
            else -> 0L
        }
    }

    fun parseBrowseHome(root: JsonObject): BrowsePage {
        val sectionList = findSectionList(root)
            ?: root.obj("continuationContents")?.obj("sectionListContinuation")
            ?: return BrowsePage(emptyList())

        val shelves = sectionList.arr("contents")
            ?.mapNotNull { parseShelf(it.asObj()) }
            ?.filter { it.items.isNotEmpty() }
            .orEmpty()

        val continuation = extractContinuation(sectionList)
        return BrowsePage(shelves = shelves, continuation = continuation)
    }

    /**
     * Walks a browse response deeply to collect every musicTwoRowItemRenderer card
     * (used by FEmusic_mixed_for_you where YouTube nests cards across grids and shelves).
     */
    fun parseTwoRowItemsDeep(root: JsonObject): List<ShelfItem> {
        val found = mutableListOf<ShelfItem>()
        fun walk(el: JsonElement) {
            when (el) {
                is JsonObject -> {
                    el.obj("musicTwoRowItemRenderer")?.let { renderer ->
                        parseTwoRowItem(renderer)?.let { found += it }
                        return
                    }
                    el.values.forEach(::walk)
                }
                is JsonArray -> el.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return found.distinctBy { it.id }
    }

    fun parseDetailPage(browseId: String, root: JsonObject): DetailPage {
        val type = inferBrowseType(browseId)
        val twoCol = root.obj("contents")?.obj("twoColumnBrowseResultsRenderer")

        val headerNode = root.obj("header")
            ?: twoCol?.arr("tabs")?.firstOrNull()?.asObj()
                ?.obj("tabRenderer")?.obj("content")?.obj("sectionListRenderer")
                ?.arr("contents")?.firstOrNull()?.asObj()

        val headerRenderer = headerNode?.obj("musicResponsiveHeaderRenderer")
            ?: headerNode?.obj("musicDetailHeaderRenderer")
            ?: headerNode?.obj("musicImmersiveHeaderRenderer")
            ?: headerNode?.obj("musicVisualHeaderRenderer")
            ?: headerNode?.obj("musicHeaderRenderer")

        val title = headerRenderer?.obj("title")?.runsText().orEmpty()
        val straplineRuns = headerRenderer?.obj("straplineTextOne")?.arr("runs")
        val straplineArtist = straplineRuns?.firstOrNull()?.asObj()?.let { run ->
            val artistName = run.str("text")?.trim()?.takeIf { it.isNotBlank() } ?: return@let null
            val artistId = run.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId")
            ArtistRef(name = artistName, browseId = artistId)
        }
        val subtitle = listOfNotNull(
            headerRenderer?.obj("subtitle")?.runsText()?.takeIf { it.isNotBlank() },
            headerRenderer?.obj("secondSubtitle")?.runsText()?.takeIf { it.isNotBlank() },
            headerRenderer?.obj("straplineTextOne")?.runsText()?.takeIf { it.isNotBlank() },
        ).joinToString(" • ").ifBlank { null }

        val description = headerRenderer?.obj("description")
            ?.obj("musicDescriptionShelfRenderer")?.obj("description")?.runsText()
            ?: headerRenderer?.obj("description")?.runsText()

        val thumbnailUrl = extractBestThumbnail(headerRenderer?.obj("thumbnail"))
            ?: extractBestThumbnail(headerRenderer?.obj("foregroundThumbnail"))

        val secondarySectionList = twoCol?.obj("secondaryContents")?.obj("sectionListRenderer")
            ?: findSectionList(root)

        val tracks = mutableListOf<Song>()
        val sections = mutableListOf<HomeShelf>()
        var shelfContinuation: String? = null

        secondarySectionList?.arr("contents")?.forEach { entry ->
            val obj = entry.asObj() ?: return@forEach
            val shelfObj = obj.obj("musicShelfRenderer")
                ?: obj.obj("musicPlaylistShelfRenderer")
                ?: obj.obj("musicCarouselShelfRenderer")
                ?: obj.obj("gridRenderer")
                ?: return@forEach

            if (shelfContinuation == null) {
                shelfContinuation = extractContinuation(shelfObj)
                    ?: extractInlineContinuation(shelfObj.arr("contents"))
            }

            val shelfTitle = extractShelfTitle(shelfObj)
            val parsedItems = parseRendererItems(
                shelfObj.arr("contents") ?: shelfObj.arr("items"),
                fallbackCover = thumbnailUrl,
                fallbackAlbum = if (type == BrowseType.ALBUM && title.isNotBlank()) AlbumRef(title, browseId) else null,
                fallbackArtist = when (type) {
                    BrowseType.ARTIST -> if (title.isNotBlank()) ArtistRef(title, browseId) else null
                    BrowseType.ALBUM -> straplineArtist
                    else -> null
                },
            )

            if (type == BrowseType.ALBUM || type == BrowseType.PLAYLIST) {
                parsedItems.filterIsInstance<ShelfItem.Track>().forEach { tracks += it.song }
                if (parsedItems.any { it !is ShelfItem.Track } && shelfTitle.isNotBlank()) {
                    sections += HomeShelf(title = shelfTitle, items = parsedItems)
                }
            } else {
                if (parsedItems.isNotEmpty()) {
                    if (tracks.isEmpty() && parsedItems.all { it is ShelfItem.Track }) {
                        tracks += parsedItems.filterIsInstance<ShelfItem.Track>().map { it.song }
                    }
                    val moreEndpoint = shelfObj.obj("bottomEndpoint")?.obj("browseEndpoint")
                        ?: shelfObj.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")
                            ?.obj("moreContentButton")?.obj("buttonRenderer")
                            ?.obj("navigationEndpoint")?.obj("browseEndpoint")
                    sections += HomeShelf(
                        title = shelfTitle.ifBlank { title },
                        items = parsedItems,
                        browseId = moreEndpoint?.str("browseId"),
                        params = moreEndpoint?.str("params"),
                    )
                }
            }
        }

        val continuation = shelfContinuation ?: secondarySectionList?.let(::extractContinuation)
        val audioPlaylistId = extractAudioPlaylistId(headerRenderer, root)
            ?: tracks.firstOrNull()?.playlistId?.takeIf { it.startsWith("OLAK5uy_") }
        val resolvedArtist = straplineArtist
            ?: if (type == BrowseType.ALBUM) {
                tracks.firstOrNull()?.artists?.firstOrNull { !it.browseId.isNullOrBlank() }
            } else {
                null
            }
        return DetailPage(
            browseId = browseId,
            type = type,
            title = title,
            subtitle = subtitle,
            description = description,
            thumbnailUrl = thumbnailUrl,
            artist = resolvedArtist,
            tracks = tracks,
            sections = sections,
            continuation = continuation,
            audioPlaylistId = audioPlaylistId,
        )
    }

    /**
     * Parses playlist/detail continuation pages (>100 tracks), supporting both
     * `musicPlaylistShelfContinuation`, `musicShelfContinuation`, and `sectionListContinuation`.
     */
    fun parseDetailContinuation(
        browseId: String,
        root: JsonObject,
        fallbackCover: String? = null,
        fallbackAlbum: AlbumRef? = null,
        fallbackArtist: ArtistRef? = null,
    ): Pair<List<Song>, String?> {
        val contContents = root.obj("continuationContents")
        val shelf = contContents?.obj("musicPlaylistShelfContinuation")
            ?: contContents?.obj("musicShelfContinuation")
        if (shelf != null) {
            val songs = parseRendererItems(
                shelf.arr("contents"),
                fallbackCover = fallbackCover,
                fallbackAlbum = fallbackAlbum,
                fallbackArtist = fallbackArtist,
            )
                .filterIsInstance<ShelfItem.Track>()
                .map { it.song }
            val next = extractContinuation(shelf) ?: extractInlineContinuation(shelf.arr("contents"))
            return songs to next
        }

        val sectionCont = contContents?.obj("sectionListContinuation")
        if (sectionCont != null) {
            val songs = mutableListOf<Song>()
            var nextToken = extractContinuation(sectionCont)
            sectionCont.arr("contents")?.forEach { entry ->
                val obj = entry.asObj() ?: return@forEach
                val inner = obj.obj("musicPlaylistShelfRenderer")
                    ?: obj.obj("musicShelfRenderer")
                    ?: return@forEach
                songs += parseRendererItems(
                    inner.arr("contents"),
                    fallbackCover = fallbackCover,
                    fallbackAlbum = fallbackAlbum,
                    fallbackArtist = fallbackArtist,
                )
                    .filterIsInstance<ShelfItem.Track>()
                    .map { it.song }
                if (nextToken == null) {
                    nextToken = extractContinuation(inner) ?: extractInlineContinuation(inner.arr("contents"))
                }
            }
            return songs to nextToken
        }

        // Also handle onResponseReceivedActions appendContinuationItemsAction format
        val actions = root.arr("onResponseReceivedActions")
        if (actions != null) {
            val songs = mutableListOf<Song>()
            var nextToken: String? = null
            actions.forEach { action ->
                val items = action.asObj()
                    ?.obj("appendContinuationItemsAction")
                    ?.arr("continuationItems")
                    ?: return@forEach
                songs += parseRendererItems(
                    items,
                    fallbackCover = fallbackCover,
                    fallbackAlbum = fallbackAlbum,
                    fallbackArtist = fallbackArtist,
                )
                    .filterIsInstance<ShelfItem.Track>()
                    .map { it.song }
                val inline = extractInlineContinuation(items)
                if (!inline.isNullOrBlank()) nextToken = inline
            }
            return songs to nextToken
        }

        val fallback = parseDetailPage(browseId, root)
        return fallback.tracks to fallback.continuation
    }

    fun parsePlaybackTracking(root: JsonObject): PlaybackTrackingUrls? {
        val tracking = root.obj("playbackTracking") ?: return null
        val playbackUrl = tracking.obj("videostatsPlaybackUrl")?.str("baseUrl")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val atrObj = tracking.obj("atrUrl")
        val atrUrl = atrObj?.str("baseUrl")?.takeIf { it.isNotBlank() }
        val atrDelaySec = atrObj?.str("elapsedMediaTimeSeconds")?.toLongOrNull()
            ?.coerceIn(2L, 30L)
            ?: 5L
        val watchtimeUrl = tracking.obj("videostatsWatchtimeUrl")?.str("baseUrl")
            ?.takeIf { it.isNotBlank() }
        return PlaybackTrackingUrls(
            playbackUrl = playbackUrl,
            atrUrl = atrUrl,
            watchtimeUrl = watchtimeUrl,
            atrAfterSeconds = atrDelaySec,
        )
    }

    fun parseSearchPage(root: JsonObject): SearchPage {
        val continuationShelf = root.obj("continuationContents")?.obj("musicShelfContinuation")
        if (continuationShelf != null) {
            return SearchPage(
                items = parseRendererItems(continuationShelf.arr("contents")),
                continuation = extractContinuation(continuationShelf),
            )
        }

        val sectionList = root.obj("contents")?.obj("tabbedSearchResultsRenderer")
            ?.arr("tabs")?.firstOrNull()?.asObj()
            ?.obj("tabRenderer")?.obj("content")?.obj("sectionListRenderer")
            ?: findSectionList(root)
            ?: return SearchPage(emptyList())

        val items = mutableListOf<ShelfItem>()
        var continuation: String? = null

        sectionList.arr("contents")?.forEach { elem ->
            val obj = elem.asObj() ?: return@forEach
            obj.obj("musicCardShelfRenderer")?.let { card ->
                val top = parseCardShelfTopResult(card)
                if (top != null) items += top
                val topArtist = (top as? ShelfItem.Artist)?.let {
                    ArtistRef(name = it.title, browseId = it.browseId)
                }
                items += parseRendererItems(card.arr("contents"), fallbackArtist = topArtist)
            }
            obj.obj("musicShelfRenderer")?.let { shelf ->
                items += parseRendererItems(shelf.arr("contents"))
                if (continuation == null) continuation = extractContinuation(shelf)
            }
        }

        return SearchPage(items = items.distinctBy { it.id + it::class.simpleName }, continuation = continuation)
    }

    fun parseSearchSuggestions(root: JsonObject): List<String> {
        val results = mutableListOf<String>()
        root.arr("contents")?.forEach { section ->
            section.asObj()?.obj("searchSuggestionsSectionRenderer")?.arr("contents")?.forEach { entry ->
                val text = entry.asObj()
                    ?.obj("searchSuggestionRenderer")
                    ?.obj("suggestion")
                    ?.runsText()
                    ?.trim()
                if (!text.isNullOrBlank()) results += text
            }
        }
        return results.distinct()
    }

    fun parseUpNext(root: JsonObject): List<Song> {
        val tabs = root.obj("contents")
            ?.obj("singleColumnMusicWatchNextResultsRenderer")
            ?.obj("tabbedRenderer")
            ?.obj("watchNextTabbedResultsRenderer")
            ?.arr("tabs")
            ?: return emptyList()

        val songs = mutableListOf<Song>()
        tabs.forEach { tab ->
            val queueContents = tab.asObj()
                ?.obj("tabRenderer")?.obj("content")
                ?.obj("musicQueueRenderer")?.obj("content")
                ?.obj("playlistPanelRenderer")?.arr("contents")
                ?: return@forEach

            queueContents.forEach { item ->
                val renderer = item.asObj()?.obj("playlistPanelVideoRenderer")
                    ?: item.asObj()?.obj("playlistPanelVideoWrapperRenderer")
                        ?.obj("primaryRenderer")?.obj("playlistPanelVideoRenderer")
                    ?: return@forEach

                val videoId = renderer.str("videoId") ?: return@forEach
                val title = renderer.obj("title")?.runsText().orEmpty()
                if (title.isBlank()) return@forEach

                val bylineRuns = renderer.obj("longBylineText")?.arr("runs")
                    ?: renderer.obj("shortBylineText")?.arr("runs")
                val (artists, album) = parseArtistAndAlbumFromRuns(bylineRuns)
                val durationMs = parseDurationMs(renderer.obj("lengthText")?.runsText())
                val thumb = extractBestThumbnail(renderer.obj("thumbnail"))
                val setVideoId = renderer.str("playlistSetVideoId")

                songs += Song(
                    videoId = videoId,
                    title = title,
                    artists = artists,
                    album = album,
                    durationMs = durationMs,
                    thumbnailUrl = thumb,
                    setVideoId = setVideoId,
                )
            }
        }
        return songs
    }

    fun parseLyricsBrowseId(nextRoot: JsonObject): String? {
        val tabs = nextRoot.obj("contents")
            ?.obj("singleColumnMusicWatchNextResultsRenderer")
            ?.obj("tabbedRenderer")
            ?.obj("watchNextTabbedResultsRenderer")
            ?.arr("tabs")
            ?: return null

        for (tab in tabs) {
            val endpoint = tab.asObj()?.obj("tabRenderer")?.obj("endpoint")?.obj("browseEndpoint") ?: continue
            val pageType = endpoint.obj("browseEndpointContextSupportedConfigs")
                ?.obj("browseEndpointContextMusicConfig")
                ?.str("pageType")
            val browseId = endpoint.str("browseId")
            if (pageType == "MUSIC_PAGE_TYPE_TRACK_LYRICS" && !browseId.isNullOrBlank()) {
                return browseId
            }
        }
        return null
    }

    fun parsePlainLyrics(browseRoot: JsonObject): String? {
        val contents = findSectionList(browseRoot)?.arr("contents") ?: return null
        for (item in contents) {
            val desc = item.asObj()?.obj("musicDescriptionShelfRenderer")
                ?.obj("description")?.runsText()
            if (!desc.isNullOrBlank()) return desc
        }
        return null
    }

    fun parseTimedLyricsLines(browseRoot: JsonObject): List<Pair<Long, String>> {
        val timedData = browseRoot.obj("contents")
            ?.obj("elementRenderer")
            ?.obj("newElement")
            ?.obj("type")
            ?.obj("componentType")
            ?.obj("model")
            ?.obj("timedLyricsModel")
            ?.obj("lyricsData")
            ?.arr("timedLyricsData")
            ?: return emptyList()

        return timedData.mapNotNull { entry ->
            val obj = entry.asObj() ?: return@mapNotNull null
            val line = obj.str("lyricLine")?.trim() ?: return@mapNotNull null
            val startMs = obj.obj("cueRange")?.str("startTimeMilliseconds")?.toLongOrNull()
                ?: return@mapNotNull null
            startMs to line
        }
    }

    fun parseAccountProfile(root: JsonObject): AccountProfile? {
        val actions = root.arr("actions") ?: return null
        for (action in actions) {
            val header = action.asObj()
                ?.obj("openPopupAction")?.obj("popup")
                ?.obj("multiPageMenuRenderer")?.obj("header")
                ?.obj("activeAccountHeaderRenderer")
                ?: continue
            val name = header.obj("accountName")?.runsText()?.takeIf { it.isNotBlank() } ?: continue
            val handle = header.obj("channelHandle")?.runsText()?.takeIf { it.isNotBlank() }
            val email = header.obj("email")?.runsText()?.takeIf { it.isNotBlank() }
            val avatar = extractBestThumbnail(header.obj("accountPhoto"))
            return AccountProfile(name = name, handle = handle, email = email, avatarUrl = avatar)
        }
        return null
    }

    // ---- Internal helpers ----

    private fun findSectionList(root: JsonObject): JsonObject? {
        val tabs = root.obj("contents")?.obj("singleColumnBrowseResultsRenderer")?.arr("tabs")
            ?: root.obj("contents")?.obj("twoColumnBrowseResultsRenderer")?.arr("tabs")
            ?: return null
        val selected = tabs.firstOrNull {
            it.asObj()?.obj("tabRenderer")?.bool("selected") == true
        } ?: tabs.firstOrNull()
        return selected?.asObj()?.obj("tabRenderer")?.obj("content")?.obj("sectionListRenderer")
    }

    private fun parseShelf(wrapper: JsonObject?): HomeShelf? {
        if (wrapper == null) return null
        val carousel = wrapper.obj("musicCarouselShelfRenderer")
        if (carousel != null) {
            val header = carousel.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")
            val title = header?.obj("title")?.runsText().orEmpty()
            val subtitle = header?.obj("strapline")?.runsText()
            val moreEndpoint = header?.obj("moreContentButton")?.obj("buttonRenderer")
                ?.obj("navigationEndpoint")?.obj("browseEndpoint")
            val items = parseRendererItems(carousel.arr("contents"))
            return HomeShelf(
                title = title,
                subtitle = subtitle,
                items = items,
                browseId = moreEndpoint?.str("browseId"),
                params = moreEndpoint?.str("params"),
            )
        }

        val listShelf = wrapper.obj("musicShelfRenderer")
        if (listShelf != null) {
            val title = listShelf.obj("title")?.runsText().orEmpty()
            val items = parseRendererItems(listShelf.arr("contents"))
            return HomeShelf(title = title, items = items)
        }

        val grid = wrapper.obj("gridRenderer")
        if (grid != null) {
            val title = grid.obj("header")?.obj("gridHeaderRenderer")?.obj("title")?.runsText().orEmpty()
            val items = parseRendererItems(grid.arr("items"))
            return HomeShelf(title = title, items = items)
        }

        return null
    }

    private fun parseRendererItems(
        array: JsonArray?,
        fallbackCover: String? = null,
        fallbackAlbum: AlbumRef? = null,
        fallbackArtist: ArtistRef? = null,
    ): List<ShelfItem> {
        if (array == null) return emptyList()
        return array.mapNotNull { elem ->
            val obj = elem.asObj() ?: return@mapNotNull null
            obj.obj("musicResponsiveListItemRenderer")?.let {
                return@mapNotNull parseResponsiveListItem(it, fallbackCover, fallbackAlbum, fallbackArtist)
            }
            obj.obj("musicTwoRowItemRenderer")?.let {
                return@mapNotNull parseTwoRowItem(it)
            }
            obj.obj("musicNavigationButtonRenderer")?.let { btn ->
                val title = btn.obj("buttonText")?.runsText() ?: return@mapNotNull null
                val browse = btn.obj("clickCommand")?.obj("browseEndpoint") ?: return@mapNotNull null
                val id = browse.str("browseId") ?: return@mapNotNull null
                val stripeColor = btn.obj("solid")?.str("leftStripeColor")?.toLongOrNull()
                return@mapNotNull ShelfItem.Playlist(
                    browseId = id,
                    title = title,
                    subtitle = null,
                    params = browse.str("params"),
                    accentColorArgb = stripeColor,
                )
            }
            null
        }
    }

    private fun parseResponsiveListItem(
        renderer: JsonObject,
        fallbackCover: String?,
        fallbackAlbum: AlbumRef?,
        fallbackArtist: ArtistRef?,
    ): ShelfItem? {
        val flexColumns = renderer.arr("flexColumns").orEmpty()
        val firstRuns = flexColumns.getOrNull(0)?.asObj()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.arr("runs")
        val secondRuns = flexColumns.getOrNull(1)?.asObj()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.arr("runs")
        val thirdRuns = flexColumns.getOrNull(2)?.asObj()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.arr("runs")

        val title = firstRuns?.runsText().orEmpty().trim()
        if (title.isBlank()) return null

        val thumb = extractBestThumbnail(renderer.obj("thumbnail")) ?: fallbackCover

        val videoId = renderer.obj("playlistItemData")?.str("videoId")
            ?: firstRuns?.firstOrNull()?.asObj()?.obj("navigationEndpoint")?.obj("watchEndpoint")?.str("videoId")
            ?: renderer.obj("overlay")?.obj("musicItemThumbnailOverlayRenderer")
                ?.obj("content")?.obj("musicPlayButtonRenderer")
                ?.obj("playNavigationEndpoint")?.obj("watchEndpoint")?.str("videoId")

        if (!videoId.isNullOrBlank()) {
            val (parsedArtists, parsedAlbum) = parseArtistAndAlbumFromRuns(secondRuns)
            val artists = parsedArtists.ifEmpty { listOfNotNull(fallbackArtist) }
            val album = parsedAlbum ?: parseAlbumFromRuns(thirdRuns) ?: fallbackAlbum

            val fixedDuration = renderer.arr("fixedColumns")?.firstOrNull()?.asObj()
                ?.obj("musicResponsiveListItemFixedColumnRenderer")?.obj("text")?.runsText()
            val durationFromFlex = secondRuns?.lastOrNull()?.asObj()?.str("text")
            val durationMs = parseDurationMs(fixedDuration).takeIf { it > 0L }
                ?: parseDurationMs(durationFromFlex)

            val isExplicit = renderer.arr("badges")?.any { badge ->
                badge.asObj()?.obj("musicInlineBadgeRenderer")?.obj("icon")?.str("iconType") == "MUSIC_EXPLICIT_BADGE"
            } == true

            val setVideoId = renderer.obj("playlistItemData")?.str("playlistSetVideoId")

            return ShelfItem.Track(
                Song(
                    videoId = videoId,
                    title = title,
                    artists = artists,
                    album = album,
                    durationMs = durationMs,
                    thumbnailUrl = thumb,
                    isExplicit = isExplicit,
                    setVideoId = setVideoId,
                ),
            )
        }

        val navEndpoint = renderer.obj("navigationEndpoint")?.obj("browseEndpoint") ?: return null
        val browseId = navEndpoint.str("browseId") ?: return null
        val pageType = navEndpoint.obj("browseEndpointContextSupportedConfigs")
            ?.obj("browseEndpointContextMusicConfig")?.str("pageType")
        val subtitle = secondRuns?.runsText()?.takeIf { it.isNotBlank() }

        return toBrowseShelfItem(browseId, pageType, title, subtitle, thumb)
    }

    private fun parseTwoRowItem(renderer: JsonObject): ShelfItem? {
        val title = renderer.obj("title")?.runsText()?.trim().orEmpty()
        if (title.isBlank()) return null
        val subtitleRuns = renderer.obj("subtitle")?.arr("runs")
        val subtitle = subtitleRuns?.runsText()?.takeIf { it.isNotBlank() }
        val thumb = extractBestThumbnail(renderer.obj("thumbnailRenderer"))

        val overlayPlayNav = renderer.obj("thumbnailOverlay")
            ?.obj("musicItemThumbnailOverlayRenderer")
            ?.obj("content")
            ?.obj("musicPlayButtonRenderer")
            ?.obj("playNavigationEndpoint")
        val nav = renderer.obj("navigationEndpoint") ?: overlayPlayNav

        val browse = nav?.obj("browseEndpoint") ?: overlayPlayNav?.obj("browseEndpoint")
        val rawBrowseId = browse?.str("browseId")
        val mpedVideoId = rawBrowseId?.takeIf { it.startsWith("MPED") }?.removePrefix("MPED")

        val watchEndpoint = nav?.obj("watchEndpoint") ?: overlayPlayNav?.obj("watchEndpoint")
        val watchVideoId = watchEndpoint?.str("videoId") ?: mpedVideoId
        if (!watchVideoId.isNullOrBlank()) {
            val (artists, album) = parseArtistAndAlbumFromRuns(subtitleRuns)
            return ShelfItem.Track(
                Song(
                    videoId = watchVideoId,
                    title = title,
                    artists = artists,
                    album = album,
                    thumbnailUrl = thumb,
                    playlistId = watchEndpoint?.str("playlistId"),
                ),
            )
        }

        if (browse != null && !rawBrowseId.isNullOrBlank()) {
            val pageType = browse.obj("browseEndpointContextSupportedConfigs")
                ?.obj("browseEndpointContextMusicConfig")?.str("pageType")
            return toBrowseShelfItem(rawBrowseId, pageType, title, subtitle, thumb, browse.str("params"))
        }

        // Mixes, Supermix, and Radio cards may carry watchPlaylistEndpoint or playlist-only watchEndpoint
        val watchPlaylistId = nav?.obj("watchPlaylistEndpoint")?.str("playlistId")
            ?: overlayPlayNav?.obj("watchPlaylistEndpoint")?.str("playlistId")
            ?: watchEndpoint?.str("playlistId")
            ?: return null
        val normalizedId = if (watchPlaylistId.startsWith("VL") || watchPlaylistId.startsWith("RD")) {
            watchPlaylistId
        } else {
            "VL$watchPlaylistId"
        }
        return ShelfItem.Playlist(browseId = normalizedId, title = title, subtitle = subtitle, thumbnailUrl = thumb)
    }

    private fun parseCardShelfTopResult(card: JsonObject): ShelfItem? {
        val title = card.obj("title")?.runsText()?.trim().orEmpty()
        if (title.isBlank()) return null
        val subtitleRuns = card.obj("subtitle")?.arr("runs")
        val subtitle = subtitleRuns?.runsText()?.takeIf { it.isNotBlank() }
        val thumb = extractBestThumbnail(card.obj("thumbnail"))
        val nav = card.obj("onTap") ?: card.obj("title")?.arr("runs")?.firstOrNull()?.asObj()?.obj("navigationEndpoint")

        val videoId = nav?.obj("watchEndpoint")?.str("videoId")
        if (!videoId.isNullOrBlank()) {
            val (artists, album) = parseArtistAndAlbumFromRuns(subtitleRuns)
            return ShelfItem.Track(Song(videoId = videoId, title = title, artists = artists, album = album, thumbnailUrl = thumb))
        }
        val browse = nav?.obj("browseEndpoint")
        if (browse != null) {
            val browseId = browse.str("browseId") ?: return null
            val pageType = browse.obj("browseEndpointContextSupportedConfigs")
                ?.obj("browseEndpointContextMusicConfig")?.str("pageType")
            return toBrowseShelfItem(browseId, pageType, title, subtitle, thumb, browse.str("params"))
        }
        val watchPlaylistId = nav?.obj("watchPlaylistEndpoint")?.str("playlistId") ?: return null
        return ShelfItem.Playlist(browseId = watchPlaylistId, title = title, subtitle = subtitle, thumbnailUrl = thumb)
    }

    private fun toBrowseShelfItem(
        browseId: String,
        pageType: String?,
        title: String,
        subtitle: String?,
        thumbnailUrl: String?,
        params: String? = null,
    ): ShelfItem = when {
        pageType == "MUSIC_PAGE_TYPE_ALBUM" || browseId.startsWith("MPREb_") ->
            ShelfItem.Album(browseId = browseId, title = title, subtitle = subtitle, thumbnailUrl = thumbnailUrl)
        pageType == "MUSIC_PAGE_TYPE_ARTIST" || browseId.startsWith("UC") ->
            ShelfItem.Artist(browseId = browseId, title = title, subtitle = subtitle, thumbnailUrl = thumbnailUrl)
        else ->
            ShelfItem.Playlist(browseId = browseId, title = title, subtitle = subtitle, thumbnailUrl = thumbnailUrl, params = params)
    }

    private fun inferBrowseType(browseId: String): BrowseType = when {
        browseId.startsWith("MPREb_") || browseId.startsWith("OLAK5uy_") -> BrowseType.ALBUM
        browseId.startsWith("UC") || browseId.startsWith("FEmusic_library_corpus_track_artists") -> BrowseType.ARTIST
        browseId.startsWith("FEmusic_moods") -> BrowseType.MOOD
        else -> BrowseType.PLAYLIST
    }

    private fun parseArtistAndAlbumFromRuns(runs: JsonArray?): Pair<List<ArtistRef>, AlbumRef?> {
        if (runs == null) return emptyList<ArtistRef>() to null
        val artists = mutableListOf<ArtistRef>()
        var album: AlbumRef? = null
        val plainCandidates = mutableListOf<String>()

        for (run in runs) {
            val obj = run.asObj() ?: continue
            val text = obj.str("text")?.trim().orEmpty()
            if (text.isBlank() || text == "•" || text == "&" || text == ",") continue
            if (parseDurationMs(text) > 0L) continue
            if (isMetadataBadgeLabel(text)) {
                continue
            }

            val browseId = obj.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId")
            val pageType = obj.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?.obj("browseEndpointContextSupportedConfigs")
                ?.obj("browseEndpointContextMusicConfig")?.str("pageType")

            when {
                pageType == "MUSIC_PAGE_TYPE_ALBUM" || browseId?.startsWith("MPREb_") == true ->
                    album = AlbumRef(name = text, browseId = browseId)
                pageType == "MUSIC_PAGE_TYPE_ARTIST" || browseId?.startsWith("UC") == true ->
                    artists += ArtistRef(name = text, browseId = browseId)
                browseId == null -> plainCandidates += text
            }
        }

        if (artists.isEmpty() && plainCandidates.isNotEmpty()) {
            artists += ArtistRef(name = plainCandidates.first())
        }
        return artists to album
    }

    private fun isMetadataBadgeLabel(text: String): Boolean {
        val lower = text.lowercase()
        return lower in setOf("song", "video", "lagu", "album", "single", "ep", "playlist", "podcast") ||
            lower.endsWith("plays") ||
            lower.endsWith("views") ||
            lower.contains("diputar") ||
            lower.contains("ditonton") ||
            lower.matches(Regex("""^\d{4}$"""))
    }

    private fun parseAlbumFromRuns(runs: JsonArray?): AlbumRef? {
        if (runs == null) return null
        val first = runs.firstOrNull()?.asObj() ?: return null
        val text = first.str("text")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val browseId = first.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId")
        return AlbumRef(name = text, browseId = browseId)
    }

    private fun extractBestThumbnail(container: JsonObject?): String? {
        if (container == null) return null
        val list = container.arr("thumbnails")
            ?: container.obj("thumbnail")?.arr("thumbnails")
            ?: container.obj("musicThumbnailRenderer")?.obj("thumbnail")?.arr("thumbnails")
            ?: container.obj("croppedSquareThumbnailRenderer")?.obj("thumbnail")?.arr("thumbnails")
            ?: return null
        val raw = list.lastOrNull()?.asObj()?.str("url")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (raw.startsWith("//")) "https:$raw" else raw
    }

    private fun extractShelfTitle(shelf: JsonObject): String =
        shelf.obj("title")?.runsText()
            ?: shelf.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")?.obj("title")?.runsText()
            ?: shelf.obj("header")?.obj("gridHeaderRenderer")?.obj("title")?.runsText()
            ?: ""

    private fun extractContinuation(node: JsonObject): String? =
        node.arr("continuations")?.firstNotNullOfOrNull { entry ->
            entry.asObj()?.obj("nextContinuationData")?.str("continuation")?.takeIf { it.isNotBlank() }
        }

    private fun extractInlineContinuation(items: JsonArray?): String? {
        if (items == null) return null
        for (item in items) {
            val token = item.asObj()
                ?.obj("continuationItemRenderer")
                ?.obj("continuationEndpoint")
                ?.obj("continuationCommand")
                ?.str("token")
            if (!token.isNullOrBlank()) return token
        }
        return null
    }

    private fun extractAudioPlaylistId(headerRenderer: JsonObject?, root: JsonObject): String? {
        fun findOlak(el: JsonElement?): String? {
            when (el) {
                is JsonObject -> {
                    val pid = el.str("playlistId")
                    if (!pid.isNullOrBlank() && pid.startsWith("OLAK5uy_")) return pid
                    for (value in el.values) {
                        findOlak(value)?.let { return it }
                    }
                }
                is JsonArray -> {
                    for (item in el) {
                        findOlak(item)?.let { return it }
                    }
                }
                else -> Unit
            }
            return null
        }
        return findOlak(headerRenderer) ?: findOlak(root.obj("microformat"))
    }

    private fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonObject.runsText(): String =
        arr("runs")?.runsText() ?: str("simpleText").orEmpty()

    private fun JsonArray.runsText(): String =
        mapNotNull { it.asObj()?.str("text") }.joinToString("")
}
