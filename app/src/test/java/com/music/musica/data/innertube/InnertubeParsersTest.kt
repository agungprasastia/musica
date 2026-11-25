package com.music.musica.data.innertube

import com.music.musica.core.model.BrowseType
import com.music.musica.core.model.ShelfItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InnertubeParsersTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parseDurationMs_handlesMinutesSecondsHoursAndInvalid() {
        assertEquals(225_000L, InnertubeParsers.parseDurationMs("3:45"))
        assertEquals(3_723_000L, InnertubeParsers.parseDurationMs("1:02:03"))
        assertEquals(0L, InnertubeParsers.parseDurationMs(null))
        assertEquals(0L, InnertubeParsers.parseDurationMs("Live"))
    }

    @Test
    fun parseBrowseHome_extractsCarouselTracksAlbumsAndContinuation() {
        val raw = """
        {
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [{
                        "musicCarouselShelfRenderer": {
                          "header": {
                            "musicCarouselShelfBasicHeaderRenderer": {
                              "title": { "runs": [{ "text": "Quick picks" }] }
                            }
                          },
                          "contents": [
                            {
                              "musicResponsiveListItemRenderer": {
                                "playlistItemData": { "videoId": "vid123" },
                                "thumbnail": {
                                  "musicThumbnailRenderer": {
                                    "thumbnail": { "thumbnails": [{ "url": "https://img/1.jpg", "width": 120, "height": 120 }] }
                                  }
                                },
                                "flexColumns": [
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Midnight Rain" }] } } },
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [
                                    { "text": "Luna", "navigationEndpoint": { "browseEndpoint": { "browseId": "UC_luna" } } },
                                    { "text": " • " },
                                    { "text": "3:20" }
                                  ] } } }
                                ]
                              }
                            },
                            {
                              "musicTwoRowItemRenderer": {
                                "title": { "runs": [{ "text": "Starlight LP" }] },
                                "subtitle": { "runs": [{ "text": "Album • 2026" }] },
                                "navigationEndpoint": {
                                  "browseEndpoint": {
                                    "browseId": "MPREb_starlight",
                                    "browseEndpointContextSupportedConfigs": {
                                      "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ALBUM" }
                                    }
                                  }
                                },
                                "thumbnailRenderer": {
                                  "musicThumbnailRenderer": {
                                    "thumbnail": { "thumbnails": [{ "url": "https://img/alb.jpg", "width": 226, "height": 226 }] }
                                  }
                                }
                              }
                            }
                          ]
                        }
                      }],
                      "continuations": [{ "nextContinuationData": { "continuation": "CONT_HOME_2" } }]
                    }
                  }
                }
              }]
            }
          }
        }
        """.trimIndent()

        val page = InnertubeParsers.parseBrowseHome(json.parseToJsonElement(raw).jsonObject)
        assertEquals(1, page.shelves.size)
        assertEquals("Quick picks", page.shelves[0].title)
        assertEquals("CONT_HOME_2", page.continuation)
        assertEquals(2, page.shelves[0].items.size)

        val track = page.shelves[0].items[0] as ShelfItem.Track
        assertEquals("vid123", track.song.videoId)
        assertEquals("Midnight Rain", track.song.title)
        assertEquals("Luna", track.song.artistText)
        assertEquals("UC_luna", track.song.primaryArtistId)
        assertEquals(200_000L, track.song.durationMs)

        val album = page.shelves[0].items[1] as ShelfItem.Album
        assertEquals("MPREb_starlight", album.browseId)
        assertEquals("Starlight LP", album.title)
    }

    @Test
    fun parseBrowseHome_survivesMalformedOrMissingFields() {
        val malformed = """
        {
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [{ "tabRenderer": { "content": { "sectionListRenderer": { "contents": [
                { "musicCarouselShelfRenderer": { "contents": [ { "unknownRenderer": {} } ] } }
              ] } } } }]
            }
          }
        }
        """.trimIndent()
        val page = InnertubeParsers.parseBrowseHome(json.parseToJsonElement(malformed).jsonObject)
        assertTrue(page.shelves.isEmpty())
        assertNull(page.continuation)
    }

    @Test
    fun parseDetailPage_extractsAlbumHeaderAndTracks() {
        val raw = """
        {
          "contents": {
            "twoColumnBrowseResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [{
                        "musicResponsiveHeaderRenderer": {
                          "title": { "runs": [{ "text": "Neon Nights" }] },
                          "subtitle": { "runs": [{ "text": "Album • 2025" }] },
                          "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [{ "url": "https://img/cover.jpg" }] } } }
                        }
                      }]
                    }
                  }
                }
              }],
              "secondaryContents": {
                "sectionListRenderer": {
                  "contents": [{
                    "musicShelfRenderer": {
                      "contents": [{
                        "musicResponsiveListItemRenderer": {
                          "playlistItemData": { "videoId": "track_01" },
                          "flexColumns": [
                            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Track One" }] } } }
                          ],
                          "fixedColumns": [
                            { "musicResponsiveListItemFixedColumnRenderer": { "text": { "runs": [{ "text": "4:05" }] } } }
                          ]
                        }
                      }]
                    }
                  }]
                }
              }
            }
          }
        }
        """.trimIndent()

        val detail = InnertubeParsers.parseDetailPage("MPREb_neon", json.parseToJsonElement(raw).jsonObject)
        assertEquals(BrowseType.ALBUM, detail.type)
        assertEquals("Neon Nights", detail.title)
        assertEquals("https://img/cover.jpg", detail.thumbnailUrl)
        assertEquals(1, detail.tracks.size)
        assertEquals("track_01", detail.tracks[0].videoId)
        assertEquals(245_000L, detail.tracks[0].durationMs)
    }

    @Test
    fun parseUpNext_extractsPlaylistPanelSongsAndLyricsBrowseId() {
        val raw = """
        {
          "contents": {
            "singleColumnMusicWatchNextResultsRenderer": {
              "tabbedRenderer": {
                "watchNextTabbedResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "musicQueueRenderer": {
                            "content": {
                              "playlistPanelRenderer": {
                                "contents": [{
                                  "playlistPanelVideoRenderer": {
                                    "videoId": "up_1",
                                    "title": { "runs": [{ "text": "Afterglow" }] },
                                    "longBylineText": { "runs": [{ "text": "Sol", "navigationEndpoint": { "browseEndpoint": { "browseId": "UC_sol" } } }] },
                                    "lengthText": { "runs": [{ "text": "2:50" }] },
                                    "thumbnail": { "thumbnails": [{ "url": "https://img/up1.jpg" }] }
                                  }
                                }]
                              }
                            }
                          }
                        }
                      }
                    },
                    {
                      "tabRenderer": {
                        "endpoint": {
                          "browseEndpoint": {
                            "browseId": "MPLYt_abc123",
                            "browseEndpointContextSupportedConfigs": {
                              "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_TRACK_LYRICS" }
                            }
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
          }
        }
        """.trimIndent()
        val root = json.parseToJsonElement(raw).jsonObject
        val songs = InnertubeParsers.parseUpNext(root)
        assertEquals(1, songs.size)
        assertEquals("up_1", songs[0].videoId)
        assertEquals("Sol", songs[0].artistText)
        assertEquals(170_000L, songs[0].durationMs)
        assertEquals("MPLYt_abc123", InnertubeParsers.parseLyricsBrowseId(root))
    }

    @Test
    fun parseAccountProfile_extractsNameHandleAndAvatar() {
        val raw = """
        {
          "actions": [{
            "openPopupAction": {
              "popup": {
                "multiPageMenuRenderer": {
                  "header": {
                    "activeAccountHeaderRenderer": {
                      "accountName": { "runs": [{ "text": "Ari" }] },
                      "channelHandle": { "runs": [{ "text": "@ari_music" }] },
                      "accountPhoto": { "thumbnails": [{ "url": "https://img/avatar.jpg" }] }
                    }
                  }
                }
              }
            }
          }]
        }
        """.trimIndent()
        val profile = InnertubeParsers.parseAccountProfile(json.parseToJsonElement(raw).jsonObject)
        assertNotNull(profile)
        assertEquals("Ari", profile?.name)
        assertEquals("@ari_music", profile?.handle)
        assertEquals("https://img/avatar.jpg", profile?.avatarUrl)
    }
}
