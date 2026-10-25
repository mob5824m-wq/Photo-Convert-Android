package com.photoconvert.android.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-Kotlin standards-compliant Animated GIF89a encoder (#5) with 6x6x6 + grayscale
 * 256-color palette, Netscape 2.0 infinite looping, Boomerang ping-pong mode, and LZW encoding.
 */
class AnimationExporter(private val context: Context) {

    suspend fun exportBurstGif(
        uris: List<Uri>,
        frameDelayMs: Int = 240,
        boomerang: Boolean = false,
        maxEdgePx: Int = 720
    ): File = withContext(Dispatchers.IO) {
        require(uris.size >= 2) { "Select at least 2 photos to create an animation." }

        val rawFrames = mutableListOf<Bitmap>()
        for (uri in uris.take(24)) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
            val (orientation, _) = bytes.inputStream().use { ExifHelper.readExifSnapshot(it) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = DimensionCalculator.calculateInSampleSize(
                    max(1, bounds.outWidth),
                    max(1, bounds.outHeight),
                    maxEdgePx,
                    maxEdgePx
                )
            }
            val dec = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: continue
            rawFrames.add(ExifHelper.applyOrientation(dec, orientation))
        }
        require(rawFrames.size >= 2) { "Need at least 2 readable images." }

        val first = rawFrames.first()
        val scale = min(1f, maxEdgePx.toFloat() / max(first.width, first.height).toFloat())
        val canvasW = max(64, (first.width * scale).roundToInt())
        val canvasH = max(64, (first.height * scale).roundToInt())

        val normalized = rawFrames.map { frm ->
            val stage = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(stage)
            canvas.drawColor(Color.rgb(15, 23, 42))
            val fit = min(canvasW.toFloat() / frm.width.toFloat(), canvasH.toFloat() / frm.height.toFloat())
            val dw = frm.width * fit
            val dh = frm.height * fit
            val dx = (canvasW - dw) / 2f
            val dy = (canvasH - dh) / 2f
            canvas.drawBitmap(frm, null, RectF(dx, dy, dx + dw, dy + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            frm.recycle()
            stage
        }

        val sequence = if (boomerang && normalized.size >= 3) {
            normalized + normalized.subList(1, normalized.size - 1).asReversed()
        } else {
            normalized
        }

        val outDir = File(context.filesDir, "exports").apply { mkdirs() }
        val gifFile = File(outDir, "PhotoConvert_Burst_${System.currentTimeMillis()}.gif")
        FileOutputStream(gifFile).use { out ->
            encodeGif89a(sequence, canvasW, canvasH, frameDelayMs.coerceIn(40, 3000), out)
        }
        normalized.forEach { it.recycle() }

        exportGifToGallery(gifFile)
        gifFile
    }

    private fun exportGifToGallery(file: File) {
        runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PhotoConvert")
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    companion object {
        fun encodeGif89a(
            frames: List<Bitmap>,
            width: Int,
            height: Int,
            delayMs: Int,
            out: OutputStream
        ) {
            // Header + Logical Screen Descriptor (Global Color Table 256 colors)
            out.write("GIF89a".toByteArray(Charsets.US_ASCII))
            writeShortLE(out, width)
            writeShortLE(out, height)
            out.write(0xF7) // Global Color Table flag (1), color res (7), sort (0), size 2^(7+1)=256
            out.write(0)
            out.write(0)

            // 216-color 6x6x6 cube + 40 grayscale entries = 256 global palette
            for (r in 0..5) {
                for (g in 0..5) {
                    for (b in 0..5) {
                        out.write(r * 51)
                        out.write(g * 51)
                        out.write(b * 51)
                    }
                }
            }
            for (i in 0 until 40) {
                val v = (i * 255) / 39
                out.write(v); out.write(v); out.write(v)
            }

            // Netscape 2.0 infinite loop extension
            out.write(
                byteArrayOf(
                    0x21, 0xFF.toByte(), 0x0B,
                    'N'.code.toByte(), 'E'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(),
                    'C'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(), 'E'.code.toByte(),
                    '2'.code.toByte(), '.'.code.toByte(), '0'.code.toByte(),
                    0x03, 0x01, 0x00, 0x00, 0x00
                )
            )

            val delayCs = max(4, delayMs / 10)
            val pxBuf = IntArray(width * height)
            val indexed = ByteArray(width * height)

            for (bmp in frames) {
                bmp.getPixels(pxBuf, 0, width, 0, 0, width, height)
                for (i in pxBuf.indices) {
                    val c = pxBuf[i]
                    val r = (Color.red(c) + 25) / 51
                    val g = (Color.green(c) + 25) / 51
                    val b = (Color.blue(c) + 25) / 51
                    indexed[i] = (r * 36 + g * 6 + b).toByte()
                }

                // Graphic Control Extension
                out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00))
                writeShortLE(out, delayCs)
                out.write(0x00)
                out.write(0x00)

                // Image Descriptor
                out.write(0x2C)
                writeShortLE(out, 0)
                writeShortLE(out, 0)
                writeShortLE(out, width)
                writeShortLE(out, height)
                out.write(0x00)

                lzwEncode8Bit(indexed, out)
            }

            out.write(0x3B) // Trailer
        }

        private fun writeShortLE(out: OutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }

        private fun lzwEncode8Bit(indices: ByteArray, out: OutputStream) {
            val minCodeSize = 8
            out.write(minCodeSize)

            val clearCode = 256
            val endCode = 257
            var nextCode = 258
            var codeSize = 9

            // Dictionary key = (prefix shl 8) or suffix -> code
            val dict = HashMap<Int, Int>(4096)
            val subBlock = ByteArrayOutputStream(256)
            var bitBuf = 0
            var bitCount = 0

            fun flushSubBlock(forceAll: Boolean) {
                val bytes = subBlock.toByteArray()
                var offset = 0
                while (bytes.size - offset >= 255) {
                    out.write(255)
                    out.write(bytes, offset, 255)
                    offset += 255
                }
                subBlock.reset()
                if (offset < bytes.size) {
                    if (forceAll) {
                        out.write(bytes.size - offset)
                        out.write(bytes, offset, bytes.size - offset)
                    } else {
                        subBlock.write(bytes, offset, bytes.size - offset)
                    }
                }
            }

            fun emitCode(code: Int) {
                bitBuf = bitBuf or (code shl bitCount)
                bitCount += codeSize
                while (bitCount >= 8) {
                    subBlock.write(bitBuf and 0xFF)
                    bitBuf = bitBuf ushr 8
                    bitCount -= 8
                    if (subBlock.size() >= 255) flushSubBlock(false)
                }
            }

            emitCode(clearCode)
            if (indices.isEmpty()) {
                emitCode(endCode)
                if (bitCount > 0) subBlock.write(bitBuf and 0xFF)
                flushSubBlock(true)
                out.write(0)
                return
            }

            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val k = indices[i].toInt() and 0xFF
                val key = (prefix shl 8) or k
                val existing = dict[key]
                if (existing != null) {
                    prefix = existing
                } else {
                    emitCode(prefix)
                    if (nextCode < 4096) {
                        dict[key] = nextCode
                        if (nextCode == (1 shl codeSize) && codeSize < 12) {
                            codeSize++
                        }
                        nextCode++
                    } else {
                        emitCode(clearCode)
                        dict.clear()
                        codeSize = 9
                        nextCode = 258
                    }
                    prefix = k
                }
            }
            emitCode(prefix)
            emitCode(endCode)
            if (bitCount > 0) {
                subBlock.write(bitBuf and 0xFF)
            }
            flushSubBlock(true)
            out.write(0) // Block terminator
        }
    }
}
