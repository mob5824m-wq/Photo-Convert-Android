package com.photoconvert.android.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.media.FaceDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.photoconvert.android.model.RedactStyle
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object PrivacyRedactor {

    private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val PLATE_OR_ID_REGEX = Regex("\\b[A-Z0-9]{2,4}[-\\s]?[0-9]{3,6}\\b", RegexOption.IGNORE_CASE)

    fun looksSensitive(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 4) return false
        val digitCount = trimmed.count { it.isDigit() }
        if (digitCount >= 4) return true
        if (EMAIL_REGEX.containsMatchIn(trimmed)) return true
        if (PLATE_OR_ID_REGEX.containsMatchIn(trimmed)) return true
        return false
    }

    /**
     * Parses semicolon-separated normalized `x,y,w,h` boxes in `[0, 1]`.
     */
    fun parseNormalizedBoxes(spec: String, width: Int, height: Int): List<Rect> {
        if (spec.isBlank()) return emptyList()
        val out = mutableListOf<Rect>()
        for (rawPart in spec.split(';')) {
            val pieces = rawPart.trim().split(',')
            if (pieces.size != 4) continue
            val nx = pieces[0].trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: continue
            val ny = pieces[1].trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: continue
            val nw = pieces[2].trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: continue
            val nh = pieces[3].trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: continue

            val px1 = (nx * width).roundToInt().coerceIn(0, max(0, width - 1))
            val py1 = (ny * height).roundToInt().coerceIn(0, max(0, height - 1))
            val px2 = ((nx + nw) * width).roundToInt().coerceIn(px1, width)
            val py2 = ((ny + nh) * height).roundToInt().coerceIn(py1, height)
            if (px2 - px1 >= 4 && py2 - py1 >= 4) {
                out.add(Rect(px1, py1, px2, py2))
            }
        }
        return out
    }

    suspend fun applyRedactions(
        src: Bitmap,
        redactFaces: Boolean,
        redactText: Boolean,
        redactBoxesSpec: String,
        style: RedactStyle
    ): Pair<Bitmap, Int> {
        val boxes = mutableListOf<Rect>()
        if (redactFaces) {
            boxes.addAll(detectFaces(src))
        }
        if (redactText) {
            boxes.addAll(detectSensitiveTextBlocks(src))
        }
        if (redactBoxesSpec.isNotBlank()) {
            boxes.addAll(parseNormalizedBoxes(redactBoxesSpec, src.width, src.height))
        }
        if (boxes.isEmpty()) return src to 0

        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 23, 42)
            this.style = Paint.Style.FILL
        }

        for (rawBox in boxes) {
            val left = rawBox.left.coerceIn(0, out.width - 1)
            val top = rawBox.top.coerceIn(0, out.height - 1)
            val right = rawBox.right.coerceIn(left + 1, out.width)
            val bottom = rawBox.bottom.coerceIn(top + 1, out.height)
            val bw = right - left
            val bh = bottom - top

            when (style) {
                RedactStyle.SOLID -> {
                    canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), solidPaint)
                }
                RedactStyle.PIXELATE -> {
                    val crop = Bitmap.createBitmap(out, left, top, bw, bh)
                    val block = max(8, min(bw, bh) / 7)
                    val sw = max(2, bw / block)
                    val sh = max(2, bh / block)
                    val tiny = Bitmap.createScaledBitmap(crop, sw, sh, true)
                    val mosaic = Bitmap.createScaledBitmap(tiny, bw, bh, false)
                    canvas.drawBitmap(mosaic, left.toFloat(), top.toFloat(), null)
                    crop.recycle()
                    if (tiny !== crop) tiny.recycle()
                    if (mosaic !== tiny) mosaic.recycle()
                }
                RedactStyle.BLUR -> {
                    val crop = Bitmap.createBitmap(out, left, top, bw, bh)
                    val sw = max(4, bw / 10)
                    val sh = max(4, bh / 10)
                    val tiny = Bitmap.createScaledBitmap(crop, sw, sh, true)
                    val blurred = Bitmap.createScaledBitmap(tiny, bw, bh, true)
                    canvas.drawBitmap(blurred, left.toFloat(), top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
                    crop.recycle()
                    if (tiny !== crop) tiny.recycle()
                    if (blurred !== tiny) blurred.recycle()
                }
            }
        }
        return out to boxes.size
    }

    private suspend fun detectFaces(src: Bitmap): List<Rect> {
        val mlKitFaces = runCatching {
            val opts = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
            val detector = FaceDetection.getClient(opts)
            try {
                detector.process(InputImage.fromBitmap(src, 0)).await().map { it.boundingBox }
            } finally {
                detector.close()
            }
        }.getOrNull()

        if (!mlKitFaces.isNullOrEmpty()) return mlKitFaces

        return runCatching {
            val evenW = if (src.width % 2 == 0) src.width else max(2, src.width - 1)
            val rgb565 = Bitmap.createScaledBitmap(src, evenW, src.height, false)
                .copy(Bitmap.Config.RGB_565, false)
            val maxFaces = 12
            val found = arrayOfNulls<FaceDetector.Face>(maxFaces)
            val count = FaceDetector(rgb565.width, rgb565.height, maxFaces).findFaces(rgb565, found)
            rgb565.recycle()
            val list = mutableListOf<Rect>()
            for (i in 0 until count) {
                val f = found[i] ?: continue
                val mid = PointF()
                f.getMidPoint(mid)
                val r = (f.eyesDistance() * 1.45f).roundToInt()
                list.add(
                    Rect(
                        (mid.x - r).roundToInt(),
                        (mid.y - r).roundToInt(),
                        (mid.x + r).roundToInt(),
                        (mid.y + r * 1.2f).roundToInt()
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    private suspend fun detectSensitiveTextBlocks(src: Bitmap): List<Rect> {
        return runCatching {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val visionText = recognizer.process(InputImage.fromBitmap(src, 0)).await()
                val matches = mutableListOf<Rect>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        if (looksSensitive(line.text)) {
                            line.boundingBox?.let { matches.add(it) }
                        }
                    }
                }
                matches
            } finally {
                recognizer.close()
            }
        }.getOrDefault(emptyList())
    }
}
