package com.photoconvert.android.engine

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.photoconvert.android.model.ExifInspectionReport
import com.photoconvert.android.model.ExifPrivacyMode
import java.io.File
import java.io.InputStream
import java.util.Locale

object ExifHelper {

    private val NON_GPS_SAFE_TAGS = arrayOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT
    )

    private val GPS_TAGS = arrayOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF
    )

    fun readExifSnapshot(stream: InputStream): Pair<Int, Map<String, String>> {
        return try {
            val exif = ExifInterface(stream)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val attrs = mutableMapOf<String, String>()
            for (tag in NON_GPS_SAFE_TAGS + GPS_TAGS) {
                val value = exif.getAttribute(tag)
                if (!value.isNullOrBlank()) {
                    attrs[tag] = value
                }
            }
            orientation to attrs
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL to emptyMap()
        }
    }

    fun inspectPhotoMetadata(
        rawBytes: ByteArray,
        decodedBitmap: Bitmap,
        filename: String,
        origBytes: Long
    ): ExifInspectionReport {
        val tags = mutableListOf<Pair<String, String>>()
        var hasGps = false
        var gpsSummary = "Clean — No GPS coordinates"

        try {
            val exif = ExifInterface(rawBytes.inputStream())
            val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
            val camera = listOf(make, model).filter { it.isNotEmpty() }.joinToString(" ")
            if (camera.isNotEmpty()) tags.add("Camera" to camera)

            exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.takeIf { it.isNotBlank() }?.let {
                tags.add("Lens" to it)
            }
            val dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            if (!dt.isNullOrBlank()) {
                tags.add("Captured" to dt)
            } else {
                BatchRenamer.inferExifDateFromFilename(filename)?.let {
                    tags.add("Filename Date" to "$it (repairable)")
                }
            }

            exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.takeIf { it.isNotBlank() }?.let {
                tags.add("ISO" to it)
            }
            exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.takeIf { it.isNotBlank() }?.let {
                tags.add("Aperture" to "f/$it")
            }
            exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.takeIf { it.isNotBlank() }?.let {
                tags.add("Shutter" to "${it}s")
            }
            exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.takeIf { it.isNotBlank() }?.let {
                tags.add("Focal Length" to it)
            }
            exif.getAttribute(ExifInterface.TAG_ARTIST)?.takeIf { it.isNotBlank() }?.let {
                tags.add("Artist" to it)
            }
            exif.getAttribute(ExifInterface.TAG_COPYRIGHT)?.takeIf { it.isNotBlank() }?.let {
                tags.add("Copyright" to it)
            }

            val latLong = exif.latLong
            if (latLong != null && latLong.size == 2) {
                hasGps = true
                gpsSummary = String.format(Locale.US, "%.5f, %.5f", latLong[0], latLong[1])
            } else if (!exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE).isNullOrBlank()) {
                hasGps = true
                gpsSummary = "GPS tags present"
            }
        } catch (_: Exception) {
        }

        val sharpness = BurstDeduplicator.computeSharpnessScore(decodedBitmap)
        val dHashHex = BurstDeduplicator.formatDHashHex(BurstDeduplicator.computeDHash(decodedBitmap))
        val palette = PaletteExtractor.extractDominantHexColors(decodedBitmap, 5)
        val ext = filename.substringAfterLast('.', "IMG").uppercase(Locale.US)

        return ExifInspectionReport(
            filename = filename,
            format = ext,
            width = decodedBitmap.width,
            height = decodedBitmap.height,
            bytes = origBytes,
            hasGps = hasGps,
            gpsSummary = gpsSummary,
            sharpnessScore = sharpness,
            dHashHex = dHashHex,
            paletteHex = palette,
            tags = tags
        )
    }

    fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    fun writeSelectiveExif(
        targetFile: File,
        attrs: Map<String, String>,
        privacyMode: ExifPrivacyMode,
        originalFilename: String,
        repairFilenameDates: Boolean,
        exifArtist: String = "",
        exifCopyright: String = ""
    ): Pair<Boolean, Boolean> {
        val outAttrs = mutableMapOf<String, String>()
        var repaired = false

        if (privacyMode == ExifPrivacyMode.SCRUB_GPS_ONLY || privacyMode == ExifPrivacyMode.KEEP_ALL) {
            for (tag in NON_GPS_SAFE_TAGS) {
                attrs[tag]?.let { outAttrs[tag] = it }
            }
            if (privacyMode == ExifPrivacyMode.KEEP_ALL) {
                for (tag in GPS_TAGS) {
                    attrs[tag]?.let { outAttrs[tag] = it }
                }
            }

            val hasCaptureDate = !outAttrs[ExifInterface.TAG_DATETIME_ORIGINAL].isNullOrBlank()
            if (!hasCaptureDate && repairFilenameDates) {
                val inferred = BatchRenamer.inferExifDateFromFilename(originalFilename)
                if (inferred != null) {
                    outAttrs[ExifInterface.TAG_DATETIME_ORIGINAL] = inferred
                    outAttrs[ExifInterface.TAG_DATETIME_DIGITIZED] = inferred
                    outAttrs[ExifInterface.TAG_DATETIME] = inferred
                    repaired = true
                }
            }
        }

        if (exifArtist.isNotBlank()) {
            outAttrs[ExifInterface.TAG_ARTIST] = exifArtist.trim().take(80)
        }
        if (exifCopyright.isNotBlank()) {
            outAttrs[ExifInterface.TAG_COPYRIGHT] = exifCopyright.trim().take(120)
        }

        if (outAttrs.isEmpty()) return false to false

        return try {
            val exif = ExifInterface(targetFile)
            for ((tag, value) in outAttrs) {
                exif.setAttribute(tag, value)
            }
            exif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString()
            )
            exif.saveAttributes()
            true to repaired
        } catch (_: Exception) {
            false to false
        }
    }
}
