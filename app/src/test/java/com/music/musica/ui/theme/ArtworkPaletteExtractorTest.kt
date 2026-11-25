package com.music.musica.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPaletteExtractorTest {

    @Test
    fun derivePalette_guaranteesReadableContrastForExtremeSeedColors() {
        val seeds = listOf(Color.White, Color.Black, Color(0xFFFFEA00), Color(0xFF0014FF), Color(0xFFFA2D48))
        for (dark in listOf(true, false)) {
            for (seed in seeds) {
                val palette = ArtworkPaletteMath.derivePalette(seed, dark)
                val textContrast = ArtworkPaletteMath.contrastRatio(palette.onBackground, palette.background)
                assertTrue(
                    "Expected contrast >= 4.5 for seed=$seed dark=$dark, got $textContrast",
                    textContrast >= 4.5f,
                )
                assertEquals(4, palette.meshColors.size)
            }
        }
    }
}
