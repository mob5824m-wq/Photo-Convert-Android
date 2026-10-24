package com.photoconvert.android.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.photoconvert.android.model.AspectRatioOption
import com.photoconvert.android.model.ConvertSettings
import com.photoconvert.android.model.ConvertedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

data class LoupePreviewResult(
    val filename: String,
    val beforeBitmap: Bitmap,
    val afterBitmap: Bitmap,
    val origWidth: Int,
    val origHeight: Int,
    val outWidth: Int,
    val outHeight: Int,
    val origBytes: Long,
    val outBytes: Long,
    val savedBytes: Long,
    val savedPct: Double,
    val outputFormatLabel: String,
    val usedQuality: Int
)

class PhotoConverterEngine(private val context: Context) {

    suspend fun convertUri(
        uri: Uri,
        settings: ConvertSettings,
        sequenceIndex: Int = 1
    ): ConvertedItem = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (displayName, origSize) = queryMeta(uri)
        val rawBytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to read selected image.")
        val actualOrigBytes = if (origSize > 0L) origSize else rawBytes.size.toLong()

        val (transformed, origW, origH, exifMap) = runTransformPipeline(
            rawBytes = rawBytes,
            filename = displayName,
            settings = settings
        )

        val effectiveFmt = settings.effectiveFormat()
        val readyForEncode = if (!effectiveFmt.supportsAlpha && transformed.hasAlpha()) {
            val flattened = Bitmap.createBitmap(transformed.width, transformed.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flattened)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(transformed, 0f, 0f, null)
            transformed.recycle()
            flattened
        } else {
            transformed
        }

        val encoded = TargetSizeCompressor.encodeWithOptionalCeiling(
            source = readyForEncode,
            format = effectiveFmt,
            preferredQuality = settings.clampedQuality(),
            targetSizeKb = settings.targetSizeKb
        )
        readyForEncode.recycle()

        val outputName = BatchRenamer.renderOutputFilename(
            pattern = settings.renamePattern,
            originalFilename = displayName,
            extension = effectiveFmt.extension,
            width = encoded.finalWidth,
            height = encoded.finalHeight,
            seq = sequenceIndex,
            presetSlug = settings.presetSlug,
            exifDate = exifMap[ExifInterface.TAG_DATETIME_ORIGINAL]
        )

        val outDir = File(context.filesDir, "converted").apply { mkdirs() }
        val id = UUID.randomUUID().toString().take(12)
        val outFile = File(outDir, "${id}_$outputName")
        FileOutputStream(outFile).use { it.write(encoded.bytes) }

        val (exifKept, repairedDate) = if (!settings.stripExif && effectiveFmt.extension != "png") {
            ExifHelper.writePreservedExif(
                targetFile = outFile,
                attrs = exifMap,
                originalFilename = displayName,
                repairFilenameDates = settings.repairFilenameDates
            )
        } else {
            false to false
        }

        exportToMediaStore(outFile, outputName, effectiveFmt.mimeType)

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
            outputFormat = effectiveFmt.label.uppercase(),
            mimeType = effectiveFmt.mimeType,
            origWidth = origW,
            origHeight = origH,
            outWidth = encoded.finalWidth,
            outHeight = encoded.finalHeight,
            origBytes = actualOrigBytes,
            outBytes = outBytes,
            savedBytes = savedBytes,
            savedPct = savedPct,
            exifKept = exifKept,
            repairedDate = repairedDate,
            cutoutApplied = settings.removeBackground,
            localFilePath = outFile.absolutePath,
            createdAtEpochMs = System.currentTimeMillis()
        )
    }

    /**
     * Build live in-memory Split-Screen Before/After Loupe preview (#1).
     */
    suspend fun buildLoupePreview(uri: Uri, settings: ConvertSettings): LoupePreviewResult =
        withContext(Dispatchers.IO) {
            val (displayName, origSize) = queryMeta(uri)
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Unable to read image for preview.")
            val actualOrigBytes = if (origSize > 0L) origSize else rawBytes.size.toLong()

            val beforeFull = decodeModernOrRawBitmap(
                rawBytes = rawBytes,
                filename = displayName,
                reqW = 1400,
                reqH = 1400,
                normalizeSrgb = false
            )
            val beforeView = clampPreviewBitmap(beforeFull, 1280)

            val (transformed, origW, origH, _) = runTransformPipeline(rawBytes, displayName, settings)
            val effectiveFmt = settings.effectiveFormat()
            val encoded = TargetSizeCompressor.encodeWithOptionalCeiling(
                source = transformed,
                format = effectiveFmt,
                preferredQuality = settings.clampedQuality(),
                targetSizeKb = settings.targetSizeKb
            )
            transformed.recycle()

            val decodedAfter = BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size)
                ?: error("Could not decode preview output.")
            val afterView = clampPreviewBitmap(decodedAfter, 1280)

            val outBytes = encoded.bytes.size.toLong()
            val savedBytes = actualOrigBytes - outBytes
            val savedPct = if (actualOrigBytes > 0L) {
                ((savedBytes.toDouble() / actualOrigBytes.toDouble()) * 1000.0).roundToInt() / 10.0
            } else {
                0.0
            }

            LoupePreviewResult(
                filename = displayName,
                beforeBitmap = beforeView,
                afterBitmap = afterView,
                origWidth = origW,
                origHeight = origH,
                outWidth = encoded.finalWidth,
                outHeight = encoded.finalHeight,
                origBytes = actualOrigBytes,
                outBytes = outBytes,
                savedBytes = savedBytes,
                savedPct = savedPct,
                outputFormatLabel = effectiveFmt.label,
                usedQuality = encoded.usedQuality
            )
        }

    private data class TransformResult(
        val bitmap: Bitmap,
        val origWidth: Int,
        val origHeight: Int,
        val exifMap: Map<String, String>
    )

    private suspend fun runTransformPipeline(
        rawBytes: ByteArray,
        filename: String,
        settings: ConvertSettings
    ): TransformResult {
        val (orientation, exifMap) = rawBytes.inputStream().use { ExifHelper.readExifSnapshot(it) }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)

        val swapped = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        val rawW = if (bounds.outWidth > 0) bounds.outWidth else 2048
        val rawH = if (bounds.outHeight > 0) bounds.outHeight else 2048
        val logicalOrigW = if (swapped) rawH else rawW
        val logicalOrigH = if (swapped) rawW else rawH

        val (targetW, targetH) = DimensionCalculator.calculateTargetDimensions(
            width = logicalOrigW,
            height = logicalOrigH,
            preset = settings.resizePreset,
            customMaxEdgePx = settings.clampedCustomEdge()
        )

        var working = decodeModernOrRawBitmap(
            rawBytes = rawBytes,
            filename = filename,
            reqW = targetW,
            reqH = targetH,
            normalizeSrgb = settings.normalizeSrgb,
            exifOrientation = orientation
        )

        if (settings.removeBackground) {
            val cut = SubjectCutoutHelper.cutoutForeground(working)
            if (cut !== working) working.recycle()
            working = cut
        }

        if (working.width != targetW || working.height != targetH) {
            val (recalcW, recalcH) = DimensionCalculator.calculateTargetDimensions(
                width = working.width,
                height = working.height,
                preset = settings.resizePreset,
                customMaxEdgePx = settings.clampedCustomEdge()
            )
            if (recalcW != working.width || recalcH != working.height) {
                val scaled = Bitmap.createScaledBitmap(working, recalcW, recalcH, true)
                if (scaled !== working) working.recycle()
                working = scaled
            }
        }

        if (settings.aspectRatio != AspectRatioOption.ORIGINAL) {
            val framed = ImageEnhancer.applyAspectFrame(
                src = working,
                aspect = settings.aspectRatio,
                fillMode = settings.frameFillMode
            )
            if (framed !== working) working.recycle()
            working = framed
        }

        if (settings.watermarkText.isNotBlank()) {
            val marked = ImageEnhancer.applyWatermark(
                src = working,
                text = settings.watermarkText,
                anchor = settings.watermarkAnchor,
                opacityPct = settings.clampedWatermarkOpacity()
            )
            if (marked !== working) working.recycle()
            working = marked
        }

        return TransformResult(working, logicalOrigW, logicalOrigH, exifMap)
    }

    /**
     * Decodes HEIC/AVIF via hardware ImageDecoder on API 28+ (#5) and normalizes Display P3 -> sRGB (#9).
     */
    private fun decodeModernOrRawBitmap(
        rawBytes: ByteArray,
        filename: String,
        reqW: Int,
        reqH: Int,
        normalizeSrgb: Boolean,
        exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL
    ): Bitmap {
        val lower = filename.lowercase()
        val preferImageDecoder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            (lower.endsWith(".heic") || lower.endsWith(".heif") || lower.endsWith(".avif"))

        if (preferImageDecoder && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decodedModern = runCatching {
                val src = ImageDecoder.createSource(ByteBuffer.wrap(rawBytes))
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    if (normalizeSrgb) {
                        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    }
                    val maxReq = max(reqW, reqH)
                    val maxSrc = max(info.size.width, info.size.height)
                    if (maxSrc > maxReq && maxReq > 0) {
                        val ratio = maxReq.toFloat() / maxSrc.toFloat()
                        decoder.setTargetSize(
                            max(1, (info.size.width * ratio).roundToInt()),
                            max(1, (info.size.height * ratio).roundToInt())
                        )
                    }
                }
            }.getOrNull()
            if (decodedModern != null) return decodedModern
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        val decodeOpts = BitmapFactory.Options().apply {
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                inSampleSize = DimensionCalculator.calculateInSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    reqW,
                    reqH
                )
            }
            inPreferredConfig = Bitmap.Config.ARGB_8888
            if (normalizeSrgb) {
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
        }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
            ?: error("Unsupported or corrupted image / RAW file.")
        return ExifHelper.applyOrientation(decoded, exifOrientation)
    }

    private fun clampPreviewBitmap(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * ratio).roundToInt()),
            max(1, (bitmap.height * ratio).roundToInt()),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun exportToMediaStore(sourceFile: File, displayName: String, mimeType: String) {
        runCatching {
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
            val itemUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(itemUri)?.use { out ->
                sourceFile.inputStream().use { it.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    itemUri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
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
