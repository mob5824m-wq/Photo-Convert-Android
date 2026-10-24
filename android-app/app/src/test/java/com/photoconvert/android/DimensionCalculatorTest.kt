package com.photoconvert.android

import com.photoconvert.android.engine.DimensionCalculator
import com.photoconvert.android.model.ByteFormatter
import com.photoconvert.android.model.DefaultPresets
import com.photoconvert.android.model.OutputFormat
import com.photoconvert.android.model.ResizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DimensionCalculatorTest {

    @Test
    fun originalPresetKeepsExactDimensions() {
        val (w, h) = DimensionCalculator.calculateTargetDimensions(
            width = 6000,
            height = 4000,
            preset = ResizePreset.ORIGINAL
        )
        assertEquals(6000, w)
        assertEquals(4000, h)
    }

    @Test
    fun storageSaver16MpDownscales24MpMaintainingAspectRatio() {
        val (w, h) = DimensionCalculator.calculateTargetDimensions(
            width = 6000,
            height = 4000,
            preset = ResizePreset.MP_16
        )
        assertTrue(w * h <= 16_000_000)
        val origRatio = 6000.0 / 4000.0
        val outRatio = w.toDouble() / h.toDouble()
        assertEquals(origRatio, outRatio, 0.01)
    }

    @Test
    fun smallerPhotoNeverUpscalesOn4kOr16Mp() {
        val (w1, h1) = DimensionCalculator.calculateTargetDimensions(1600, 1200, ResizePreset.MP_16)
        assertEquals(1600, w1)
        assertEquals(1200, h1)

        val (w2, h2) = DimensionCalculator.calculateTargetDimensions(1600, 1200, ResizePreset.UHD_4K)
        assertEquals(1600, w2)
        assertEquals(1200, h2)
    }

    @Test
    fun fhd1080ClampsPortraitAndLandscapeLongestEdge() {
        val (lw, lh) = DimensionCalculator.calculateTargetDimensions(4000, 3000, ResizePreset.FHD_1080P)
        assertEquals(1920, lw)
        assertEquals(1440, lh)

        val (pw, ph) = DimensionCalculator.calculateTargetDimensions(3000, 4000, ResizePreset.FHD_1080P)
        assertEquals(1440, pw)
        assertEquals(1920, ph)
    }

    @Test
    fun defaultPresetsCoverCoreFormats() {
        assertEquals(5, DefaultPresets.list.size)
        val formats = DefaultPresets.list.map { it.format }.toSet()
        assertTrue(formats.contains(OutputFormat.WEBP))
        assertTrue(formats.contains(OutputFormat.JPEG))
        assertTrue(formats.contains(OutputFormat.PNG))
        assertEquals("1.5 MB", ByteFormatter.format(1_572_864L))
    }
}
