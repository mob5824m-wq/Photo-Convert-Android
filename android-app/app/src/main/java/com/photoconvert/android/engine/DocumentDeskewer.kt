package com.photoconvert.android.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object DocumentDeskewer {

    /**
     * Automatically detects a contrasting document quadrilateral on a surface and rectifies it
     * using Android's 4-corner homography `Matrix.setPolyToPoly`, falling back to clean border trim.
     */
    fun autoDeskewAndTrim(src: Bitmap): Bitmap {
        val origW = src.width
        val origH = src.height
        if (origW < 80 || origH < 80) return src

        val workMax = 300
        val scale = min(1f, workMax.toFloat() / max(origW, origH).toFloat())
        val workW = max(48, (origW * scale).roundToInt())
        val workH = max(48, (origH * scale).roundToInt())

        val small = Bitmap.createScaledBitmap(src, workW, workH, true)
        val px = IntArray(workW * workH)
        small.getPixels(px, 0, workW, 0, 0, workW, workH)
        if (small !== src) small.recycle()

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var borderCnt = 0
        for (x in 0 until workW) {
            val t = px[x]
            val b = px[(workH - 1) * workW + x]
            sumR += Color.red(t) + Color.red(b)
            sumG += Color.green(t) + Color.green(b)
            sumB += Color.blue(t) + Color.blue(b)
            borderCnt += 2
        }
        for (y in 1 until workH - 1) {
            val l = px[y * workW]
            val r = px[y * workW + (workW - 1)]
            sumR += Color.red(l) + Color.red(r)
            sumG += Color.green(l) + Color.green(r)
            sumB += Color.blue(l) + Color.blue(r)
            borderCnt += 2
        }

        val bgR = (sumR / max(1, borderCnt)).toInt()
        val bgG = (sumG / max(1, borderCnt)).toInt()
        val bgB = (sumB / max(1, borderCnt)).toInt()
        val threshold = 34.0

        var fgCount = 0
        var minSum = Int.MAX_VALUE
        var maxSum = Int.MIN_VALUE
        var minDiff = Int.MAX_VALUE
        var maxDiff = Int.MIN_VALUE

        var tlX = 0
        var tlY = 0
        var brX = workW - 1
        var brY = workH - 1
        var trX = workW - 1
        var trY = 0
        var blX = 0
        var blY = workH - 1

        var minX = workW
        var maxX = 0
        var minY = workH
        var maxY = 0

        for (y in 0 until workH) {
            for (x in 0 until workW) {
                val c = px[y * workW + x]
                val dr = (Color.red(c) - bgR).toDouble()
                val dg = (Color.green(c) - bgG).toDouble()
                val db = (Color.blue(c) - bgB).toDouble()
                if (sqrt(dr * dr + dg * dg + db * db) > threshold) {
                    fgCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y

                    val s = x + y
                    val d = x - y
                    if (s < minSum) {
                        minSum = s; tlX = x; tlY = y
                    }
                    if (s > maxSum) {
                        maxSum = s; brX = x; brY = y
                    }
                    if (d > maxDiff) {
                        maxDiff = d; trX = x; trY = y
                    }
                    if (d < minDiff) {
                        minDiff = d; blX = x; blY = y
                    }
                }
            }
        }

        val frac = fgCount.toFloat() / (workW * workH).toFloat()
        if (frac < 0.08f || frac > 0.94f) return src

        val inv = 1f / scale
        if (frac > 0.88f) {
            val x1 = max(0, (minX * inv).roundToInt() - 8)
            val y1 = max(0, (minY * inv).roundToInt() - 8)
            val x2 = min(origW, (maxX * inv).roundToInt() + 8)
            val y2 = min(origH, (maxY * inv).roundToInt() + 8)
            if (x2 - x1 > origW * 0.3f && y2 - y1 > origH * 0.3f) {
                return Bitmap.createBitmap(src, x1, y1, x2 - x1, y2 - y1)
            }
            return src
        }

        val srcQuad = floatArrayOf(
            tlX * inv, tlY * inv,
            trX * inv, trY * inv,
            brX * inv, brY * inv,
            blX * inv, blY * inv
        )
        val topW = hypot((srcQuad[2] - srcQuad[0]).toDouble(), (srcQuad[3] - srcQuad[1]).toDouble())
        val botW = hypot((srcQuad[4] - srcQuad[6]).toDouble(), (srcQuad[5] - srcQuad[7]).toDouble())
        val outW = max(64, max(topW, botW).roundToInt())

        val leftH = hypot((srcQuad[6] - srcQuad[0]).toDouble(), (srcQuad[7] - srcQuad[1]).toDouble())
        val rightH = hypot((srcQuad[4] - srcQuad[2]).toDouble(), (srcQuad[5] - srcQuad[3]).toDouble())
        val outH = max(64, max(leftH, rightH).roundToInt())

        if (outW < origW * 0.25f || outH < origH * 0.25f) return src

        val dstQuad = floatArrayOf(
            0f, 0f,
            outW.toFloat(), 0f,
            outW.toFloat(), outH.toFloat(),
            0f, outH.toFloat()
        )

        val matrix = Matrix()
        if (!matrix.setPolyToPoly(srcQuad, 0, dstQuad, 0, 4)) {
            return src
        }

        val rectified = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(rectified)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return rectified
    }
}
