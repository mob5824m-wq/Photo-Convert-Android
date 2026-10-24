package com.photoconvert.android.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.photoconvert.android.model.PdfMargin
import com.photoconvert.android.model.PdfPageSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PdfExporter(private val context: Context) {

    suspend fun exportPhotosToPdf(
        uris: List<Uri>,
        pageSize: PdfPageSize = PdfPageSize.A4,
        margin: PdfMargin = PdfMargin.SMALL,
        documentScanFilter: Boolean = false
    ): File = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "Select at least one image to create a PDF." }

        val pdf = PdfDocument()
        try {
            uris.forEachIndexed { idx, uri ->
                val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@forEachIndexed
                val (orientation, _) = raw.inputStream().use { ExifHelper.readExifSnapshot(it) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@forEachIndexed

                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = DimensionCalculator.calculateInSampleSize(
                        bounds.outWidth,
                        bounds.outHeight,
                        2000,
                        2000
                    )
                }
                val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, decodeOpts)
                    ?: return@forEachIndexed
                val upright = ExifHelper.applyOrientation(decoded, orientation)

                val pad = margin.paddingPt
                val pageW: Int
                val pageH: Int
                if (pageSize == PdfPageSize.FIT_IMAGE) {
                    val scale = min(1f, 1400f / max(upright.width, upright.height).toFloat())
                    pageW = (upright.width * scale).roundToInt() + pad * 2
                    pageH = (upright.height * scale).roundToInt() + pad * 2
                } else {
                    val landscape = upright.width > upright.height
                    pageW = if (landscape) pageSize.heightPt else pageSize.widthPt
                    pageH = if (landscape) pageSize.widthPt else pageSize.heightPt
                }

                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, idx + 1).create()
                val page = pdf.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)

                val availW = max(32f, (pageW - pad * 2).toFloat())
                val availH = max(32f, (pageH - pad * 2).toFloat())
                val fitScale = min(availW / upright.width.toFloat(), availH / upright.height.toFloat())
                val drawW = upright.width * fitScale
                val drawH = upright.height * fitScale
                val left = (pageW - drawW) / 2f
                val top = (pageH - drawH) / 2f

                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                if (documentScanFilter) {
                    val grayMatrix = ColorMatrix().apply { setSaturation(0f) }
                    val contrast = 1.38f
                    val translate = (-0.5f * contrast + 0.5f) * 255f
                    val contrastMatrix = ColorMatrix(
                        floatArrayOf(
                            contrast, 0f, 0f, 0f, translate,
                            0f, contrast, 0f, 0f, translate,
                            0f, 0f, contrast, 0f, translate,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                    grayMatrix.postConcat(contrastMatrix)
                    paint.colorFilter = ColorMatrixColorFilter(grayMatrix)
                }

                canvas.drawBitmap(upright, null, RectF(left, top, left + drawW, top + drawH), paint)
                pdf.finishPage(page)
                upright.recycle()
            }

            val outDir = File(context.filesDir, "pdfs").apply { mkdirs() }
            val pdfFile = File(outDir, "PhotoConvert_${System.currentTimeMillis()}.pdf")
            FileOutputStream(pdfFile).use { pdf.writeTo(it) }
            exportPdfToDownloads(pdfFile)
            pdfFile
        } finally {
            pdf.close()
        }
    }

    private fun exportPdfToDownloads(pdfFile: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, pdfFile.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PhotoConvert")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                pdfFile.inputStream().use { it.copyTo(out) }
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
        }
    }
}
