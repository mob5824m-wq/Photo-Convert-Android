package com.pixelnas.photosaver.converter

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Safe streaming Google Takeout / archive (.zip) unpacker for Closet Pixel 1-5 Incoming folders.
 */
object TakeoutZipExtractor {

    private const val MAX_ARCHIVE_EXTRACTED_BYTES = 8L * 1024L * 1024L * 1024L

    fun extractIncomingZip(
        zipFile: File,
        allowedMediaExtensions: Set<String>,
        deleteArchiveAfter: Boolean = true
    ): List<File> {
        if (!zipFile.isFile || !zipFile.extension.equals("zip", ignoreCase = true)) {
            return emptyList()
        }
        val parentDir = zipFile.parentFile ?: return emptyList()
        val unpackRoot = File(parentDir, "${zipFile.nameWithoutExtension}_unpacked").canonicalFile
        unpackRoot.mkdirs()

        val extractedMedia = mutableListOf<File>()
        var totalWritten = 0L
        val buffer = ByteArray(128 * 1024)

        ZipInputStream(FileInputStream(zipFile).buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val rawParts = entry.name.replace('\\', '/').trimStart('/').split('/')
                    .filter { it.isNotBlank() && it != "." && it != ".." && !it.startsWith("__MACOSX") }
                if (rawParts.isEmpty() || rawParts.last().startsWith(".")) {
                    zis.closeEntry()
                    continue
                }
                val cleanParts = when {
                    rawParts.size >= 3 && rawParts[0].equals("Takeout", true) && rawParts[1].contains("Photos", true) ->
                        rawParts.drop(2)
                    rawParts.size >= 2 && rawParts[0].equals("Takeout", true) ->
                        rawParts.drop(1)
                    else -> rawParts
                }
                val leaf = cleanParts.last()
                val ext = leaf.substringAfterLast('.', "").lowercase()
                val isSidecar = ext == "json"
                val isMedia = ext in allowedMediaExtensions
                if (!isSidecar && !isMedia) {
                    zis.closeEntry()
                    continue
                }

                var dest = File(unpackRoot, cleanParts.joinToString("/")).canonicalFile
                if (!dest.path.startsWith(unpackRoot.path)) {
                    zis.closeEntry()
                    continue
                }
                dest.parentFile?.mkdirs()
                if (dest.exists() && isMedia) {
                    dest = File(dest.parentFile, "${dest.nameWithoutExtension}_${UUID.randomUUID().toString().take(6)}.${dest.extension}")
                }

                val tmp = File(dest.parentFile, ".unzip_${UUID.randomUUID()}.part")
                try {
                    FileOutputStream(tmp).use { fos ->
                        while (true) {
                            val read = zis.read(buffer)
                            if (read <= 0) break
                            totalWritten += read
                            if (totalWritten > MAX_ARCHIVE_EXTRACTED_BYTES) break
                            fos.write(buffer, 0, read)
                        }
                    }
                    if (totalWritten <= MAX_ARCHIVE_EXTRACTED_BYTES) {
                        if (!tmp.renameTo(dest)) {
                            tmp.copyTo(dest, overwrite = true)
                            tmp.delete()
                        }
                        if (isMedia) extractedMedia.add(dest)
                    }
                } finally {
                    if (tmp.exists()) tmp.delete()
                    zis.closeEntry()
                }
                if (totalWritten > MAX_ARCHIVE_EXTRACTED_BYTES) break
            }
        }

        if (deleteArchiveAfter) {
            zipFile.delete()
        }
        return extractedMedia
    }
}
