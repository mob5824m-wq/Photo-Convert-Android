package com.pixelnas.photosaver.converter

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Preserves embedded Google Camera / Android Motion Photo MP4 streams (`MVIMG_*.jpg`, `PXL_*.MP.jpg`)
 * across >16 MP -> <=16 MP Storage Saver resizing.
 */
object MotionPhotoHelper {

    private val MICROVIDEO_RE = Regex("""GCamera:MicroVideoOffset="(\d+)"""")
    private val FTYP_BRANDS = listOf(
        "ftypmp42".toByteArray(Charsets.US_ASCII),
        "ftypisom".toByteArray(Charsets.US_ASCII),
        "ftypiso2".toByteArray(Charsets.US_ASCII),
        "ftypavc1".toByteArray(Charsets.US_ASCII)
    )

    fun extractEmbeddedMp4(sourceFile: File): ByteArray? {
        val ext = sourceFile.extension.lowercase()
        if (ext != "jpg" && ext != "jpeg") return null
        val len = sourceFile.length()
        if (len < 4096L || len > 120L * 1024L * 1024L) return null

        val data = runCatching { sourceFile.readBytes() }.getOrNull() ?: return null
        val size = data.size

        // 1. Check XMP GCamera:MicroVideoOffset in header
        val headText = String(data, 0, minOf(size, 65536), Charsets.ISO_8859_1)
        val match = MICROVIDEO_RE.find(headText)
        if (match != null) {
            val offsetFromEof = match.groupValues[1].toIntOrNull() ?: 0
            if (offsetFromEof in 512 until (size - 256)) {
                val start = size - offsetFromEof
                val probe = String(data, start, minOf(32, size - start), Charsets.ISO_8859_1)
                if (probe.contains("ftyp")) {
                    return data.copyOfRange(start, size)
                }
            }
        }

        // 2. Fallback scan for MP4 `ftyp` box after JPEG EOI (0xFF 0xD9)
        val searchFrom = maxOf(256, size / 5)
        for (sig in FTYP_BRANDS) {
            val idx = indexOfBytes(data, sig, searchFrom)
            if (idx > 8) {
                val boxStart = idx - 4
                if (hasJpegEoiNear(data, maxOf(0, boxStart - 64), boxStart + 2)) {
                    return data.copyOfRange(boxStart, size)
                }
            }
        }
        return null
    }

    fun attachMotionMp4ToJpeg(jpegFile: File, mp4Bytes: ByteArray): Boolean {
        if (mp4Bytes.size < 32 || !jpegFile.isFile) return false
        val raw = runCatching { jpegFile.readBytes() }.getOrNull() ?: return false
        if (raw.size < 4 || raw[0] != 0xFF.toByte() || raw[1] != 0xD8.toByte()) return false

        var pos = 2
        val n = raw.size
        while (pos + 4 <= n && raw[pos] == 0xFF.toByte()) {
            val marker = raw[pos + 1].toInt() and 0xFF
            if (marker != 0xE0 && marker != 0xE1) break
            val segLen = ((raw[pos + 2].toInt() and 0xFF) shl 8) or (raw[pos + 3].toInt() and 0xFF)
            if (segLen < 2 || pos + 2 + segLen > n) break
            pos += 2 + segLen
        }

        val xmpSeg = buildXmpApp1Segment(mp4Bytes.size)
        val out = ByteArrayOutputStream(raw.size + xmpSeg.size + mp4Bytes.size)
        out.write(raw, 0, pos)
        out.write(xmpSeg)
        out.write(raw, pos, raw.size - pos)
        out.write(mp4Bytes)
        jpegFile.writeBytes(out.toByteArray())
        return true
    }

    private fun buildXmpApp1Segment(mp4Length: Int): ByteArray {
        val xml = """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="$mp4Length" GCamera:MicroVideoPresentationTimestampUs="0"/></rdf:RDF></x:xmpmeta><?xpacket end="w"?>"""
        val ns = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val xmlBytes = xml.toByteArray(Charsets.UTF_8)
        val payloadLen = ns.size + xmlBytes.size + 2
        val seg = ByteArray(2 + 2 + ns.size + xmlBytes.size)
        seg[0] = 0xFF.toByte()
        seg[1] = 0xE1.toByte()
        seg[2] = ((payloadLen ushr 8) and 0xFF).toByte()
        seg[3] = (payloadLen and 0xFF).toByte()
        System.arraycopy(ns, 0, seg, 4, ns.size)
        System.arraycopy(xmlBytes, 0, seg, 4 + ns.size, xmlBytes.size)
        return seg
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, fromIndex: Int): Int {
        val limit = haystack.size - needle.size
        outer@ for (i in fromIndex..limit) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun hasJpegEoiNear(data: ByteArray, start: Int, endExclusive: Int): Boolean {
        val stop = minOf(data.size - 1, endExclusive)
        for (i in start until stop) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) return true
        }
        return false
    }
}
