package com.photoconvert.android.engine

import com.photoconvert.android.model.ResizePreset
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object DimensionCalculator {

    fun calculateTargetDimensions(
        width: Int,
        height: Int,
        preset: ResizePreset,
        customMaxEdgePx: Int = 2048
    ): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Dimensions must be positive." }

        return when (preset) {
            ResizePreset.ORIGINAL -> width to height
            ResizePreset.MP_16 -> clampMegapixels(width, height, 16.0)
            ResizePreset.UHD_4K -> clampLongestEdge(width, height, 3840)
            ResizePreset.FHD_1080P -> clampLongestEdge(width, height, 1920)
            ResizePreset.CUSTOM_EDGE -> clampLongestEdge(width, height, customMaxEdgePx.coerceIn(128, 16384))
        }
    }

    fun clampMegapixels(width: Int, height: Int, maxMp: Double): Pair<Int, Int> {
        val limitPx = maxMp * 1_000_000.0
        val currentPx = width.toDouble() * height.toDouble()
        if (currentPx <= limitPx) return width to height

        val scale = sqrt(limitPx / currentPx)
        val newW = max(1, floor(width * scale).toInt())
        val newH = max(1, floor(height * scale).toInt())
        return newW to newH
    }

    fun clampLongestEdge(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxEdge) return width to height

        val scale = maxEdge.toDouble() / longest.toDouble()
        val newW = max(1, (width * scale).roundToInt())
        val newH = max(1, (height * scale).roundToInt())
        return newW to newH
    }

    fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
