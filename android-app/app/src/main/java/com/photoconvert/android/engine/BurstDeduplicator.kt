package com.photoconvert.android.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object BurstDeduplicator {

    fun computeDHashFromLuminanceGrid(luma9x8: IntArray): Long {
        require(luma9x8.size == 72) { "Expected 9x8 luminance grid (72 samples)." }
        var hash = 0L
        for (row in 0 until 8) {
            val base = row * 9
            for (col in 0 until 8) {
                hash = (hash shl 1) or (if (luma9x8[base + col + 1] > luma9x8[base + col]) 1L else 0L)
            }
        }
        return hash
    }

    fun formatDHashHex(hash: Long): String =
        java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0').lowercase(Locale.US)

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun computeDHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val px = IntArray(72)
        small.getPixels(px, 0, 9, 0, 0, 9, 8)
        if (small !== bitmap) small.recycle()
        val luma = IntArray(72) { idx ->
            val c = px[idx]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        return computeDHashFromLuminanceGrid(luma)
    }

    fun computeSharpnessScore(bitmap: Bitmap): Double {
        val maxEdge = 384
        val longest = max(bitmap.width, bitmap.height)
        val scale = min(1f, maxEdge.toFloat() / max(1, longest).toFloat())
        val w = max(16, (bitmap.width * scale).roundToInt())
        val h = max(16, (bitmap.height * scale).roundToInt())
        val work = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        work.getPixels(px, 0, w, 0, 0, w, h)
        if (work !== bitmap) work.recycle()

        val luma = FloatArray(w * h) { i ->
            val c = px[i]
            0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        }

        var sumLap = 0.0
        var sumLapSq = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val center = luma[row + x]
                val lap = (
                    luma[row + x - 1] +
                        luma[row + x + 1] +
                        luma[row - w + x] +
                        luma[row + w + x] -
                        4f * center
                    ).toDouble()
                sumLap += lap
                sumLapSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sumLap / count
        val variance = max(0.0, (sumLapSq / count) - (mean * mean))
        return (variance * 100.0).roundToInt() / 100.0
    }

    data class DedupeOutcome(
        val keptUris: List<Uri>,
        val droppedCount: Int
    )

    suspend fun dedupeQueue(
        context: Context,
        uris: List<Uri>,
        maxHamming: Int = 10
    ): DedupeOutcome = withContext(Dispatchers.IO) {
        if (uris.size < 2) return@withContext DedupeOutcome(uris, 0)

        data class ScoredUri(
            val index: Int,
            val uri: Uri,
            val hash: Long,
            val rankScore: Double
        )

        val scored = mutableListOf<ScoredUri>()
        uris.forEachIndexed { idx, uri ->
            val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (raw == null) {
                scored.add(ScoredUri(idx, uri, idx.toLong(), 0.0))
                return@forEachIndexed
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = DimensionCalculator.calculateInSampleSize(
                    max(1, bounds.outWidth),
                    max(1, bounds.outHeight),
                    512,
                    512
                )
            }
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            if (bmp == null) {
                scored.add(ScoredUri(idx, uri, idx.toLong(), 0.0))
            } else {
                val dHash = computeDHash(bmp)
                val sharp = computeSharpnessScore(bmp)
                bmp.recycle()
                val mp = (bounds.outWidth.toDouble() * bounds.outHeight.toDouble()) / 12_000_000.0
                val rank = sharp * (1.0 + min(1.5, mp))
                scored.add(ScoredUri(idx, uri, dHash, rank))
            }
        }

        val assigned = BooleanArray(scored.size)
        val keepIndices = mutableSetOf<Int>()

        for (i in scored.indices) {
            if (assigned[i]) continue
            assigned[i] = true
            val cluster = mutableListOf(scored[i])
            for (j in i + 1 until scored.size) {
                if (!assigned[j] && hammingDistance(scored[i].hash, scored[j].hash) <= maxHamming) {
                    assigned[j] = true
                    cluster.add(scored[j])
                }
            }
            val winner = cluster.maxBy { it.rankScore }
            keepIndices.add(winner.index)
        }

        val kept = uris.filterIndexed { index, _ -> index in keepIndices }
        DedupeOutcome(keptUris = kept, droppedCount = uris.size - kept.size)
    }
}
