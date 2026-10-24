package com.photoconvert.android.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object PaletteExtractor {

    /**
     * Extract up to `count` dominant hex colors using quantized RGB bucket clustering.
     */
    fun extractDominantHexColors(bitmap: Bitmap, count: Int = 5): List<String> {
        val sw = min(96, max(16, bitmap.width))
        val sh = min(96, max(16, bitmap.height))
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== bitmap) small.recycle()

        class Bucket(var hits: Int = 0, var sumR: Long = 0, var sumG: Long = 0, var sumB: Long = 0)

        val buckets = HashMap<Int, Bucket>(256)
        for (c in px) {
            if (Color.alpha(c) < 64) continue
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val bucket = buckets.getOrPut(key) { Bucket() }
            bucket.hits++
            bucket.sumR += r
            bucket.sumG += g
            bucket.sumB += b
        }

        if (buckets.isEmpty()) return listOf("#2563EB")

        val sorted = buckets.values.sortedByDescending { it.hits }
        val chosen = mutableListOf<Triple<Int, Int, Int>>()

        for (b in sorted) {
            val avgR = (b.sumR / b.hits).toInt()
            val avgG = (b.sumG / b.hits).toInt()
            val avgB = (b.sumB / b.hits).toInt()
            val tooClose = chosen.any { (cr, cg, cb) ->
                val dr = avgR - cr
                val dg = avgG - cg
                val db = avgB - cb
                (dr * dr + dg * dg + db * db) < 1800
            }
            if (!tooClose) {
                chosen.add(Triple(avgR, avgG, avgB))
                if (chosen.size >= count) break
            }
        }

        if (chosen.isEmpty()) {
            val top = sorted.first()
            chosen.add(
                Triple(
                    (top.sumR / top.hits).toInt(),
                    (top.sumG / top.hits).toInt(),
                    (top.sumB / top.hits).toInt()
                )
            )
        }

        return chosen.map { (r, g, b) ->
            String.format(Locale.US, "#%02X%02X%02X", r, g, b)
        }
    }

    @Suppress("DEPRECATION")
    fun generateLqipDataUrl(bitmap: Bitmap): String {
        val longest = max(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scale = 24f / longest.toFloat()
        val tw = max(8, (bitmap.width * scale).roundToInt())
        val th = max(8, (bitmap.height * scale).roundToInt())
        val tiny = Bitmap.createScaledBitmap(bitmap, tw, th, true)
        val out = ByteArrayOutputStream()
        val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        tiny.compress(fmt, 45, out)
        if (tiny !== bitmap) tiny.recycle()
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return "data:image/webp;base64,$b64"
    }
}
