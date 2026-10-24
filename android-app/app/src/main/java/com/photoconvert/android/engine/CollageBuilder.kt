package com.photoconvert.android.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.photoconvert.android.model.CollageLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CollageBuilder(private val context: Context) {

    suspend fun buildCollageSheet(
        uris: List<Uri>,
        layout: CollageLayout = CollageLayout.SIDE_BY_SIDE_2X1,
        showCaptions: Boolean = false
    ): File = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "Select at least one image." }

        val bitmaps = mutableListOf<Pair<String, Bitmap>>()
        for (uri in uris.take(12)) {
            val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
            val (orientation, _) = raw.inputStream().use { ExifHelper.readExifSnapshot(it) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = DimensionCalculator.calculateInSampleSize(
                    max(1, bounds.outWidth),
                    max(1, bounds.outHeight),
                    1400,
                    1400
                )
            }
            val dec = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: continue
            bitmaps.add(queryDisplayName(uri) to ExifHelper.applyOrientation(dec, orientation))
        }
        require(bitmaps.isNotEmpty()) { "Could not decode selected photos." }

        val sheet = if (layout == CollageLayout.ID_CARD_2UP) {
            renderIdCard2Up(bitmaps)
        } else {
            renderGridSheet(bitmaps, layout.cols, layout.rows, showCaptions)
        }
        bitmaps.forEach { (_, bmp) -> bmp.recycle() }

        val outDir = File(context.filesDir, "exports").apply { mkdirs() }
        val outFile = File(outDir, "PhotoConvert_${layout.id}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { sheet.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        sheet.recycle()

        exportJpegToGallery(outFile)
        outFile
    }

    private fun renderIdCard2Up(items: List<Pair<String, Bitmap>>): Bitmap {
        val pageW = 1240
        val pageH = 1754
        val cardW = 560f
        val cardH = 354f
        val sheet = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(203, 213, 225)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val slotsY = listOf(360f, 960f)

        items.take(2).forEachIndexed { idx, (_, bmp) ->
            val slotX = (pageW - cardW) / 2f
            val slotY = slotsY[idx]
            canvas.drawRect(slotX - 6f, slotY - 6f, slotX + cardW + 6f, slotY + cardH + 6f, borderPaint)

            val scale = min(cardW / bmp.width.toFloat(), cardH / bmp.height.toFloat())
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val dx = slotX + (cardW - dw) / 2f
            val dy = slotY + (cardH - dh) / 2f
            canvas.drawBitmap(bmp, null, RectF(dx, dy, dx + dw, dy + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return sheet
    }

    private fun renderGridSheet(
        items: List<Pair<String, Bitmap>>,
        cols: Int,
        minRows: Int,
        showCaptions: Boolean
    ): Bitmap {
        val canvasW = 2200
        val gap = 24
        val rows = max(minRows, ceil(items.size.toDouble() / cols.toDouble()).toInt())
        val cellW = (canvasW - gap * (cols + 1)) / cols
        val cellH = (cellW * 0.78f).roundToInt()
        val captionH = if (showCaptions) 36 else 0
        val canvasH = gap + rows * (cellH + captionH + gap)

        val sheet = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 41, 59)
            textSize = 24f
            typeface = Typeface.SANS_SERIF
            textAlign = Paint.Align.CENTER
        }

        items.take(cols * rows).forEachIndexed { idx, (name, bmp) ->
            val r = idx / cols
            val c = idx % cols
            val cellX = gap + c * (cellW + gap)
            val cellY = gap + r * (cellH + captionH + gap)

            val scale = min(cellW.toFloat() / bmp.width.toFloat(), cellH.toFloat() / bmp.height.toFloat())
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val dx = cellX + (cellW - dw) / 2f
            val dy = cellY + (cellH - dh) / 2f
            canvas.drawBitmap(bmp, null, RectF(dx, dy, dx + dw, dy + dh), Paint(Paint.FILTER_BITMAP_FLAG))

            if (showCaptions) {
                val shortName = if (name.length > 26) name.take(23) + "..." else name
                canvas.drawText(shortName, cellX + cellW / 2f, (cellY + cellH + 28).toFloat(), textPaint)
            }
        }
        return sheet
    }

    private fun exportJpegToGallery(file: File) {
        runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PhotoConvert")
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "photo.jpg"
        }
        return uri.lastPathSegment ?: "photo.jpg"
    }
}
