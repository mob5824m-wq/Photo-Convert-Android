package com.photoconvert.android.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.provider.OpenableColumns
import com.photoconvert.android.model.OutputFormat
import com.photoconvert.android.model.WebPackBundleResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

class WebPackExporter(private val context: Context) {

    suspend fun buildResponsiveWebPack(
        uri: Uri,
        quality: Int = 82
    ): WebPackBundleResult = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to read selected image.")
        val (orientation, _) = raw.inputStream().use { ExifHelper.readExifSnapshot(it) }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            ?: error("Unsupported image.")
        val upright = ExifHelper.applyOrientation(decoded, orientation)

        val origW = upright.width
        val origH = upright.height
        val rawName = queryName(uri).substringBeforeLast('.')
        val stem = rawName.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').lowercase(Locale.US)
            .ifBlank { "hero-image" }

        val palette = PaletteExtractor.extractDominantHexColors(upright, 5)
        val lqip = PaletteExtractor.generateLqipDataUrl(upright)

        val defaultStops = listOf(480, 960, 1920)
        val widths = (defaultStops.filter { it <= origW } + minOf(origW, 1920)).distinct().sorted()

        val outDir = File(context.filesDir, "exports").apply { mkdirs() }
        val zipFile = File(outDir, "${stem}-webpack.zip")

        val webpSrcset = mutableListOf<String>()
        val jpgSrcset = mutableListOf<String>()
        var fallbackJpg = "images/$stem-${widths.last()}w.jpg"
        var fallbackW = origW
        var fallbackH = origH

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for (w in widths) {
                val h = max(1, (origH * (w.toFloat() / origW.toFloat())).roundToInt())
                val scaled = if (w == origW) upright else Bitmap.createScaledBitmap(upright, w, h, true)

                val webpName = "images/$stem-${w}w.webp"
                val webpBytes = TargetSizeCompressor.compressOnce(scaled, OutputFormat.WEBP, quality)
                zos.putNextEntry(ZipEntry(webpName))
                zos.write(webpBytes)
                zos.closeEntry()
                webpSrcset.add("$webpName ${w}w")

                val jpgName = "images/$stem-${w}w.jpg"
                val jpgBytes = TargetSizeCompressor.compressOnce(scaled, OutputFormat.JPEG, quality)
                zos.putNextEntry(ZipEntry(jpgName))
                zos.write(jpgBytes)
                zos.closeEntry()
                jpgSrcset.add("$jpgName ${w}w")

                fallbackJpg = jpgName
                fallbackW = w
                fallbackH = h
                if (scaled !== upright) scaled.recycle()
            }

            val htmlSnippet = buildString {
                appendLine("<picture>")
                appendLine("  <source type=\"image/webp\" srcset=\"${webpSrcset.joinToString(", ")}\" sizes=\"(max-width: 960px) 100vw, 960px\" />")
                appendLine("  <img")
                appendLine("    src=\"$fallbackJpg\"")
                appendLine("    srcset=\"${jpgSrcset.joinToString(", ")}\"")
                appendLine("    width=\"$fallbackW\"")
                appendLine("    height=\"$fallbackH\"")
                appendLine("    loading=\"lazy\"")
                appendLine("    decoding=\"async\"")
                appendLine("    alt=\"$stem\"")
                appendLine("    style=\"background: ${palette.first()} url('$lqip') center/cover no-repeat;\"")
                appendLine("  />")
                append("</picture>")
            }

            zos.putNextEntry(ZipEntry("snippet.html"))
            zos.write(htmlSnippet.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val manifestJson = JSONObject()
                .put("name", stem)
                .put("breakpoints", JSONArray(widths))
                .put("dominant_palette", JSONArray(palette))
                .put("lqip_data_url", lqip)
                .put("html_snippet", htmlSnippet)
                .toString(2)

            zos.putNextEntry(ZipEntry("palette.json"))
            zos.write(manifestJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            upright.recycle()
            BatchZipExporter.exportZipToDownloads(context, zipFile)

            return@withContext WebPackBundleResult(
                zipFile = zipFile,
                baseName = stem,
                breakpoints = widths,
                paletteHex = palette,
                htmlSnippet = htmlSnippet
            )
        }
    }

    private fun queryName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "image.jpg"
        }
        return uri.lastPathSegment ?: "image.jpg"
    }
}
