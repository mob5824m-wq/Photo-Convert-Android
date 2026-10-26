package com.pixelnas.photosaver.converter

import android.graphics.Bitmap

/**
 * 64-bit perceptual difference hash (dHash) + Hamming distance for camera burst
 * and re-encoded WhatsApp/Gallery near-duplicate detection.
 */
object PerceptualHashHelper {

    fun computeDHashHex(bitmap: Bitmap): String {
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val pixels = IntArray(9 * 8)
        small.getPixels(pixels, 0, 9, 0, 0, 9, 8)
        if (small !== bitmap) {
            small.recycle()
        }
        var bits = 0L
        for (row in 0 until 8) {
            val base = row * 9
            for (col in 0 until 8) {
                val leftLuma = luma(pixels[base + col])
                val rightLuma = luma(pixels[base + col + 1])
                bits = (bits shl 1) or (if (rightLuma > leftLuma) 1L else 0L)
            }
        }
        return java.lang.Long.toUnsignedString(bits, 16).padStart(16, '0')
    }

    fun hammingDistanceHex(aHex: String, bHex: String): Int {
        if (aHex.length != 16 || bHex.length != 16) return 64
        return try {
            val a = java.lang.Long.parseUnsignedLong(aHex, 16)
            val b = java.lang.Long.parseUnsignedLong(bHex, 16)
            java.lang.Long.bitCount(a xor b)
        } catch (_: Exception) {
            64
        }
    }

    private fun luma(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
