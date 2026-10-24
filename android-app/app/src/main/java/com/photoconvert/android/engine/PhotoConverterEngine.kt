package com.photoconvert.android.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.photoconvert.android.model.ConvertSettings
import com.photoconvert.android.model.ConvertedItem
import com.photoconvert.android.model.OutputFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

class PhotoConverterEngine(private val context: Context) {

    suspend fun convertUri(uri: Uri, settings: ConvertSettings): ConvertedItem = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (displayName, origSize) = queryMeta(uri)
        val rawBytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to read selected image.")
        val actualOrigBytes = if (origSize > 0L) origSize else rawBytes.size.toLong()

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
            error("Unsupported or corrupted image file.")
        }

        val (orientation, exifMap) = rawBytes.inputStream().use { ExifHelper.readExifSnapshot(it) }
        val swapped = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        val logicalOrigW = if (swapped) boundsOpts.outHeight else boundsOpts.outWidth
        val logicalOrigH = if (swapped) boundsOpts.outWidth else boundsOpts.outHeight

        val (targetW, targetH) = DimensionCalculator.calculateTargetDimensions(
            width = logicalOrigW,
            height = logicalOrigH,
            preset = settings.resizePreset,
            customMaxEdgePx = settings.clampedCustomEdge()
        )

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = DimensionCalculator.calculateInSampleSize(
                logicalOrigW,
                logicalOrigH,
                targetW,
                targetH
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
            ?: error("Could not decode image pixels.")

        val upright = ExifHelper.applyOrientation(decoded, orientation)
        val resized = if (upright.width != targetW || upright.height != targetH) {
            val scaled = Bitmap.createScaledBitmap(upright, targetW, targetH, true)
            if (scaled !== upright) upright.recycle()
            scaled
        } else {
            upright
        }

        val prepared = if (settings.format == OutputFormat.JPEG && resized.hasAlpha()) {
            val flattened = Bitmap.createBitmap(resized.width, resized.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flattened)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(resized, 0f, 0f, null)
            resized.recycle()
            flattened
        } else {
            resized
        }

        val outDir = File(context.filesDir, "converted").apply { mkdirs() }
        val id = UUID.randomUUID().toString().take(12)
        val cleanStem = displayName.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .ifBlank { "photo" }
        val outputName = "$cleanStem.${settings.format.extension}"
        val outFile = File(outDir, "${id}_$outputName")

        FileOutputStream(outFile).use { fos ->
            val compressFormat = resolveCompressFormat(settings.format)
            val q = if (settings.format.isLossless) 100 else settings.clampedQuality()
            prepared.compress(compressFormat, q, fos)
        }
        val finalW = prepared.width
        val finalH = prepared.height
        prepared.recycle()

        val exifKept = if (!settings.stripExif && settings.format != OutputFormat.PNG) {
            ExifHelper.writePreservedExif(outFile, exifMap)
        } else {
            false
        }

        exportToMediaStore(outFile, outputName, settings.format.mimeType)

        val outBytes = outFile.length()
        val savedBytes = actualOrigBytes - outBytes
        val savedPct = if (actualOrigBytes > 0L) {
            ((savedBytes.toDouble() / actualOrigBytes.toDouble()) * 1000.0).roundToInt() / 10.0
        } else {
            0.0
        }

        val origExt = displayName.substringAfterLast('.', "IMG").uppercase()

        ConvertedItem(
            id = id,
            originalName = displayName,
            outputName = outputName,
            originalFormat = origExt,
            outputFormat = settings.format.label.uppercase(),
            mimeType = settings.format.mimeType,
            origWidth = logicalOrigW,
            origHeight = logicalOrigH,
            outWidth = finalW,
            outHeight = finalH,
            origBytes = actualOrigBytes,
            outBytes = outBytes,
            savedBytes = savedBytes,
            savedPct = savedPct,
            exifKept = exifKept,
            localFilePath = outFile.absolutePath,
            createdAtEpochMs = System.currentTimeMillis()
        )
    }

    @Suppress("DEPRECATION")
    private fun resolveCompressFormat(format: OutputFormat): Bitmap.CompressFormat {
        return when (format) {
            OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
            OutputFormat.PNG -> Bitmap.CompressFormat.PNG
            OutputFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
        }
    }

    private fun exportToMediaStore(sourceFile: File, displayName: String, mimeType: String) {
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/PhotoConvert"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val itemUri = resolver.insert(collection, values) ?: return
            resolver.openOutputStream(itemUri)?.use { out ->
                sourceFile.inputStream().use { it.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val doneValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(itemUri, doneValues, null, null)
            }
        } catch (_: Exception) {
            // Local converted copy in filesDir/converted remains available for sharing
        }
    }

    private fun queryMeta(uri: Uri): Pair<String, Long> {
        var name = "photo.jpg"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }
        return name to size
    }
}
