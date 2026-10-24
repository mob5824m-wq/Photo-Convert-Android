package com.photoconvert.android.engine

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.photoconvert.android.model.ConvertedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BatchZipExporter(private val context: Context) {

    suspend fun exportItemsToZip(items: List<ConvertedItem>): File = withContext(Dispatchers.IO) {
        val valid = items.filter { File(it.localFilePath).isFile }
        require(valid.isNotEmpty()) { "No converted files available to archive." }

        val outDir = File(context.filesDir, "exports").apply { mkdirs() }
        val zipFile = File(outDir, "PhotoConvert_Batch_${System.currentTimeMillis()}.zip")
        val usedNames = HashMap<String, Int>()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for (item in valid) {
                val src = File(item.localFilePath)
                val count = usedNames[item.outputName] ?: 0
                usedNames[item.outputName] = count + 1
                val entryName = if (count == 0) {
                    item.outputName
                } else {
                    val stem = item.outputName.substringBeforeLast('.')
                    val ext = item.outputName.substringAfterLast('.', "webp")
                    "${stem}_${count + 1}.$ext"
                }
                zos.putNextEntry(ZipEntry(entryName))
                src.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }

        exportZipToDownloads(context, zipFile)
        zipFile
    }

    companion object {
        fun exportZipToDownloads(context: Context, zipFile: File) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            runCatching {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, zipFile.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/PhotoConvert"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                resolver.openOutputStream(uri)?.use { out ->
                    zipFile.inputStream().use { it.copyTo(out) }
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
}
