package com.music.musica.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncedLyricsParserTest {

    @Test
    fun parseLrc_parsesStandardAndEnhancedWordTimestamps() {
        val lrc = """
            [ti:Test Song]
            [00:12.50]Hello world
            [00:16.00]<00:16.00>Shine <00:16.50>bright <00:17.20>tonight
            [00:20.00]
        """.trimIndent()

        val lines = SyncedLyricsParser.parseLrc(lrc)
        assertEquals(2, lines.size)
        assertEquals(12_500L, lines[0].startMs)
        assertEquals("Hello world", lines[0].text)
        assertFalse(lines[0].isWordSynced)

        assertEquals(16_000L, lines[1].startMs)
        assertTrue(lines[1].isWordSynced)
        assertEquals(3, lines[1].words.size)
        assertEquals("Shine", lines[1].words[0].text)
        assertEquals(16_000L, lines[1].words[0].startMs)
        assertEquals(16_500L, lines[1].words[0].endMs)
    }

    @Test
    fun parseTtml_extractsSyllableSpansAndHandlesZeroDuration() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:00:05.000" end="00:00:08.500">
                    <span begin="00:00:05.000" end="00:00:06.200">Night </span>
                    <span begin="00:00:06.200" end="00:00:06.200">falls</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lines = SyncedLyricsParser.parseTtml(ttml)
        assertEquals(1, lines.size)
        assertEquals(5_000L, lines[0].startMs)
        assertEquals(8_500L, lines[0].endMs)
        assertEquals(2, lines[0].words.size)
        assertTrue(lines[0].words[1].endMs >= lines[0].words[1].startMs)
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
        // +5000ms shift moves effective clock to 16_000ms -> Line 2
        assertEquals(1, SyncedLyricsParser.activeLineIndex(lines, 11_000L, offsetMs = 5_000L))
        // -5000ms shift moves effective clock to 6_000ms -> before Line 1
        assertEquals(-1, SyncedLyricsParser.activeLineIndex(lines, 11_000L, offsetMs = -5_000L))
    }

    @Test
    fun parseTtml_doesNotDuplicateNestedBackgroundVocalSpans() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body><div>
                <p begin="00:00:01.000" end="00:00:04.000">
                  <span begin="00:00:01.000" end="00:00:02.000">Lead</span>
                  <span ttm:role="x-bg" begin="00:00:02.000" end="00:00:04.000">
                    <span begin="00:00:02.000" end="00:00:03.000">(echo</span>
                    <span begin="00:00:03.000" end="00:00:04.000">vocal)</span>
                  </span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()
        val lines = SyncedLyricsParser.parseTtml(ttml)
        assertEquals(1, lines.size)
        assertEquals(listOf("Lead", "(echo", "vocal)"), lines[0].words.map { it.text })
    }

    @Test
    fun parseTtml_preservesSyllableJoinsWithoutSplittingWords() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="00:00:10.000" end="00:00:13.000"><span begin="00:00:10.000" end="00:00:10.500">ber</span><span begin="00:00:10.500" end="00:00:11.000">sa</span><span begin="00:00:11.000" end="00:00:11.500">ma</span> <span begin="00:00:11.600" end="00:00:12.000">ken</span><span begin="00:00:12.000" end="00:00:12.400">da</span><span begin="00:00:12.400" end="00:00:13.000">la</span></p>
              </div></body>
            </tt>
        """.trimIndent()
        val lines = SyncedLyricsParser.parseTtml(ttml)
        assertEquals(1, lines.size)
        assertEquals("bersama kendala", lines[0].text)
        assertFalse(lines[0].words[0].trailingSpace)
        assertFalse(lines[0].words[1].trailingSpace)
        assertTrue(lines[0].words[2].trailingSpace)
    }
}
