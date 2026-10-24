package com.photoconvert.android.data

import android.content.Context
import com.photoconvert.android.model.ConvertSettings
import com.photoconvert.android.model.ConvertedItem
import com.photoconvert.android.model.DefaultPresets
import com.photoconvert.android.model.OutputFormat
import com.photoconvert.android.model.PresetProfile
import com.photoconvert.android.model.ResizePreset
import com.photoconvert.android.model.StatsSummary
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
            resizePreset = ResizePreset.fromId(prefs.getString(KEY_RESIZE, "original") ?: "original"),
            customMaxEdgePx = prefs.getInt(KEY_CUSTOM_EDGE, 2048),
            stripExif = prefs.getBoolean(KEY_STRIP_EXIF, true),
            activePresetName = prefs.getString(KEY_PRESET_NAME, "Custom configuration") ?: "Custom configuration"
        )
    }

    fun saveSettings(settings: ConvertSettings) {
        prefs.edit()
            .putString(KEY_FORMAT, settings.format.id)
            .putInt(KEY_QUALITY, settings.clampedQuality())
            .putString(KEY_RESIZE, settings.resizePreset.id)
            .putInt(KEY_CUSTOM_EDGE, settings.clampedCustomEdge())
            .putBoolean(KEY_STRIP_EXIF, settings.stripExif)
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
                "${settings.format.label} · ${if (settings.format.isLossless) "Lossless" else "${settings.quality}%"}"
            },
            badge = "Custom",
            format = settings.format,
            quality = settings.clampedQuality(),
            resizePreset = settings.resizePreset,
            customMaxEdgePx = settings.clampedCustomEdge(),
            stripExif = settings.stripExif,
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
            )
        }
        prefs.edit().putString(KEY_CUSTOM_PRESETS, arr.toString()).apply()
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
        const val KEY_RESIZE = "resize_preset"
        const val KEY_CUSTOM_EDGE = "custom_edge"
        const val KEY_STRIP_EXIF = "strip_exif"
        const val KEY_PRESET_NAME = "preset_name"
        const val KEY_CUSTOM_PRESETS = "custom_presets"
        const val KEY_HISTORY = "history_items"
    }
}
