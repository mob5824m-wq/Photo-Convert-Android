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
import com.photoconvert.android.model.ExifInspectionReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

data class LoupePreviewResult(
    val sourceUri: Uri,
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
    val usedQuality: Int,
    val redactedRegions: Int
)

class PhotoConverterEngine(private val context: Context) {

    suspend fun inspectUri(uri: Uri): ExifInspectionReport = withContext(Dispatchers.IO) {
        val (displayName, origSize) = queryMeta(uri)
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to read image.")
        val actualBytes = if (origSize > 0L) origSize else rawBytes.size.toLong()
        val (orientation, _) = rawBytes.inputStream().use { ExifHelper.readExifSnapshot(it) }
        val bmp = decodeModernOrRawBitmap(
            rawBytes = rawBytes,
            filename = displayName,
            reqW = 1024,
            reqH = 1024,
            normalizeSrgb = false,
            exifOrientation = orientation
        )
        val report = ExifHelper.inspectPhotoMetadata(
            rawBytes = rawBytes,
            decodedBitmap = bmp,
            filename = displayName,
            origBytes = actualBytes
        )
        bmp.recycle()
        report
    }

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

        val pipeline = runTransformPipeline(
            rawBytes = rawBytes,
            filename = displayName,
            settings = settings
        )

        val effectiveFmt = settings.effectiveFormat()
        val readyForEncode = if (!effectiveFmt.supportsAlpha && pipeline.bitmap.hasAlpha()) {
            val flattened = Bitmap.createBitmap(
                pipeline.bitmap.width,
                pipeline.bitmap.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(flattened)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(pipeline.bitmap, 0f, 0f, null)
            pipeline.bitmap.recycle()
            flattened
        } else {
            pipeline.bitmap
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
            exifDate = pipeline.exifMap[ExifInterface.TAG_DATETIME_ORIGINAL]
        )

        val outDir = File(context.filesDir, "converted").apply { mkdirs() }
        val id = UUID.randomUUID().toString().take(12)
        val outFile = File(outDir, "${id}_$outputName")
        FileOutputStream(outFile).use { it.write(encoded.bytes) }

        val (exifKept, repairedDate) = if (effectiveFmt.extension != "png") {
            ExifHelper.writeSelectiveExif(
                targetFile = outFile,
                attrs = pipeline.exifMap,
                privacyMode = settings.exifPrivacyMode,
                originalFilename = displayName,
                repairFilenameDates = settings.repairFilenameDates,
                exifArtist = settings.exifArtist,
                exifCopyright = settings.exifCopyright
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
            origWidth = pipeline.origWidth,
            origHeight = pipeline.origHeight,
            outWidth = encoded.finalWidth,
            outHeight = encoded.finalHeight,
            origBytes = actualOrigBytes,
            outBytes = outBytes,
            savedBytes = savedBytes,
            savedPct = savedPct,
            exifKept = exifKept,
            exifPrivacyMode = settings.exifPrivacyMode.id,
            repairedDate = repairedDate,
            cutoutApplied = settings.removeBackground,
            redactedRegions = pipeline.redactedRegions,
            localFilePath = outFile.absolutePath,
            createdAtEpochMs = System.currentTimeMillis()
        )
    }

    suspend fun buildLoupePreview(uri: Uri, settings: ConvertSettings): LoupePreviewResult =
        withContext(Dispatchers.IO) {
            val (displayName, origSize) = queryMeta(uri)
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Unable to read image for preview.")
            val actualOrigBytes = if (origSize > 0L) origSize else rawBytes.size.toLong()
            val (orientation, _) = rawBytes.inputStream().use { ExifHelper.readExifSnapshot(it) }

            val beforeFull = decodeModernOrRawBitmap(
                rawBytes = rawBytes,
                filename = displayName,
                reqW = 1400,
                reqH = 1400,
                normalizeSrgb = false,
                exifOrientation = orientation
            )
            val beforeView = clampPreviewBitmap(beforeFull, 1280)

            val pipeline = runTransformPipeline(rawBytes, displayName, settings)
            val effectiveFmt = settings.effectiveFormat()
            val encoded = TargetSizeCompressor.encodeWithOptionalCeiling(
                source = pipeline.bitmap,
                format = effectiveFmt,
                preferredQuality = settings.clampedQuality(),
                targetSizeKb = settings.targetSizeKb
            )
            pipeline.bitmap.recycle()

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
                sourceUri = uri,
                filename = displayName,
                beforeBitmap = beforeView,
                afterBitmap = afterView,
                origWidth = pipeline.origWidth,
                origHeight = pipeline.origHeight,
                outWidth = encoded.finalWidth,
                outHeight = encoded.finalHeight,
                origBytes = actualOrigBytes,
                outBytes = outBytes,
                savedBytes = savedBytes,
                savedPct = savedPct,
                outputFormatLabel = effectiveFmt.label,
                usedQuality = encoded.usedQuality,
                redactedRegions = pipeline.redactedRegions
            )
        }

    private data class TransformResult(
        val bitmap: Bitmap,
        val origWidth: Int,
        val origHeight: Int,
        val exifMap: Map<String, String>,
        val redactedRegions: Int
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

        if (settings.autoDeskew) {
            val deskewed = DocumentDeskewer.autoDeskewAndTrim(working)
            if (deskewed !== working) working.recycle()
            working = deskewed
        }

        var redactedCount = 0
        if (settings.redactFaces || settings.redactText || settings.redactBoxesSpec.isNotBlank()) {
            val (redacted, cnt) = PrivacyRedactor.applyRedactions(
                src = working,
                redactFaces = settings.redactFaces,
                redactText = settings.redactText,
                redactBoxesSpec = settings.redactBoxesSpec,
                style = settings.redactStyle
            )
            if (redacted !== working) working.recycle()
            working = redacted
            redactedCount = cnt
        }

        if (settings.removeBackground) {
            val cut = SubjectCutoutHelper.cutoutForeground(working)
            if (cut !== working) working.recycle()
            working = cut
        }

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

        if (settings.autoLevels || settings.grayscale || settings.postSharpen) {
            val toned = ImageEnhancer.applyToneAndSharpen(
                src = working,
                autoLevels = settings.autoLevels,
                grayscale = settings.grayscale,
                postSharpen = settings.postSharpen
            )
            if (toned !== working) working.recycle()
            working = toned
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

        if (settings.watermarkLogoUriString.isNotBlank()) {
            val logoBmp = runCatching {
                val logoUri = Uri.parse(settings.watermarkLogoUriString)
                context.contentResolver.openInputStream(logoUri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (logoBmp != null) {
                val withLogo = ImageEnhancer.applyLogoWatermark(
                    src = working,
                    logo = logoBmp,
                    anchor = settings.watermarkAnchor,
                    opacityPct = settings.clampedWatermarkOpacity(),
                    tiled = settings.watermarkTiled
                )
                logoBmp.recycle()
                if (withLogo !== working) working.recycle()
                working = withLogo
            }
        }

        if (settings.watermarkText.isNotBlank()) {
            val marked = ImageEnhancer.applyWatermark(
                src = working,
                text = settings.watermarkText,
                anchor = settings.watermarkAnchor,
                opacityPct = settings.clampedWatermarkOpacity(),
                tiled = settings.watermarkTiled
            )
            if (marked !== working) working.recycle()
            working = marked
        }

        return TransformResult(working, logicalOrigW, logicalOrigH, exifMap, redactedCount)
    }

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
