package com.photoconvert.android.model

import java.util.Locale

enum class OutputFormat(
    val id: String,
    val label: String,
    val extension: String,
    val mimeType: String,
    val isLossless: Boolean,
    val supportsAlpha: Boolean
) {
    WEBP("webp", "WebP", "webp", "image/webp", false, true),
    WEBP_LOSSLESS("webp_lossless", "WebP Lossless", "webp", "image/webp", true, true),
    JPEG("jpeg", "JPEG", "jpg", "image/jpeg", false, false),
    PNG("png", "PNG", "png", "image/png", true, true);

    companion object {
        fun fromId(id: String): OutputFormat =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: WEBP
    }
}

enum class ResizePreset(
    val id: String,
    val label: String
) {
    ORIGINAL("original", "Original"),
    MP_16("mp16", "16 MP"),
    UHD_4K("uhd4k", "4K"),
    FHD_1080P("fhd1080", "1080p"),
    CUSTOM_EDGE("custom", "Custom");

    companion object {
        fun fromId(id: String): ResizePreset =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ORIGINAL
    }
}

enum class AspectRatioOption(
    val id: String,
    val label: String,
    val ratioW: Int,
    val ratioH: Int
) {
    ORIGINAL("original", "Original", 0, 0),
    SQUARE_1_1("1:1", "1:1", 1, 1),
    PORTRAIT_4_5("4:5", "4:5", 4, 5),
    WIDE_16_9("16:9", "16:9", 16, 9),
    STORY_9_16("9:16", "9:16", 9, 16);

    companion object {
        fun fromId(id: String): AspectRatioOption =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ORIGINAL
    }
}

enum class FrameFillMode(val id: String, val label: String) {
    BLUR_BACKDROP("blur", "Blur Backdrop"),
    WHITE_PAD("white", "White Pad"),
    BLACK_PAD("black", "Black Pad"),
    CENTER_CROP("crop", "Center Crop");

    companion object {
        fun fromId(id: String): FrameFillMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: BLUR_BACKDROP
    }
}

enum class WatermarkAnchor(val id: String, val label: String) {
    BOTTOM_RIGHT("bottom-right", "Bottom Right"),
    BOTTOM_LEFT("bottom-left", "Bottom Left"),
    TOP_RIGHT("top-right", "Top Right"),
    TOP_LEFT("top-left", "Top Left"),
    CENTER("center", "Center");

    companion object {
        fun fromId(id: String): WatermarkAnchor =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: BOTTOM_RIGHT
    }
}

enum class PdfPageSize(val id: String, val label: String, val widthPt: Int, val heightPt: Int) {
    A4("a4", "A4", 595, 842),
    LETTER("letter", "US Letter", 612, 792),
    FIT_IMAGE("fit", "Fit Photo", 0, 0)
}

enum class PdfMargin(val id: String, val label: String, val paddingPt: Int) {
    NONE("none", "None", 0),
    SMALL("small", "Small", 24),
    STANDARD("standard", "Standard", 48)
}

data class ConvertSettings(
    val format: OutputFormat = OutputFormat.WEBP,
    val quality: Int = 85,
    val targetSizeKb: Int = 0, // 0 = Quality %, >0 = Fit under exact KB ceiling (#2)
    val resizePreset: ResizePreset = ResizePreset.ORIGINAL,
    val customMaxEdgePx: Int = 2048,
    val stripExif: Boolean = true,
    val repairFilenameDates: Boolean = true, // (#8)
    val normalizeSrgb: Boolean = true, // (#9)
    val removeBackground: Boolean = false, // (#10)
    val aspectRatio: AspectRatioOption = AspectRatioOption.ORIGINAL, // (#7)
    val frameFillMode: FrameFillMode = FrameFillMode.BLUR_BACKDROP, // (#7)
    val watermarkText: String = "", // (#6)
    val watermarkAnchor: WatermarkAnchor = WatermarkAnchor.BOTTOM_RIGHT, // (#6)
    val watermarkOpacity: Int = 65, // (#6)
    val renamePattern: String = "{orig}", // (#8)
    val presetSlug: String = "custom",
    val activePresetName: String = "Custom configuration"
) {
    fun clampedQuality(): Int = quality.coerceIn(10, 100)
    fun clampedCustomEdge(): Int = customMaxEdgePx.coerceIn(128, 16384)
    fun clampedWatermarkOpacity(): Int = watermarkOpacity.coerceIn(15, 100)

    fun effectiveFormat(): OutputFormat =
        if (removeBackground && !format.supportsAlpha) OutputFormat.WEBP else format

    fun activeEnhancementCount(): Int {
        var count = 0
        if (removeBackground) count++
        if (aspectRatio != AspectRatioOption.ORIGINAL) count++
        if (watermarkText.isNotBlank()) count++
        if (renamePattern.trim().ifBlank { "{orig}" } != "{orig}") count++
        return count
    }
}

data class PresetProfile(
    val id: String,
    val name: String,
    val description: String,
    val badge: String,
    val format: OutputFormat,
    val quality: Int,
    val resizePreset: ResizePreset,
    val customMaxEdgePx: Int = 2048,
    val stripExif: Boolean = true,
    val targetSizeKb: Int = 0,
    val normalizeSrgb: Boolean = true,
    val removeBackground: Boolean = false,
    val aspectRatio: AspectRatioOption = AspectRatioOption.ORIGINAL,
    val frameFillMode: FrameFillMode = FrameFillMode.BLUR_BACKDROP,
    val watermarkText: String = "",
    val renamePattern: String = "{orig}",
    val builtIn: Boolean = true
) {
    fun toSettings(): ConvertSettings = ConvertSettings(
        format = format,
        quality = quality,
        targetSizeKb = targetSizeKb,
        resizePreset = resizePreset,
        customMaxEdgePx = customMaxEdgePx,
        stripExif = stripExif,
        normalizeSrgb = normalizeSrgb,
        removeBackground = removeBackground,
        aspectRatio = aspectRatio,
        frameFillMode = frameFillMode,
        watermarkText = watermarkText,
        renamePattern = renamePattern,
        presetSlug = id,
        activePresetName = "Preset: $name"
    )
}

object DefaultPresets {
    val list: List<PresetProfile> = listOf(
        PresetProfile(
            id = "web-webp",
            name = "Web & Messaging WebP",
            description = "Crisp 2048px sRGB WebP at 82% quality with private metadata removed.",
            badge = "Smallest Size",
            format = OutputFormat.WEBP,
            quality = 82,
            resizePreset = ResizePreset.CUSTOM_EDGE,
            customMaxEdgePx = 2048,
            stripExif = true
        ),
        PresetProfile(
            id = "portal-under-500kb",
            name = "Portal & Forms (≤ 500 KB)",
            description = "Binary-searches JPEG quality and resolution to guarantee files fit under 500 KB.",
            badge = "Exact Cap",
            format = OutputFormat.JPEG,
            quality = 88,
            resizePreset = ResizePreset.CUSTOM_EDGE,
            customMaxEdgePx = 2400,
            stripExif = true,
            targetSizeKb = 500
        ),
        PresetProfile(
            id = "social-4x5-blur",
            name = "Social 4:5 Blurred Frame",
            description = "Frames any photo onto a 4:5 portrait canvas with a soft-blurred backdrop.",
            badge = "Social Ready",
            format = OutputFormat.JPEG,
            quality = 88,
            resizePreset = ResizePreset.CUSTOM_EDGE,
            customMaxEdgePx = 2160,
            stripExif = true,
            aspectRatio = AspectRatioOption.PORTRAIT_4_5,
            frameFillMode = FrameFillMode.BLUR_BACKDROP
        ),
        PresetProfile(
            id = "subject-cutout-webp",
            name = "Transparent Subject Cutout",
            description = "Uses offline ML Kit Subject Segmentation to export a clean transparent WebP.",
            badge = "AI Cutout",
            format = OutputFormat.WEBP,
            quality = 90,
            resizePreset = ResizePreset.CUSTOM_EDGE,
            customMaxEdgePx = 2048,
            stripExif = true,
            removeBackground = true
        ),
        PresetProfile(
            id = "storage-saver-16mp",
            name = "16 MP Storage Saver",
            description = "Downscales camera shots above 16 MP to 85% JPEG while repairing filename dates.",
            badge = "Cloud Backup",
            format = OutputFormat.JPEG,
            quality = 85,
            resizePreset = ResizePreset.MP_16,
            stripExif = false
        ),
        PresetProfile(
            id = "uhd-4k-webp",
            name = "4K Archive WebP",
            description = "High-detail 3840px 4K WebP at 90% quality with EXIF timestamps intact.",
            badge = "High Detail",
            format = OutputFormat.WEBP,
            quality = 90,
            resizePreset = ResizePreset.UHD_4K,
            stripExif = false
        ),
        PresetProfile(
            id = "lossless-png",
            name = "Lossless Transparent PNG",
            description = "Pixel-exact PNG preserving alpha transparency at original dimensions.",
            badge = "Lossless",
            format = OutputFormat.PNG,
            quality = 100,
            resizePreset = ResizePreset.ORIGINAL,
            stripExif = true
        )
    )
}

data class ConvertedItem(
    val id: String,
    val originalName: String,
    val outputName: String,
    val originalFormat: String,
    val outputFormat: String,
    val mimeType: String,
    val origWidth: Int,
    val origHeight: Int,
    val outWidth: Int,
    val outHeight: Int,
    val origBytes: Long,
    val outBytes: Long,
    val savedBytes: Long,
    val savedPct: Double,
    val exifKept: Boolean,
    val repairedDate: Boolean = false,
    val cutoutApplied: Boolean = false,
    val localFilePath: String,
    val createdAtEpochMs: Long
)

data class WatchedFolderState(
    val enabled: Boolean = false,
    val treeUriString: String = "",
    val displayPath: String = "Not selected (tap Choose Folder)",
    val processedCount: Int = 0
)

data class StatsSummary(
    val totalConverted: Int = 0,
    val totalOrigBytes: Long = 0L,
    val totalOutBytes: Long = 0L,
    val totalSavedBytes: Long = 0L,
    val avgSavedPct: Double = 0.0
)

object ByteFormatter {
    fun format(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var idx = 0
        while (value >= 1024.0 && idx < units.lastIndex) {
            value /= 1024.0
            idx++
        }
        return if (idx == 0) {
            String.format(Locale.US, "%.0f %s", value, units[idx])
        } else {
            String.format(Locale.US, "%.1f %s", value, units[idx])
        }
    }
}
