package com.photoconvert.android.data

import android.content.Context
import com.photoconvert.android.model.AspectRatioOption
import com.photoconvert.android.model.ConvertSettings
import com.photoconvert.android.model.ConvertedItem
import com.photoconvert.android.model.DefaultPresets
import com.photoconvert.android.model.FrameFillMode
import com.photoconvert.android.model.OutputFormat
import com.photoconvert.android.model.PresetProfile
import com.photoconvert.android.model.ResizePreset
import com.photoconvert.android.model.StatsSummary
import com.photoconvert.android.model.WatermarkAnchor
import com.photoconvert.android.model.WatchedFolderState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

class ConvertRepository(context: Context) {

    private val prefs = context.getSharedPreferences("photo_convert_prefs", Context.MODE_PRIVATE)

    fun loadDarkThemeOverride(): Boolean? {
        if (!prefs.contains(KEY_DARK_THEME)) return null
        return prefs.getBoolean(KEY_DARK_THEME, false)
    }

    fun saveDarkThemeOverride(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, enabled).apply()
    }

    fun loadSettings(): ConvertSettings {
        return ConvertSettings(
            format = OutputFormat.fromId(prefs.getString(KEY_FORMAT, "webp") ?: "webp"),
            quality = prefs.getInt(KEY_QUALITY, 85),
            targetSizeKb = prefs.getInt(KEY_TARGET_KB, 0),
            resizePreset = ResizePreset.fromId(prefs.getString(KEY_RESIZE, "original") ?: "original"),
            customMaxEdgePx = prefs.getInt(KEY_CUSTOM_EDGE, 2048),
            stripExif = prefs.getBoolean(KEY_STRIP_EXIF, true),
            repairFilenameDates = prefs.getBoolean(KEY_REPAIR_DATES, true),
            normalizeSrgb = prefs.getBoolean(KEY_NORMALIZE_SRGB, true),
            removeBackground = prefs.getBoolean(KEY_REMOVE_BG, false),
            aspectRatio = AspectRatioOption.fromId(prefs.getString(KEY_ASPECT_RATIO, "original") ?: "original"),
            frameFillMode = FrameFillMode.fromId(prefs.getString(KEY_FRAME_FILL, "blur") ?: "blur"),
            watermarkText = prefs.getString(KEY_WM_TEXT, "") ?: "",
            watermarkAnchor = WatermarkAnchor.fromId(prefs.getString(KEY_WM_ANCHOR, "bottom-right") ?: "bottom-right"),
            watermarkOpacity = prefs.getInt(KEY_WM_OPACITY, 65),
            renamePattern = prefs.getString(KEY_RENAME_PATTERN, "{orig}") ?: "{orig}",
            presetSlug = prefs.getString(KEY_PRESET_SLUG, "custom") ?: "custom",
            activePresetName = prefs.getString(KEY_PRESET_NAME, "Custom configuration") ?: "Custom configuration"
        )
    }

    fun saveSettings(settings: ConvertSettings) {
        prefs.edit()
            .putString(KEY_FORMAT, settings.format.id)
            .putInt(KEY_QUALITY, settings.clampedQuality())
            .putInt(KEY_TARGET_KB, settings.targetSizeKb.coerceAtLeast(0))
            .putString(KEY_RESIZE, settings.resizePreset.id)
            .putInt(KEY_CUSTOM_EDGE, settings.clampedCustomEdge())
            .putBoolean(KEY_STRIP_EXIF, settings.stripExif)
            .putBoolean(KEY_REPAIR_DATES, settings.repairFilenameDates)
            .putBoolean(KEY_NORMALIZE_SRGB, settings.normalizeSrgb)
            .putBoolean(KEY_REMOVE_BG, settings.removeBackground)
            .putString(KEY_ASPECT_RATIO, settings.aspectRatio.id)
            .putString(KEY_FRAME_FILL, settings.frameFillMode.id)
            .putString(KEY_WM_TEXT, settings.watermarkText)
            .putString(KEY_WM_ANCHOR, settings.watermarkAnchor.id)
            .putInt(KEY_WM_OPACITY, settings.clampedWatermarkOpacity())
            .putString(KEY_RENAME_PATTERN, settings.renamePattern.ifBlank { "{orig}" })
            .putString(KEY_PRESET_SLUG, settings.presetSlug)
            .putString(KEY_PRESET_NAME, settings.activePresetName)
            .apply()
    }

    fun loadPresets(): List<PresetProfile> {
        val customJson = prefs.getString(KEY_CUSTOM_PRESETS, "[]") ?: "[]"
        val customList = mutableListOf<PresetProfile>()
        try {
            val arr = JSONArray(customJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                customList.add(
                    PresetProfile(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", ""),
                        badge = "Custom",
                        format = OutputFormat.fromId(obj.optString("format", "webp")),
                        quality = obj.optInt("quality", 85),
                        resizePreset = ResizePreset.fromId(obj.optString("resizePreset", "original")),
                        customMaxEdgePx = obj.optInt("customMaxEdgePx", 2048),
                        stripExif = obj.optBoolean("stripExif", true),
                        targetSizeKb = obj.optInt("targetSizeKb", 0),
                        normalizeSrgb = obj.optBoolean("normalizeSrgb", true),
                        removeBackground = obj.optBoolean("removeBackground", false),
                        aspectRatio = AspectRatioOption.fromId(obj.optString("aspectRatio", "original")),
                        frameFillMode = FrameFillMode.fromId(obj.optString("frameFillMode", "blur")),
                        watermarkText = obj.optString("watermarkText", ""),
                        renamePattern = obj.optString("renamePattern", "{orig}"),
                        builtIn = false
                    )
                )
            }
        } catch (_: Exception) {
        }
        return DefaultPresets.list + customList
    }

    fun addCustomPreset(name: String, description: String, settings: ConvertSettings): List<PresetProfile> {
        val currentCustom = loadPresets().filter { !it.builtIn }.toMutableList()
        val profile = PresetProfile(
            id = "custom-${UUID.randomUUID().toString().take(8)}",
            name = name.trim().ifBlank { "Custom Preset" },
            description = description.trim().ifBlank {
                "${settings.format.label} · ${if (settings.targetSizeKb > 0) "≤${settings.targetSizeKb} KB" else "${settings.quality}%"}"
            },
            badge = "Custom",
            format = settings.format,
            quality = settings.clampedQuality(),
            resizePreset = settings.resizePreset,
            customMaxEdgePx = settings.clampedCustomEdge(),
            stripExif = settings.stripExif,
            targetSizeKb = settings.targetSizeKb,
            normalizeSrgb = settings.normalizeSrgb,
            removeBackground = settings.removeBackground,
            aspectRatio = settings.aspectRatio,
            frameFillMode = settings.frameFillMode,
            watermarkText = settings.watermarkText,
            renamePattern = settings.renamePattern,
            builtIn = false
        )
        currentCustom.add(profile)
        persistCustomPresets(currentCustom)
        return DefaultPresets.list + currentCustom
    }

    fun deleteCustomPreset(presetId: String): List<PresetProfile> {
        val remaining = loadPresets().filter { !it.builtIn && it.id != presetId }
        persistCustomPresets(remaining)
        return DefaultPresets.list + remaining
    }

    private fun persistCustomPresets(custom: List<PresetProfile>) {
        val arr = JSONArray()
        for (p in custom) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("description", p.description)
                    .put("format", p.format.id)
                    .put("quality", p.quality)
                    .put("resizePreset", p.resizePreset.id)
                    .put("customMaxEdgePx", p.customMaxEdgePx)
                    .put("stripExif", p.stripExif)
                    .put("targetSizeKb", p.targetSizeKb)
                    .put("normalizeSrgb", p.normalizeSrgb)
                    .put("removeBackground", p.removeBackground)
                    .put("aspectRatio", p.aspectRatio.id)
                    .put("frameFillMode", p.frameFillMode.id)
                    .put("watermarkText", p.watermarkText)
                    .put("renamePattern", p.renamePattern)
            )
        }
        prefs.edit().putString(KEY_CUSTOM_PRESETS, arr.toString()).apply()
    }

    fun loadWatchedFolderState(): WatchedFolderState {
        return WatchedFolderState(
            enabled = prefs.getBoolean(KEY_WATCH_ENABLED, false),
            treeUriString = prefs.getString(KEY_WATCH_URI, "") ?: "",
            displayPath = prefs.getString(KEY_WATCH_DISPLAY, "Not selected (tap Choose Folder)")
                ?: "Not selected (tap Choose Folder)",
            processedCount = prefs.getInt(KEY_WATCH_PROCESSED, 0)
        )
    }

    fun saveWatchedFolderState(state: WatchedFolderState) {
        prefs.edit()
            .putBoolean(KEY_WATCH_ENABLED, state.enabled)
            .putString(KEY_WATCH_URI, state.treeUriString)
            .putString(KEY_WATCH_DISPLAY, state.displayPath)
            .putInt(KEY_WATCH_PROCESSED, state.processedCount)
            .apply()
    }

    fun incrementWatchedProcessedCount(delta: Int) {
        val current = prefs.getInt(KEY_WATCH_PROCESSED, 0)
        prefs.edit().putInt(KEY_WATCH_PROCESSED, current + delta).apply()
    }

    fun loadProcessedWatchKeys(): Set<String> {
        return prefs.getStringSet(KEY_WATCH_SEEN_KEYS, emptySet()) ?: emptySet()
    }

    fun saveProcessedWatchKeys(keys: Set<String>) {
        prefs.edit().putStringSet(KEY_WATCH_SEEN_KEYS, keys.take(1000).toSet()).apply()
    }

    fun loadHistory(): List<ConvertedItem> {
        val raw = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        val items = mutableListOf<ConvertedItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                items.add(
                    ConvertedItem(
                        id = obj.getString("id"),
                        originalName = obj.getString("originalName"),
                        outputName = obj.getString("outputName"),
                        originalFormat = obj.getString("originalFormat"),
                        outputFormat = obj.getString("outputFormat"),
                        mimeType = obj.getString("mimeType"),
                        origWidth = obj.getInt("origWidth"),
                        origHeight = obj.getInt("origHeight"),
                        outWidth = obj.getInt("outWidth"),
                        outHeight = obj.getInt("outHeight"),
                        origBytes = obj.getLong("origBytes"),
                        outBytes = obj.getLong("outBytes"),
                        savedBytes = obj.getLong("savedBytes"),
                        savedPct = obj.getDouble("savedPct"),
                        exifKept = obj.optBoolean("exifKept", false),
                        repairedDate = obj.optBoolean("repairedDate", false),
                        cutoutApplied = obj.optBoolean("cutoutApplied", false),
                        localFilePath = obj.getString("localFilePath"),
                        createdAtEpochMs = obj.getLong("createdAtEpochMs")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return items
    }

    fun appendHistory(newItems: List<ConvertedItem>): List<ConvertedItem> {
        val combined = (newItems + loadHistory()).take(150)
        persistHistory(combined)
        return combined
    }

    fun removeHistoryItem(id: String): List<ConvertedItem> {
        val existing = loadHistory()
        existing.firstOrNull { it.id == id }?.let { item ->
            File(item.localFilePath).delete()
        }
        val updated = existing.filterNot { it.id == id }
        persistHistory(updated)
        return updated
    }

    fun clearHistory(): List<ConvertedItem> {
        loadHistory().forEach { item ->
            File(item.localFilePath).delete()
        }
        persistHistory(emptyList())
        return emptyList()
    }

    fun computeSummary(history: List<ConvertedItem>): StatsSummary {
        val totalOrig = history.sumOf { it.origBytes }
        val totalOut = history.sumOf { it.outBytes }
        val totalSaved = history.sumOf { max(0L, it.savedBytes) }
        val avgPct = if (totalOrig > 0L) {
            ((totalSaved.toDouble() / totalOrig.toDouble()) * 1000.0).roundToInt() / 10.0
        } else {
            0.0
        }
        return StatsSummary(
            totalConverted = history.size,
            totalOrigBytes = totalOrig,
            totalOutBytes = totalOut,
            totalSavedBytes = totalSaved,
            avgSavedPct = avgPct
        )
    }

    private fun persistHistory(items: List<ConvertedItem>) {
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("originalName", item.originalName)
                    .put("outputName", item.outputName)
                    .put("originalFormat", item.originalFormat)
                    .put("outputFormat", item.outputFormat)
                    .put("mimeType", item.mimeType)
                    .put("origWidth", item.origWidth)
                    .put("origHeight", item.origHeight)
                    .put("outWidth", item.outWidth)
                    .put("outHeight", item.outHeight)
                    .put("origBytes", item.origBytes)
                    .put("outBytes", item.outBytes)
                    .put("savedBytes", item.savedBytes)
                    .put("savedPct", item.savedPct)
                    .put("exifKept", item.exifKept)
                    .put("repairedDate", item.repairedDate)
                    .put("cutoutApplied", item.cutoutApplied)
                    .put("localFilePath", item.localFilePath)
                    .put("createdAtEpochMs", item.createdAtEpochMs)
            )
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private companion object {
        const val KEY_DARK_THEME = "dark_theme"
        const val KEY_FORMAT = "output_format"
        const val KEY_QUALITY = "quality"
        const val KEY_TARGET_KB = "target_size_kb"
        const val KEY_RESIZE = "resize_preset"
        const val KEY_CUSTOM_EDGE = "custom_edge"
        const val KEY_STRIP_EXIF = "strip_exif"
        const val KEY_REPAIR_DATES = "repair_filename_dates"
        const val KEY_NORMALIZE_SRGB = "normalize_srgb"
        const val KEY_REMOVE_BG = "remove_background"
        const val KEY_ASPECT_RATIO = "aspect_ratio"
        const val KEY_FRAME_FILL = "frame_fill"
        const val KEY_WM_TEXT = "watermark_text"
        const val KEY_WM_ANCHOR = "watermark_anchor"
        const val KEY_WM_OPACITY = "watermark_opacity"
        const val KEY_RENAME_PATTERN = "rename_pattern"
        const val KEY_PRESET_SLUG = "preset_slug"
        const val KEY_PRESET_NAME = "preset_name"
        const val KEY_CUSTOM_PRESETS = "custom_presets"
        const val KEY_HISTORY = "history_items"
        const val KEY_WATCH_ENABLED = "watch_enabled"
        const val KEY_WATCH_URI = "watch_tree_uri"
        const val KEY_WATCH_DISPLAY = "watch_display_path"
        const val KEY_WATCH_PROCESSED = "watch_processed_count"
        const val KEY_WATCH_SEEN_KEYS = "watch_seen_keys"
    }
}
