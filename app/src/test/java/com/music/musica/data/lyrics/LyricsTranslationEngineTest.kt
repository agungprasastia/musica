package com.music.musica.data.lyrics

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LyricsTranslationEngineTest {

    @Test
    fun romanize_fastExitsAlreadyRomanizedForPureLatinLines() = runTest {
        var calls = 0
        val engine = LyricsTranslationEngine(
            translateBatchTransport = { _, _, _, _ ->
                calls++
                null
            },
        )
        val lines = listOf(LyricLine(1_000L, 3_000L, "Let down and hanging around"))
        val outcome = engine.romanize("vid1", lines)
        assertTrue(outcome is LyricsTranslationOutcome.AlreadyRomanized)
        assertEquals(0, calls)
    }

    @Test
    fun translate_preservesTimingSourceBackgroundAndUsesGzipDiskCache() = runTest {
        val tmpDir = Files.createTempDirectory("musica_tr_test").toFile()
        try {
            var networkCalls = 0
            val origLine = LyricLine(
                startMs = 2_000L,
                endMs = 6_000L,
                text = "夜に駆ける",
                words = listOf(LyricWord(2_000L, 6_000L, "夜に駆ける", trailingSpace = false)),
                sungUntilMs = 6_000L,
                background = LyricLine(4_000L, 6_000L, "(走る)", isBackgroundVocal = true),
            )
            val lines = listOf(
                LyricLine(0L, 2_000L, ""), // gap
                origLine,
            )

            val transport: suspend (String, String, String, String) -> LyricsTranslationEngine.TranslateBatchResponse =
                { text, _, _, _ ->
                    networkCalls++
                    val out = text
                        .replace("夜に駆ける", "Racing into the night")
                        .replace("走る", "Running")
                    LyricsTranslationEngine.TranslateBatchResponse(
                        translatedText = out,
                        romanizedText = "",
                        detectedSourceLang = "ja",
                    )
                }

            val engine1 = LyricsTranslationEngine(cacheDir = tmpDir, translateBatchTransport = transport)
            val res1 = engine1.translate("vid_jp", lines, targetLanguageCode = "en", targetDisplayName = "English")
            assertTrue(res1 is LyricsTranslationOutcome.Success)
            val translated = (res1 as LyricsTranslationOutcome.Success).lines
            assertEquals(2, translated.size)
            assertTrue(translated[0].isGap)
            assertEquals("Racing into the night", translated[1].text)
            assertSame(origLine, translated[1].timingSource)
            assertEquals("Running", translated[1].background?.text)
            assertEquals(1, networkCalls)

            // Fresh engine reading same disk cache hits 0 network calls
            val engine2 = LyricsTranslationEngine(cacheDir = tmpDir, translateBatchTransport = transport)
            val res2 = engine2.translate("vid_jp", lines, targetLanguageCode = "en", targetDisplayName = "English")
            assertTrue(res2 is LyricsTranslationOutcome.Success)
            assertEquals("Racing into the night", (res2 as LyricsTranslationOutcome.Success).lines[1].text)
            assertEquals(1, networkCalls)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun normalizeLanguageTag_handlesLegacyAndChineseScripts() {
        assertEquals("id", LyricsTranslationEngine.normalizeLanguageTag("in-ID"))
        assertEquals("he", LyricsTranslationEngine.normalizeLanguageTag("iw"))
        assertEquals("zh-TW", LyricsTranslationEngine.normalizeLanguageTag("zh-Hant-TW"))
        assertEquals("zh-CN", LyricsTranslationEngine.normalizeLanguageTag("zh-CN"))
    }
}
