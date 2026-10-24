package com.photoconvert.android.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object SubjectCutoutHelper {

    suspend fun cutoutForeground(src: Bitmap): Bitmap {
        val mlKitBitmap = runCatching {
            val options = SubjectSegmenterOptions.Builder()
                .enableForegroundBitmap()
                .build()
            val segmenter = SubjectSegmentation.getClient(options)
            try {
                val result = segmenter.process(InputImage.fromBitmap(src, 0)).await()
                result.foregroundBitmap
            } finally {
                segmenter.close()
            }
        }.getOrNull()

        return mlKitBitmap ?: removeBackgroundBorderFlood(src)
    }

    /**
     * Pure-Kotlin offline border-seeded foreground cutout fallback for devices without Play Services.
     */
    fun removeBackgroundBorderFlood(src: Bitmap): Bitmap {
        val origW = src.width
        val origH = src.height
        val workMax = 360
        val scale = min(1f, workMax.toFloat() / max(origW, origH).toFloat())
        val workW = max(16, (origW * scale).roundToInt())
        val workH = max(16, (origH * scale).roundToInt())

        val small = Bitmap.createScaledBitmap(src, workW, workH, true)
        val px = IntArray(workW * workH)
        small.getPixels(px, 0, workW, 0, 0, workW, workH)
        if (small !== src) small.recycle()

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var borderCount = 0

        for (x in 0 until workW) {
            val topC = px[x]
            val botC = px[(workH - 1) * workW + x]
            sumR += Color.red(topC) + Color.red(botC)
            sumG += Color.green(topC) + Color.green(botC)
            sumB += Color.blue(topC) + Color.blue(botC)
            borderCount += 2
        }
        for (y in 1 until workH - 1) {
            val leftC = px[y * workW]
            val rightC = px[y * workW + (workW - 1)]
            sumR += Color.red(leftC) + Color.red(rightC)
            sumG += Color.green(leftC) + Color.green(rightC)
            sumB += Color.blue(leftC) + Color.blue(rightC)
            borderCount += 2
        }

        val bgR = (sumR / max(1, borderCount)).toInt()
        val bgG = (sumG / max(1, borderCount)).toInt()
        val bgB = (sumB / max(1, borderCount)).toInt()
        val threshold = 48.0

        val visited = BooleanArray(workW * workH)
        val queue = ArrayDeque<Int>()

        fun colorDist(c: Int): Double {
            val dr = (Color.red(c) - bgR).toDouble()
            val dg = (Color.green(c) - bgG).toDouble()
            val db = (Color.blue(c) - bgB).toDouble()
            return sqrt(dr * dr + dg * dg + db * db)
        }

        fun trySeed(idx: Int) {
            if (!visited[idx] && colorDist(px[idx]) <= threshold) {
                visited[idx] = true
                queue.add(idx)
            }
        }

        for (x in 0 until workW) {
            trySeed(x)
            trySeed((workH - 1) * workW + x)
        }
        for (y in 0 until workH) {
            trySeed(y * workW)
            trySeed(y * workW + (workW - 1))
        }

        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val cx = cur % workW
            val cy = cur / workW
            if (cy > 0) trySeed((cy - 1) * workW + cx)
            if (cy < workH - 1) trySeed((cy + 1) * workW + cx)
            if (cx > 0) trySeed(cy * workW + (cx - 1))
            if (cx < workW - 1) trySeed(cy * workW + (cx + 1))
        }

        val maskSmall = Bitmap.createBitmap(workW, workH, Bitmap.Config.ARGB_8888)
        val maskPx = IntArray(workW * workH)
        for (i in maskPx.indices) {
            maskPx[i] = if (visited[i]) Color.TRANSPARENT else Color.WHITE
        }
        maskSmall.setPixels(maskPx, 0, workW, 0, 0, workW, workH)
        val maskFull = Bitmap.createScaledBitmap(maskSmall, origW, origH, true)
        maskSmall.recycle()

        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val srcRow = IntArray(origW)
        val maskRow = IntArray(origW)
        for (y in 0 until origH) {
            out.getPixels(srcRow, 0, origW, 0, y, origW, 1)
            maskFull.getPixels(maskRow, 0, origW, 0, y, origW, 1)
            for (x in 0 until origW) {
                val mAlpha = Color.alpha(maskRow[x])
                val origAlpha = Color.alpha(srcRow[x])
                val finalAlpha = min(origAlpha, mAlpha)
                srcRow[x] = Color.argb(
                    finalAlpha,
                    Color.red(srcRow[x]),
                    Color.green(srcRow[x]),
                    Color.blue(srcRow[x])
                )
            }
            out.setPixels(srcRow, 0, origW, 0, y, origW, 1)
        }
        maskFull.recycle()
        return out
    }
}
