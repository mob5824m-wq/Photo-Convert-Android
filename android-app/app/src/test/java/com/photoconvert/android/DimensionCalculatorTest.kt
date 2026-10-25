package com.photoconvert.android

import com.photoconvert.android.engine.BatchRenamer
import com.photoconvert.android.engine.BurstDeduplicator
import com.photoconvert.android.engine.DimensionCalculator
import com.photoconvert.android.engine.PrivacyRedactor
import com.photoconvert.android.model.AspectRatioOption
import com.photoconvert.android.model.ByteFormatter
import com.photoconvert.android.model.CollageLayout
import com.photoconvert.android.model.ConvertSettings
import com.photoconvert.android.model.DefaultPresets
import com.photoconvert.android.model.ExifPrivacyMode
import com.photoconvert.android.model.OutputFormat
import com.photoconvert.android.model.ResizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun batchRenamerAndFilenameDateInferenceWork() {
        assertEquals(
            "2026:10:18 18:42:10",
            BatchRenamer.inferExifDateFromFilename("IMG_20261018_184210_Sunset.jpg")
        )
        assertEquals(
            "2026:10:20 12:00:00",
            BatchRenamer.inferExifDateFromFilename("IMG-20261020-WA0042.jpg")
        )

        val rendered = BatchRenamer.renderOutputFilename(
            pattern = "{date}_{orig}_{width}x{height}_{seq}",
            originalFilename = "IMG_20261018_184210.jpg",
            extension = "webp",
            width = 1920,
            height = 1080,
            seq = 3
        )
        assertEquals("20261018_IMG_20261018_184210_1920x1080_003.webp", rendered)
    }

    @Test
    fun cutoutAutoPromotesOpaqueFormatAndCountsEnhancements() {
        val cfg = ConvertSettings(
            format = OutputFormat.JPEG,
            removeBackground = true,
            autoDeskew = true,
            postSharpen = true,
            redactFaces = true,
            aspectRatio = AspectRatioOption.PORTRAIT_4_5,
            watermarkText = "© Studio"
        )
        assertEquals(OutputFormat.WEBP, cfg.effectiveFormat())
        assertEquals(6, cfg.activeEnhancementCount())
    }

    @Test
    fun privacyRedactorAndBurstDhashHelpersWork() {
        assertTrue(PrivacyRedactor.looksSensitive("alice@example.com"))
        assertTrue(PrivacyRedactor.looksSensitive("Card 4532 8891 2201 9910"))
        assertTrue(PrivacyRedactor.looksSensitive("ABC-1234"))
        assertFalse(PrivacyRedactor.looksSensitive("Hello"))

        assertEquals(0, BurstDeduplicator.hammingDistance(0x123456789ABCDEF0L, 0x123456789ABCDEF0L))
        assertEquals(4, BurstDeduplicator.hammingDistance(0xFL, 0x0L))
    }

    @Test
    fun defaultPresetsCoverAllCapabilities() {
        assertEquals(10, DefaultPresets.list.size)
        assertTrue(DefaultPresets.list.any { it.targetSizeKb == 500 })
        assertTrue(DefaultPresets.list.any { it.removeBackground })
        assertTrue(DefaultPresets.list.any { it.autoDeskew })
        assertTrue(DefaultPresets.list.any { it.watermarkTiled })
        assertTrue(DefaultPresets.list.any { it.exifPrivacyMode == ExifPrivacyMode.SCRUB_GPS_ONLY })
        assertEquals(CollageLayout.ID_CARD_2UP, CollageLayout.fromId("id_card_2up"))
        assertEquals("1.5 MB", ByteFormatter.format(1_572_864L))
    }
}
