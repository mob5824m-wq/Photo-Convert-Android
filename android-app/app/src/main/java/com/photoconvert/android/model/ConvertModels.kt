package com.photoconvert.android.model

import java.util.Locale

enum class OutputFormat(
    val id: String,
    val label: String,
    val extension: String,
    val mimeType: String,
    val isLossless: Boolean
) {
    WEBP("webp", "WebP", "webp", "image/webp", false),
    JPEG("jpeg", "JPEG", "jpg", "image/jpeg", false),
    PNG("png", "PNG", "png", "image/png", true);

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

data class ConvertSettings(
    val format: OutputFormat = OutputFormat.WEBP,
    val quality: Int = 85,
    val resizePreset: ResizePreset = ResizePreset.ORIGINAL,
    val customMaxEdgePx: Int = 2048,
    val stripExif: Boolean = true,
    val activePresetName: String = "Custom configuration"
) {
    fun clampedQuality(): Int = quality.coerceIn(10, 100)
    fun clampedCustomEdge(): Int = customMaxEdgePx.coerceIn(128, 16384)
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
    val builtIn: Boolean = true
) {
    fun toSettings(): ConvertSettings = ConvertSettings(
        format = format,
        quality = quality,
        resizePreset = resizePreset,
        customMaxEdgePx = customMaxEdgePx,
        stripExif = stripExif,
        activePresetName = "Preset: $name"
    )
}

object DefaultPresets {
    val list: List<PresetProfile> = listOf(
        PresetProfile(
            id = "web-webp",
            name = "Web & Messaging WebP",
            description = "Crisp 2048px WebP at 82% quality with private metadata removed.",
            badge = "Smallest Size",
            format = OutputFormat.WEBP,
            quality = 82,
            resizePreset = ResizePreset.CUSTOM_EDGE,
            customMaxEdgePx = 2048,
            stripExif = true
        ),
        PresetProfile(
            id = "storage-saver-16mp",
            name = "16 MP Storage Saver",
            description = "Downscales camera shots above 16 MP to 85% JPEG while keeping capture dates.",
            badge = "Cloud Backup",
            format = OutputFormat.JPEG,
            quality = 85,
            resizePreset = ResizePreset.MP_16,
            stripExif = false
        ),
        PresetProfile(
            id = "privacy-share",
            name = "Privacy Share JPEG",
            description = "Strips GPS coordinates and camera serials at clean 1080p HD.",
            badge = "Zero Metadata",
            format = OutputFormat.JPEG,
            quality = 84,
            resizePreset = ResizePreset.FHD_1080P,
            stripExif = true
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
    val localFilePath: String,
    val createdAtEpochMs: Long
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
