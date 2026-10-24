package com.photoconvert.android.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.photoconvert.android.model.AspectRatioOption
import com.photoconvert.android.model.FrameFillMode
import com.photoconvert.android.model.WatermarkAnchor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageEnhancer {

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
                // Fast downsampled smooth ambient backdrop
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
        opacityPct: Int
    ): Bitmap {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return src

        val mutable = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val shortest = min(mutable.width, mutable.height)
        val textSizePx = max(18f, shortest * 0.036f)
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
        val tw = bounds.width().toFloat()
        val th = bounds.height().toFloat()

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
