package com.music.musica.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncedLyricsParserTest {

    @Test
    fun parseLrc_parsesStandardAndEnhancedWordTimestampsAndHtmlEntities() {
        val lrc = """
            [ti:Test Song]
            [00:12.50]Hello &amp; world &#x27;test&#39;
            [00:16.00]<00:16.00>Shine <00:16.50>bright <00:17.20>tonight
            [00:20.00]
        """.trimIndent()

        val lines = SyncedLyricsParser.parseLrc(lrc)
        assertEquals(2, lines.size)
        assertEquals(12_500L, lines[0].startMs)
        assertEquals("Hello & world 'test'", lines[0].text)
        assertFalse(lines[0].isWordSynced)

        assertEquals(16_000L, lines[1].startMs)
        assertTrue(lines[1].isWordSynced)
        assertEquals(3, lines[1].words.size)
        assertEquals("Shine", lines[1].words[0].text)
        assertEquals(16_000L, lines[1].words[0].startMs)
        assertEquals(16_500L, lines[1].words[0].endMs)
    }

    @Test
    fun parseTtml_extractsSyllablesBackgroundVocalsAndDuetAgents() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head>
                <metadata>
                  <ttm:agent xml:id="v1" type="person" />
                  <ttm:agent xml:id="v2" type="person" />
                  <ttm:agent xml:id="v1000" type="group" />
                </metadata>
              </head>
              <body><div>
                <p ttm:agent="v1" begin="00:00:01.000" end="00:00:04.000">
                  <span begin="00:00:01.000" end="00:00:01.500">ber</span><span begin="00:00:01.500" end="00:00:02.000">sama</span>
                  <span ttm:role="x-bg" begin="00:00:02.000" end="00:00:04.000">
                    <span begin="00:00:02.000" end="00:00:03.000">(echo </span>
                    <span begin="00:00:03.000" end="00:00:04.000">vocal)</span>
                  </span>
                </p>
                <p ttm:agent="v2" begin="00:00:05.000" end="00:00:07.000">
                  <span begin="00:00:05.000" end="00:00:07.000">Answer</span>
                </p>
                <p ttm:agent="v1000" begin="00:00:08.000" end="00:00:10.000">
                  <span begin="00:00:08.000" end="00:00:10.000">Together</span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()

        val lines = SyncedLyricsParser.parseTtml(ttml)
        assertEquals(3, lines.size)

        // Line 0: glued syllables + structural background vocal + START alignment
        assertEquals("bersama", lines[0].text)
        assertEquals(1, lines[0].words.size)
        assertEquals(2, lines[0].words[0].syllables.size)
        assertEquals(LyricAlignment.START, lines[0].alignment)
        val bg = lines[0].background
        assertNotNull(bg)
        assertEquals("(echo vocal)", bg!!.text)
        assertEquals("echo vocal", bg.withoutOuterParentheses().text)

        // Line 1: singer 2 flips to END
        assertEquals(LyricAlignment.END, lines[1].alignment)
        // Line 2: group aligns START
        assertEquals(LyricAlignment.START, lines[2].alignment)
    }

    @Test
    fun assignDuetAlignments_flipsMostlyRightSingleVoiceBackToStart() {
        val agents = List(10) { "v2000" } + listOf("v1")
        val aligned = SyncedLyricsParser.assignDuetAlignments(
            agents = agents,
            agentTypes = mapOf("v2000" to "other", "v1" to "person"),
        )
        assertEquals(LyricAlignment.START, aligned.first())
        assertEquals(LyricAlignment.END, aligned.last())
    }

    @Test
    fun parseKaraokeLrc_parsesQqQrcAndNeteaseYrcFormats() {
        val qrc = """
            [1000,3000]Hel(1000,500)lo (1500,500)world(2000,1000)
            [5000,2000](5000,1000)Next (6000,1000)line
        """.trimIndent()
        val lines = SyncedLyricsParser.parseKaraokeLrc(qrc)
        assertEquals(2, lines.size)
        assertTrue(lines[0].isWordSynced)
        assertEquals(1_000L, lines[0].startMs)
        assertTrue(lines[1].text.contains("Next"))
        assertTrue(lines[1].text.contains("line"))
    }

    @Test
    fun withBackgroundVocals_extractsBalancedTrailingParenthesesWithoutSplittingMidWord() {
        val input = listOf(
            LyricLine(1_000L, 4_000L, "Hold on (hold on)"),
            LyricLine(5_000L, 7_000L, "(Whole line parenthesized)"),
            LyricLine(8_000L, 10_000L, "Still wait(ing)"),
        )
        val out = SyncedLyricsParser.withBackgroundVocals(input)
        assertEquals("Hold on", out[0].text)
        assertEquals("(hold on)", out[0].background?.text)
        assertNull(out[1].background)
        assertNull(out[2].background)
    }

    @Test
    fun withInstrumentalGaps_insertsIntroAndMidSongGapsFor4sSilences() {
        val input = listOf(
            LyricLine(5_000L, 8_000L, "First", sungUntilMs = 8_000L),
            LyricLine(13_500L, 16_000L, "Second", sungUntilMs = 16_000L),
            LyricLine(17_000L, 20_000L, "Third", sungUntilMs = 20_000L),
        )
        val out = SyncedLyricsParser.withInstrumentalGaps(input)
        assertEquals(5, out.size)
        assertTrue(out[0].isGap)
        assertEquals(0L, out[0].startMs)
        assertEquals("First", out[1].text)
        assertTrue(out[2].isGap)
        assertEquals(8_000L, out[2].startMs)
        assertEquals("Second", out[3].text)
        assertEquals("Third", out[4].text)
    }

    @Test
    fun cleanTitleForLyrics_stripsPackagingAndFeatButPreservesVersionTags() {
        assertEquals(
            "Let Down (Acoustic)",
            SyncedLyricsParser.cleanTitleForLyrics("Let Down (Acoustic) (Official Music Video)"),
        )
        assertEquals(
            "Midnight City (Remix)",
            SyncedLyricsParser.cleanTitleForLyrics("Midnight City (Remix) (feat. Guest) [Official Audio]"),
        )
        assertEquals("Radiohead", SyncedLyricsParser.cleanArtistForLyrics("Radiohead - Topic"))
    }

    @Test
    fun activeLineIndex_handlesNegativeAndPositiveOffsetsSafely() {
        val lines = listOf(
            LyricLine(10_000L, 14_000L, "Line 1"),
            LyricLine(15_000L, 19_000L, "Line 2"),
        )
        assertEquals(-1, SyncedLyricsParser.activeLineIndex(emptyList(), 5_000L))
        assertEquals(-1, SyncedLyricsParser.activeLineIndex(lines, 5_000L, offsetMs = 0L))
        assertEquals(0, SyncedLyricsParser.activeLineIndex(lines, 11_000L, offsetMs = 0L))
        assertEquals(1, SyncedLyricsParser.activeLineIndex(lines, 11_000L, offsetMs = 5_000L))
        assertEquals(-1, SyncedLyricsParser.activeLineIndex(lines, 11_000L, offsetMs = -5_000L))
    }

    @Test
    fun isSectionHeader_detectsGeniusBracketSections() {
        assertTrue(SyncedLyricsParser.isSectionHeader("[Chorus]"))
        assertTrue(SyncedLyricsParser.isSectionHeader("[Verse 2: Radiohead]"))
        assertFalse(SyncedLyricsParser.isSectionHeader("Regular lyric line"))
    }
}
