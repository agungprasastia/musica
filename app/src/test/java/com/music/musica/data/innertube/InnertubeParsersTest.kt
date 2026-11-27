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
    fun parseDurationMs_handlesMinutesSecondsHoursDotsAndInvalid() {
        assertEquals(225_000L, InnertubeParsers.parseDurationMs("3:45"))
        assertEquals(213_000L, InnertubeParsers.parseDurationMs("3.33"))
        assertEquals(605_000L, InnertubeParsers.parseDurationMs("10.05"))
        assertEquals(3_723_000L, InnertubeParsers.parseDurationMs("1:02:03"))
        assertEquals(3_723_000L, InnertubeParsers.parseDurationMs("1.02.03"))
        assertEquals(0L, InnertubeParsers.parseDurationMs("3.99"))
        assertEquals(0L, InnertubeParsers.parseDurationMs("2024"))
        assertEquals(0L, InnertubeParsers.parseDurationMs("1.2"))
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

    @Test
    fun parseDetailContinuationAndSearchContinuationExtractTracksAndNextTokens() {
        val detailContJson = """
        {
          "continuationContents": {
            "musicPlaylistShelfContinuation": {
              "contents": [{
                "musicResponsiveListItemRenderer": {
                  "playlistItemData": { "videoId": "vid_101", "playlistSetVideoId": "sv_101" },
                  "flexColumns": [
                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Song 101" }] } } },
                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Nova" }] } } }
                  ]
                }
              }],
              "continuations": [{ "nextContinuationData": { "continuation": "CONT_PAGE_3" } }]
            }
          }
        }
        """.trimIndent()
        val (tracks, nextDetail) = InnertubeParsers.parseDetailContinuation(
            "VLPL_BIG",
            json.parseToJsonElement(detailContJson).jsonObject,
        )
        assertEquals(1, tracks.size)
        assertEquals("vid_101", tracks[0].videoId)
        assertEquals("sv_101", tracks[0].setVideoId)
        assertEquals("CONT_PAGE_3", nextDetail)

        val searchContJson = """
        {
          "continuationContents": {
            "musicShelfContinuation": {
              "contents": [{
                "musicResponsiveListItemRenderer": {
                  "playlistItemData": { "videoId": "search_21" },
                  "flexColumns": [
                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Page 2 Song" }] } } }
                  ]
                }
              }],
              "continuations": [{ "nextContinuationData": { "continuation": "SEARCH_NEXT_3" } }]
            }
          }
        }
        """.trimIndent()
        val searchPage = InnertubeParsers.parseSearchPage(json.parseToJsonElement(searchContJson).jsonObject)
        assertEquals(1, searchPage.items.size)
        assertEquals("SEARCH_NEXT_3", searchPage.continuation)
    }

    @Test
    fun parseDetailPageExtractsAlbumStraplineArtistAndArtistSeeAllParams() {
        val albumJson = """
        {
          "header": {
            "musicResponsiveHeaderRenderer": {
              "title": { "runs": [{ "text": "Solaris" }] },
              "straplineTextOne": {
                "runs": [{
                  "text": "Klara",
                  "navigationEndpoint": { "browseEndpoint": { "browseId": "UC_klara" } }
                }]
              }
            }
          },
          "contents": { "twoColumnBrowseResultsRenderer": { "secondaryContents": { "sectionListRenderer": { "contents": [] } } } }
        }
        """.trimIndent()
        val album = InnertubeParsers.parseDetailPage("MPREb_solaris", json.parseToJsonElement(albumJson).jsonObject)
        assertEquals("Klara", album.artist?.name)
        assertEquals("UC_klara", album.artist?.browseId)

        val artistJson = """
        {
          "header": { "musicImmersiveHeaderRenderer": { "title": { "runs": [{ "text": "Klara" }] } } },
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "selected": true,
                  "content": {
                    "sectionListRenderer": {
                      "contents": [{
                        "musicCarouselShelfRenderer": {
                          "header": {
                            "musicCarouselShelfBasicHeaderRenderer": {
                              "title": { "runs": [{ "text": "Albums" }] },
                              "moreContentButton": {
                                "buttonRenderer": {
                                  "navigationEndpoint": {
                                    "browseEndpoint": { "browseId": "MPADUC_klara", "params": "ALBUMS_PARAM_1" }
                                  }
                                }
                              }
                            }
                          },
                          "contents": [{
                            "musicTwoRowItemRenderer": {
                              "title": { "runs": [{ "text": "Solaris" }] },
                              "navigationEndpoint": { "browseEndpoint": { "browseId": "MPREb_solaris" } }
                            }
                          }]
                        }
                      }]
                    }
                  }
                }
              }]
            }
          }
        }
        """.trimIndent()
        val artist = InnertubeParsers.parseDetailPage("UC_klara", json.parseToJsonElement(artistJson).jsonObject)
        assertEquals(1, artist.sections.size)
        assertEquals("MPADUC_klara", artist.sections[0].browseId)
        assertEquals("ALBUMS_PARAM_1", artist.sections[0].params)
    }

    @Test
    fun parseSearchPage_indonesianDotDurationInheritsTopResultArtistAndIgnoresReloadContinuation() {
        val raw = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "musicCardShelfRenderer": {
                            "title": { "runs": [{ "text": "Tulus" }] },
                            "subtitle": { "runs": [{ "text": "Artis" }] },
                            "onTap": {
                              "browseEndpoint": {
                                "browseId": "UC_tulus",
                                "browseEndpointContextSupportedConfigs": {
                                  "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ARTIST" }
                                }
                              }
                            },
                            "contents": [
                              {
                                "musicResponsiveListItemRenderer": {
                                  "playlistItemData": { "videoId": "vid_monokrom" },
                                  "flexColumns": [
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Monokrom" }] } } },
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [
                                      { "text": "Lagu" },
                                      { "text": " • " },
                                      { "text": "3.33" }
                                    ] } } }
                                  ]
                                }
                              }
                            ]
                          }
                        },
                        {
                          "musicShelfRenderer": {
                            "contents": [],
                            "continuations": [{ "reloadContinuationData": { "continuation": "SHOULD_IGNORE_RELOAD" } }]
                          }
                        }
                      ]
                    }
                  }
                }
              }]
            }
          }
        }
        """.trimIndent()

        val page = InnertubeParsers.parseSearchPage(json.parseToJsonElement(raw).jsonObject)
        assertEquals(2, page.items.size)
        assertNull(page.continuation)
        val song = (page.items[1] as ShelfItem.Track).song
        assertEquals("Monokrom", song.title)
        assertEquals("Tulus", song.artistText)
        assertEquals("UC_tulus", song.primaryArtistId)
        assertEquals(213_000L, song.durationMs)
    }

    @Test
    fun parseDetailPage_extractsAudioPlaylistIdAndInlineContinuation() {
        val raw = """
        {
          "header": {
            "musicResponsiveHeaderRenderer": {
              "title": { "runs": [{ "text": "Manusia" }] },
              "buttons": [{
                "musicPlayButtonRenderer": {
                  "playNavigationEndpoint": {
                    "watchPlaylistEndpoint": { "playlistId": "OLAK5uy_manusia123" }
                  }
                }
              }]
            }
          },
          "contents": {
            "twoColumnBrowseResultsRenderer": {
              "secondaryContents": {
                "sectionListRenderer": {
                  "contents": [{
                    "musicPlaylistShelfRenderer": {
                      "contents": [
                        {
                          "continuationItemRenderer": {
                            "continuationEndpoint": {
                              "continuationCommand": { "token": "INLINE_CONT_2" }
                            }
                          }
                        }
                      ]
                    }
                  }]
                }
              }
            }
          }
        }
        """.trimIndent()
        val detail = InnertubeParsers.parseDetailPage("MPREb_manusia", json.parseToJsonElement(raw).jsonObject)
        assertEquals("OLAK5uy_manusia123", detail.audioPlaylistId)
        assertEquals("INLINE_CONT_2", detail.continuation)
    }

    @Test
    fun upsizeArtworkUrl_preservesSignedUrlsAndResizesPlainGoogleUrls() {
        val plain = "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj",
            com.music.musica.core.model.upsizeArtworkUrl(plain, 544),
        )
        val signedQuery = "https://lh3.googleusercontent.com/xyz=w226-h226-l90-rj?sqp=123&rs=456"
        assertEquals(signedQuery, com.music.musica.core.model.upsizeArtworkUrl(signedQuery, 544))
        val signedDashS = "https://lh3.googleusercontent.com/xyz=w226-h226-s-l90-rj"
        assertEquals(signedDashS, com.music.musica.core.model.upsizeArtworkUrl(signedDashS, 72))
    }

    @Test
    fun smartCropArtworkUrl_handlesPortraitGoogleAndMzstaticUrls() {
        val banner = "https://lh3.googleusercontent.com/artist_banner=w1200-h500-l90-rj"
        assertEquals(
            "https://lh3.googleusercontent.com/artist_banner=w600-h800-p-l90-rj",
            com.music.musica.core.model.smartCropArtworkUrl(banner, 600, 800),
        )
        val portrait = "https://is1-ssl.mzstatic.com/image/thumb/AMCArtistImages221/v4/aa/bb/cc/file.jpg/1200x630cw.jpg"
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/AMCArtistImages221/v4/aa/bb/cc/file.jpg/800x800cc.jpg",
            com.music.musica.core.model.smartCropArtworkUrl(portrait, 600, 800),
        )
    }

    @Test
    fun parseDetailPage_extractsArtistMonthlyListenersSubscribersRadioAndShuffleTargets() {
        val raw = """
        {
          "header": {
            "musicImmersiveHeaderRenderer": {
              "title": { "runs": [{ "text": "Ave Mujica" }] },
              "monthlyListenerCount": { "runs": [{ "text": "228K monthly audience" }] },
              "subscriptionButton2": {
                "subscribeButtonRenderer": {
                  "subscriberCountWithSubscribeText": { "runs": [{ "text": "180K subscribers" }] }
                }
              },
              "startRadioButton": {
                "buttonRenderer": {
                  "navigationEndpoint": {
                    "watchEndpoint": {
                      "playlistId": "RDEM_avemujica",
                      "videoId": "seed_vid_1",
                      "params": "wAEB%3D"
                    }
                  }
                }
              },
              "playButton": {
                "buttonRenderer": {
                  "navigationEndpoint": {
                    "watchEndpoint": {
                      "playlistId": "RDAO_avemujica",
                      "videoId": "shuf_vid_1",
                      "params": "shuf%3D"
                    }
                  }
                }
              }
            }
          },
          "contents": { "singleColumnBrowseResultsRenderer": { "tabs": [] } }
        }
        """.trimIndent()

        val page = InnertubeParsers.parseDetailPage("UC_avemujica", json.parseToJsonElement(raw).jsonObject)
        assertEquals("Ave Mujica", page.title)
        assertEquals("228K monthly audience", page.monthlyListenerCount)
        assertEquals("180K subscribers", page.subscriberCountText)
        assertEquals("RDEM_avemujica", page.radioTarget?.playlistId)
        assertEquals("seed_vid_1", page.radioTarget?.videoId)
        assertEquals("wAEB=", page.radioTarget?.params)
        assertEquals("RDAO_avemujica", page.shuffleTarget?.playlistId)
        assertEquals("shuf_vid_1", page.shuffleTarget?.videoId)
        assertEquals("shuf=", page.shuffleTarget?.params)
    }

    @Test
    fun parseUpNext_preferAudioSwapsPairedVideoToAudioCounterpart() {
        val raw = """
        {
          "contents": {
            "singleColumnMusicWatchNextResultsRenderer": {
              "tabbedRenderer": {
                "watchNextTabbedResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "content": {
                        "musicQueueRenderer": {
                          "content": {
                            "playlistPanelRenderer": {
                              "contents": [{
                                "playlistPanelVideoWrapperRenderer": {
                                  "primaryRenderer": {
                                    "playlistPanelVideoRenderer": {
                                      "videoId": "mv_video_id",
                                      "title": { "runs": [{ "text": "Song (Official Video)" }] },
                                      "longBylineText": { "runs": [{ "text": "Artist A" }] },
                                      "navigationEndpoint": {
                                        "watchEndpoint": {
                                          "watchEndpointMusicSupportedConfigs": {
                                            "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_OMV" }
                                          }
                                        }
                                      }
                                    }
                                  },
                                  "counterpart": [{
                                    "counterpartRenderer": {
                                      "playlistPanelVideoRenderer": {
                                        "videoId": "atv_audio_id",
                                        "title": { "runs": [{ "text": "Song" }] },
                                        "longBylineText": { "runs": [{ "text": "Artist A" }] },
                                        "navigationEndpoint": {
                                          "watchEndpoint": {
                                            "watchEndpointMusicSupportedConfigs": {
                                              "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_ATV" }
                                            }
                                          }
                                        }
                                      }
                                    }
                                  }]
                                }
                              }]
                            }
                          }
                        }
                      }
                    }
                  }]
                }
              }
            }
          }
        }
        """.trimIndent()
        val root = json.parseToJsonElement(raw).jsonObject
        val defaultQueue = InnertubeParsers.parseUpNext(root, preferAudio = false)
        assertEquals("mv_video_id", defaultQueue.single().videoId)

        val audioQueue = InnertubeParsers.parseUpNext(root, preferAudio = true)
        assertEquals("atv_audio_id", audioQueue.single().videoId)
    }

    @Test
    fun parseHistorySongs_collectsAndDeduplicatesInRecencyOrder() {
        val raw = """
        {
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [{
                        "musicShelfRenderer": {
                          "contents": [
                            {
                              "musicResponsiveListItemRenderer": {
                                "playlistItemData": { "videoId": "v_recent" },
                                "flexColumns": [
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Newest" }] } } },
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Alpha", "navigationEndpoint": { "browseEndpoint": { "browseId": "UC_alpha" } } }] } } }
                                ]
                              }
                            },
                            {
                              "musicResponsiveListItemRenderer": {
                                "playlistItemData": { "videoId": "v_older" },
                                "flexColumns": [
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Older" }] } } },
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Beta", "navigationEndpoint": { "browseEndpoint": { "browseId": "UC_beta" } } }] } } }
                                ]
                              }
                            },
                            {
                              "musicResponsiveListItemRenderer": {
                                "playlistItemData": { "videoId": "v_recent" },
                                "flexColumns": [
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Newest Duplicate" }] } } }
                                ]
                              }
                            }
                          ]
                        }
                      }]
                    }
                  }
                }
              }]
            }
          }
        }
        """.trimIndent()

        val history = InnertubeParsers.parseHistorySongs(json.parseToJsonElement(raw).jsonObject)
        assertEquals(listOf("v_recent", "v_older"), history.map { it.videoId })
        assertEquals("UC_alpha", history[0].primaryArtistId)
    }
}
