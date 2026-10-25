package com.photoconvert.android.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BatchRenamer {

    private val DATE_TIME_REGEX = Regex(
        """(?<!\d)(20\d{2}|19\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])(?:[-_ T.]?([01]\d|2[0-3])[-_.]?([0-5]\d)[-_.]?([0-5]\d))?"""
    )

    fun inferExifDateFromFilename(filename: String): String? {
        val stem = filename.substringBeforeLast('.')
        val match = DATE_TIME_REGEX.find(stem) ?: return null
        val (year, month, day, hh, mm, ss) = match.destructured
        val hour = hh.ifEmpty { "12" }
        val min = mm.ifEmpty { "00" }
        val sec = ss.ifEmpty { "00" }
        return "$year:$month:$day $hour:$min:$sec"
    }

    fun compactDateFromExif(exifDate: String?): String {
        if (!exifDate.isNullOrBlank() && exifDate.length >= 10) {
            val digits = exifDate.take(10).replace(":", "").replace("-", "")
            if (digits.length == 8 && digits.all { it.isDigit() }) {
                return digits
            }
        }
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    }

    fun renderOutputFilename(
        pattern: String,
        originalFilename: String,
        extension: String,
        width: Int,
        height: Int,
        seq: Int = 1,
        presetSlug: String = "custom",
        exifDate: String? = null
    ): String {
        val rawStem = originalFilename.substringBeforeLast('.').ifBlank { "photo" }
        val cleanOrig = rawStem.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifBlank { "photo" }
        val cleanExt = extension.trimStart('.').lowercase(Locale.US)
        val cleanPreset = presetSlug.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifBlank { "custom" }
        val dateToken = compactDateFromExif(exifDate ?: inferExifDateFromFilename(originalFilename))
        val seqToken = String.format(Locale.US, "%03d", seq.coerceAtLeast(1))

        val template = pattern.trim().ifBlank { "{orig}" }
        val expanded = template
            .replace("{orig}", cleanOrig)
            .replace("{date}", dateToken)
            .replace("{preset}", cleanPreset)
            .replace("{format}", cleanExt)
            .replace("{width}", width.toString())
            .replace("{height}", height.toString())
            .replace("{seq}", seqToken)

        val sanitized = expanded
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('.', '_')
            .ifBlank { cleanOrig }

        val stemWithoutDupExt = if (sanitized.lowercase(Locale.US).endsWith(".$cleanExt")) {
            sanitized.dropLast(cleanExt.length + 1)
        } else {
            sanitized
        }
        return "$stemWithoutDupExt.$cleanExt"
    }
}
