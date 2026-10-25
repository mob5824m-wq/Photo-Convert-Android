package com.photoconvert.android.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.photoconvert.android.model.AspectRatioOption
import com.photoconvert.android.model.FrameFillMode
import com.photoconvert.android.model.WatermarkAnchor
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageEnhancer {

    /**
     * Optional auto-levels histogram stretch, monochrome conversion, and post-resize micro-sharpening (#6).
     */
    fun applyToneAndSharpen(
        src: Bitmap,
        autoLevels: Boolean,
        grayscale: Boolean,
        postSharpen: Boolean
    ): Bitmap {
        var working = src
        if (autoLevels) {
            val stretched = stretchAutoLevels(working)
            if (stretched !== working && working !== src) working.recycle()
            working = stretched
        }
        if (grayscale) {
            val mono = Bitmap.createBitmap(working.width, working.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(mono)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            canvas.drawBitmap(working, 0f, 0f, paint)
            if (working !== src) working.recycle()
            working = mono
        }
        if (postSharpen) {
            val sharp = unsharp3x3(working)
            if (sharp !== working && working !== src) working.recycle()
            working = sharp
        }
        return working
    }

    fun applyAspectFrame(
        src: Bitmap,
        aspect: AspectRatioOption,
        fillMode: FrameFillMode
    ): Bitmap {
        if (aspect == AspectRatioOption.ORIGINAL || aspect.ratioW <= 0 || aspect.ratioH <= 0) {
            return src
        }

        val targetRatio = aspect.ratioW.toDouble() / aspect.ratioH.toDouble()
        val currentRatio = src.width.toDouble() / src.height.toDouble()
        if (abs(currentRatio - targetRatio) < 0.005) return src

        if (fillMode == FrameFillMode.CENTER_CROP) {
            return if (currentRatio > targetRatio) {
                val newW = max(1, (src.height * targetRatio).roundToInt())
                val left = (src.width - newW) / 2
                Bitmap.createBitmap(src, left, 0, newW, src.height)
            } else {
                val newH = max(1, (src.width / targetRatio).roundToInt())
                val top = (src.height - newH) / 2
                Bitmap.createBitmap(src, 0, top, src.width, newH)
            }
        }

        val canvasW: Int
        val canvasH: Int
        if (currentRatio > targetRatio) {
            canvasW = src.width
            canvasH = max(src.height, (src.width / targetRatio).roundToInt())
        } else {
            canvasH = src.height
            canvasW = max(src.width, (src.height * targetRatio).roundToInt())
        }

        val out = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        when (fillMode) {
            FrameFillMode.WHITE_PAD -> canvas.drawColor(Color.WHITE)
            FrameFillMode.BLACK_PAD -> canvas.drawColor(Color.BLACK)
            FrameFillMode.BLUR_BACKDROP -> {
                val tinyW = max(24, canvasW / 18)
                val tinyH = max(24, canvasH / 18)
                val tiny = Bitmap.createScaledBitmap(src, tinyW, tinyH, true)
                val blurred = boxBlurSmall(tiny, radius = 3)
                if (blurred !== tiny) tiny.recycle()
                val stretched = Bitmap.createScaledBitmap(blurred, canvasW, canvasH, true)
                blurred.recycle()
                canvas.drawBitmap(stretched, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                stretched.recycle()
                canvas.drawColor(Color.argb(56, 12, 18, 28))
            }
            FrameFillMode.CENTER_CROP -> Unit
        }

        val ox = ((canvasW - src.width) / 2f)
        val oy = ((canvasH - src.height) / 2f)
        canvas.drawBitmap(src, ox, oy, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun applyWatermark(
        src: Bitmap,
        text: String,
        anchor: WatermarkAnchor,
        opacityPct: Int,
        tiled: Boolean = false
    ): Bitmap {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return src

        val mutable = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val shortest = min(mutable.width, mutable.height)
        val textSizePx = max(18f, shortest * (if (tiled) 0.032f else 0.036f))
        val margin = max(16f, shortest * 0.034f)
        val alpha = ((opacityPct.coerceIn(15, 100) / 100f) * 255f).roundToInt()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.alpha = alpha
            textSize = textSizePx
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(
                max(2f, textSizePx * 0.12f),
                max(1f, textSizePx * 0.05f),
                max(1f, textSizePx * 0.05f),
                Color.argb((alpha * 0.65f).roundToInt(), 0, 0, 0)
            )
        }

        val bounds = Rect()
        paint.getTextBounds(cleanText, 0, cleanText.length, bounds)
        val tw = max(36f, bounds.width().toFloat())
        val th = max(18f, bounds.height().toFloat())

        if (tiled) {
            val diag = hypot(mutable.width.toDouble(), mutable.height.toDouble()).toFloat()
            val stepX = tw * 1.85f
            val stepY = th * 3.4f
            canvas.save()
            canvas.rotate(-35f, mutable.width / 2f, mutable.height / 2f)
            val startX = (mutable.width / 2f) - diag
            val endX = (mutable.width / 2f) + diag
            val startY = (mutable.height / 2f) - diag
            val endY = (mutable.height / 2f) + diag
            var row = 0
            var y = startY
            while (y <= endY) {
                var x = startX + if (row % 2 == 1) stepX / 2f else 0f
                while (x <= endX) {
                    canvas.drawText(cleanText, x, y, paint)
                    x += stepX
                }
                y += stepY
                row++
            }
            canvas.restore()
            return mutable
        }

        val x: Float
        val y: Float
        when (anchor) {
            WatermarkAnchor.TOP_LEFT -> {
                x = margin
                y = margin + th
            }
            WatermarkAnchor.TOP_RIGHT -> {
                x = max(margin, mutable.width - tw - margin)
                y = margin + th
            }
            WatermarkAnchor.BOTTOM_LEFT -> {
                x = margin
                y = max(th + margin, mutable.height - margin)
            }
            WatermarkAnchor.CENTER -> {
                x = max(0f, (mutable.width - tw) / 2f)
                y = (mutable.height + th) / 2f
            }
            WatermarkAnchor.BOTTOM_RIGHT -> {
                x = max(margin, mutable.width - tw - margin)
                y = max(th + margin, mutable.height - margin)
            }
        }

        canvas.drawText(cleanText, x, y, paint)
        return mutable
    }

    fun applyLogoWatermark(
        src: Bitmap,
        logo: Bitmap,
        anchor: WatermarkAnchor,
        opacityPct: Int,
        tiled: Boolean = false
    ): Bitmap {
        val mutable = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val shortest = min(mutable.width, mutable.height)
        val maxLogoEdge = max(32f, shortest * 0.16f)
        val scale = min(1f, maxLogoEdge / max(logo.width, logo.height).toFloat())
        val lw = max(16, (logo.width * scale).roundToInt())
        val lh = max(16, (logo.height * scale).roundToInt())
        val scaledLogo = Bitmap.createScaledBitmap(logo, lw, lh, true)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = ((opacityPct.coerceIn(15, 100) / 100f) * 255f).roundToInt()
        }

        if (tiled) {
            val diag = hypot(mutable.width.toDouble(), mutable.height.toDouble()).toFloat()
            val stepX = max(lw + 48f, lw * 2.2f)
            val stepY = max(lh + 48f, lh * 2.2f)
            canvas.save()
            canvas.rotate(-35f, mutable.width / 2f, mutable.height / 2f)
            var row = 0
            var y = (mutable.height / 2f) - diag
            val endY = (mutable.height / 2f) + diag
            val endX = (mutable.width / 2f) + diag
            while (y <= endY) {
                var x = (mutable.width / 2f) - diag + if (row % 2 == 1) stepX / 2f else 0f
                while (x <= endX) {
                    canvas.drawBitmap(scaledLogo, x, y, paint)
                    x += stepX
                }
                y += stepY
                row++
            }
            canvas.restore()
        } else {
            val margin = max(16f, shortest * 0.034f)
            val x = when (anchor) {
                WatermarkAnchor.TOP_LEFT, WatermarkAnchor.BOTTOM_LEFT -> margin
                WatermarkAnchor.CENTER -> (mutable.width - lw) / 2f
                else -> max(margin, mutable.width - lw - margin)
            }
            val y = when (anchor) {
                WatermarkAnchor.TOP_LEFT, WatermarkAnchor.TOP_RIGHT -> margin
                WatermarkAnchor.CENTER -> (mutable.height - lh) / 2f
                else -> max(margin, mutable.height - lh - margin)
            }
            canvas.drawBitmap(scaledLogo, x, y, paint)
        }

        if (scaledLogo !== logo) scaledLogo.recycle()
        return mutable
    }

    private fun stretchAutoLevels(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val hist = IntArray(256)
        val row = IntArray(w)
        val stepY = max(1, h / 180)
        var samples = 0

        var y = 0
        while (y < h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (c in row) {
                val luma = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                hist[luma.coerceIn(0, 255)]++
                samples++
            }
            y += stepY
        }
        if (samples < 32) return src

        val lowCut = (samples * 0.008).roundToInt()
        val highCut = (samples * 0.992).roundToInt()
        var acc = 0
        var minVal = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= lowCut) {
                minVal = i
                break
            }
        }
        acc = 0
        var maxVal = 255
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= highCut) {
                maxVal = i
                break
            }
        }
        if (maxVal - minVal < 24 || (minVal <= 3 && maxVal >= 252)) return src

        val scale = 255f / (maxVal - minVal).toFloat()
        val translate = -minVal * scale
        val cm = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        return out
    }

    private fun unsharp3x3(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 4 || h < 4 || w * h > 16_500_000) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val prev = IntArray(w)
        val curr = IntArray(w)
        val next = IntArray(w)
        val dst = IntArray(w)

        src.getPixels(prev, 0, w, 0, 0, w, 1)
        src.getPixels(curr, 0, w, 0, 1, w, 1)
        val amount = 0.42f

        for (y in 1 until h - 1) {
            src.getPixels(next, 0, w, 0, y + 1, w, 1)
            dst[0] = curr[0]
            dst[w - 1] = curr[w - 1]
            for (x in 1 until w - 1) {
                val c = curr[x]
                val a = Color.alpha(c)
                val cr = Color.red(c)
                val cg = Color.green(c)
                val cb = Color.blue(c)

                val avgR = (Color.red(prev[x]) + Color.red(next[x]) + Color.red(curr[x - 1]) + Color.red(curr[x + 1])) * 0.25f
                val avgG = (Color.green(prev[x]) + Color.green(next[x]) + Color.green(curr[x - 1]) + Color.green(curr[x + 1])) * 0.25f
                val avgB = (Color.blue(prev[x]) + Color.blue(next[x]) + Color.blue(curr[x - 1]) + Color.blue(curr[x + 1])) * 0.25f

                val nr = (cr + (cr - avgR) * amount).roundToInt().coerceIn(0, 255)
                val ng = (cg + (cg - avgG) * amount).roundToInt().coerceIn(0, 255)
                val nb = (cb + (cb - avgB) * amount).roundToInt().coerceIn(0, 255)
                dst[x] = Color.argb(a, nr, ng, nb)
            }
            out.setPixels(dst, 0, w, 0, y, w, 1)
            System.arraycopy(curr, 0, prev, 0, w)
            System.arraycopy(next, 0, curr, 0, w)
        }
        return out
    }

    private fun boxBlurSmall(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        val out = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        repeat(2) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var r = 0
                    var g = 0
                    var b = 0
                    var count = 0
                    for (dy in -radius..radius) {
                        val ny = (y + dy).coerceIn(0, h - 1)
                        for (dx in -radius..radius) {
                            val nx = (x + dx).coerceIn(0, w - 1)
                            val c = pixels[ny * w + nx]
                            r += Color.red(c)
                            g += Color.green(c)
                            b += Color.blue(c)
                            count++
                        }
                    }
                    out[y * w + x] = Color.rgb(r / count, g / count, b / count)
                }
            }
            System.arraycopy(out, 0, pixels, 0, pixels.size)
        }

        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
