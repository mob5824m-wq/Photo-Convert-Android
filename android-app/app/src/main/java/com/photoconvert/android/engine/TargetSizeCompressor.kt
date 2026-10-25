package com.photoconvert.android.engine

import android.graphics.Bitmap
import android.os.Build
import com.photoconvert.android.model.OutputFormat
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object TargetSizeCompressor {

    data class EncodedPayload(
        val bytes: ByteArray,
        val usedQuality: Int,
        val finalWidth: Int,
        val finalHeight: Int
    )

    fun encodeWithOptionalCeiling(
        source: Bitmap,
        format: OutputFormat,
        preferredQuality: Int,
        targetSizeKb: Int
    ): EncodedPayload {
        if (targetSizeKb <= 0) {
            val q = if (format.isLossless) 100 else preferredQuality.coerceIn(10, 100)
            val bytes = compressOnce(source, format, q)
            return EncodedPayload(bytes, q, source.width, source.height)
        }

        val limitBytes = targetSizeKb * 1024
        var working = source
        var bestBytes = ByteArray(0)
        var bestQ = preferredQuality

        for (step in 0 until 8) {
            if (step > 0) {
                val factor = 0.84.pow(step.toDouble())
                val nw = max(64, (source.width * factor).roundToInt())
                val nh = max(64, (source.height * factor).roundToInt())
                val nextScaled = Bitmap.createScaledBitmap(source, nw, nh, true)
                if (working !== source) working.recycle()
                working = nextScaled
            }

            if (format.isLossless) {
                val attempt = compressOnce(working, format, 100)
                bestBytes = attempt
                bestQ = 100
                if (attempt.size <= limitBytes) {
                    val w = working.width
                    val h = working.height
                    if (working !== source) working.recycle()
                    return EncodedPayload(attempt, 100, w, h)
                }
                continue
            }

            var low = 12
            var high = min(95, max(preferredQuality, 85))
            val floorAttempt = compressOnce(working, format, low)
            bestBytes = floorAttempt
            bestQ = low

            if (floorAttempt.size > limitBytes) {
                continue
            }

            var matched: ByteArray = floorAttempt
            var matchedQ = low

            while (low <= high) {
                val mid = (low + high) / 2
                val candidate = compressOnce(working, format, mid)
                if (candidate.size <= limitBytes) {
                    matched = candidate
                    matchedQ = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }

            val finalW = working.width
            val finalH = working.height
            if (working !== source) working.recycle()
            return EncodedPayload(matched, matchedQ, finalW, finalH)
        }

        val finalW = working.width
        val finalH = working.height
        if (working !== source) working.recycle()
        return EncodedPayload(bestBytes, bestQ, finalW, finalH)
    }

    @Suppress("DEPRECATION")
    fun compressOnce(bitmap: Bitmap, format: OutputFormat, quality: Int): ByteArray {
        val androidFmt = when (format) {
            OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
            OutputFormat.PNG -> Bitmap.CompressFormat.PNG
            OutputFormat.WEBP_LOSSLESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.PNG
            }
            OutputFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(androidFmt, quality.coerceIn(1, 100), out)
        return out.toByteArray()
    }
}
