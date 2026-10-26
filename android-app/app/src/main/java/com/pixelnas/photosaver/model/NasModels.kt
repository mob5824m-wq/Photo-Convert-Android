package com.pixelnas.photosaver.model

import java.io.File

data class PixelHardwareInfo(
    val profileId: String,
    val displayName: String,
    val codename: String,
    val googlePhotosTier: String,
    val recommendedSaver: Boolean,
    val notes: String
)

data class FolderProfileRule(
    val folderName: String,
    val storageSaverEnabled: Boolean = true,
    val maxMegapixels: Double = 16.0,
    val jpegQuality: Int = 85,
    val compressVideos: Boolean = true,
    val stripGps: Boolean = false,
    val outputSubfolder: String = ""
)

fun defaultFolderProfiles(): List<FolderProfileRule> = listOf(
    FolderProfileRule(
        folderName = "StorageSaver",
        storageSaverEnabled = true,
        maxMegapixels = 16.0,
        jpegQuality = 85,
        compressVideos = true,
        stripGps = false,
        outputSubfolder = ""
    ),
    FolderProfileRule(
        folderName = "Original",
        storageSaverEnabled = false,
        maxMegapixels = 16.0,
        jpegQuality = 95,
        compressVideos = false,
        stripGps = false,
        outputSubfolder = ""
    ),
    FolderProfileRule(
        folderName = "Family",
        storageSaverEnabled = true,
        maxMegapixels = 16.0,
        jpegQuality = 85,
        compressVideos = true,
        stripGps = false,
        outputSubfolder = "Family"
    ),
    FolderProfileRule(
        folderName = "PublicShare",
        storageSaverEnabled = true,
        maxMegapixels = 16.0,
        jpegQuality = 85,
        compressVideos = true,
        stripGps = true,
        outputSubfolder = "PublicShare"
    )
)

data class NasConfig(
    val serverEnabled: Boolean = true,
    val startOnBoot: Boolean = true,
    val deviceProfile: String = "pixel_5",
    val storageSaverEnabled: Boolean = true,
    val deleteOriginals: Boolean = true,
    val autoWatchEnabled: Boolean = true,
    val compressVideos: Boolean = true,
    val avoidRecompressingSmallJpegs: Boolean = true,
    val skipDuplicates: Boolean = true,
    val recursiveSubfolders: Boolean = true,
    val restoreExifFromFilename: Boolean = true,
    val mergeTakeoutJson: Boolean = true,
    val preserveMotionPhotos: Boolean = true,
    val normalizeSrgb: Boolean = true,
    val stripGpsGlobally: Boolean = false,
    val organizeByDate: Boolean = false,
    val perceptualDedupEnabled: Boolean = false,
    val perceptualHammingThreshold: Int = 6,
    val extractTakeoutZips: Boolean = true,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: Int = 23,
    val quietHoursEnd: Int = 7,
    val webhookUrl: String = "",
    val webhookOnQuarantine: Boolean = true,
    val webhookOnLowStorage: Boolean = true,
    val quarantineEnabled: Boolean = true,
    val quarantineMaxRetries: Int = 2,
    val autoPurgeOutputDays: Int = 0,
    val minFreeStorageGb: Float = 0.0f,
    val maxMegapixels: Double = 16.0,
    val jpegQuality: Int = 85,
    val maxVideoShortSide: Int = 1080,
    val httpPort: Int = 8080,
    val smbEnabled: Boolean = true,
    val smbPort: Int = 4445,
    val smbShareName: String = "PixelNAS",
    val ftpEnabled: Boolean = true,
    val ftpPort: Int = 2121,
    val thermalLimitCelsius: Float = 41.0f,
    val incomingDir: String = "/storage/emulated/0/PixelNAS/Incoming",
    val outputDir: String = "/storage/emulated/0/DCIM/PixelNAS",
    val quarantineDir: String = "/storage/emulated/0/PixelNAS/Quarantine",
    val folderProfiles: List<FolderProfileRule> = defaultFolderProfiles(),
    val remoteNasUrl: String = "",
    val widgetStatusLine: String = "",
    val authEnabled: Boolean = false,
    val authUsername: String = "pixel",
    val authPassword: String = ""
) {
    fun matchFolderProfile(incomingRoot: File, sourceFile: File): FolderProfileRule? {
        return try {
            val rel = sourceFile.relativeTo(incomingRoot).invariantSeparatorsPath
            val parts = rel.split("/").filter { it.isNotBlank() }
            if (parts.size < 2) return null
            val topFolder = parts.first()
            folderProfiles.firstOrNull { it.folderName.equals(topFolder, ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
    }

    fun buildPairingUri(lanIp: String, activeSmbPort: Int = smbPort): String {
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val share = smbShareName.ifBlank { "PixelNAS" }
        val auth = if (authEnabled) "credentials" else "guest"
        val user = authUsername.ifBlank { "pixel" }
        return "pixelnas://pair?host=${enc(lanIp.trim())}&port=$activeSmbPort&share=${enc(share)}&auth=$auth&user=${enc(user)}&workgroup=WORKGROUP"
    }

    companion object {
        fun isWithinQuietHours(nowHour: Int, startHour: Int, endHour: Int): Boolean {
            val s = ((startHour % 24) + 24) % 24
            val e = ((endHour % 24) + 24) % 24
            val h = ((nowHour % 24) + 24) % 24
            if (s == e) return true
            return if (s < e) h in s until e else (h >= s || h < e)
        }
    }
}

data class MemberAnalyticsItem(
    val member: String,
    val fileCount: Int,
    val origBytes: Long,
    val outBytes: Long,
    val savedBytes: Long,
    val savedPct: Double
)

data class QuarantineItem(
    val quarantineName: String,
    val originalRelPath: String,
    val sizeBytes: Long = 0L,
    val attempts: Int = 2,
    val errorReason: String = "",
    val quarantinedAtEpochSec: Double = System.currentTimeMillis() / 1000.0
)

data class ConversionRecord(
    val id: Long = 0,
    val originalFilename: String,
    val outputFilename: String,
    val mediaType: String,
    val mode: String,
    val profileName: String = "default",
    val origWidth: Int = 0,
    val origHeight: Int = 0,
    val origMp: Double = 0.0,
    val origBytes: Long,
    val outWidth: Int = 0,
    val outHeight: Int = 0,
    val outMp: Double = 0.0,
    val outBytes: Long,
    val savedBytes: Long,
    val savedPct: Double,
    val exifPreserved: Boolean = true,
    val cameraModel: String = "",
    val dateTaken: String = "",
    val gpsCoords: String = "",
    val metadataNote: String = "",
    val originalDeleted: Boolean = true,
    val durationMs: Long = 0,
    val status: String = "completed",
    val errorMessage: String = "",
    val createdAtEpochSec: Double = System.currentTimeMillis() / 1000.0
)

data class HistorySummary(
    val totalProcessed: Int = 0,
    val saverCount: Int = 0,
    val duplicatesSkippedCount: Int = 0,
    val passthroughCount: Int = 0,
    val quarantinedCount: Int = 0,
    val totalOrigBytes: Long = 0,
    val totalOutBytes: Long = 0,
    val totalSavedBytes: Long = 0,
    val originalsDeletedCount: Int = 0,
    val avgSavedPct: Double = 0.0
)

data class WorkerRuntimeState(
    val serviceRunning: Boolean = false,
    val queueSize: Int = 0,
    val activeFile: String? = null,
    val batteryTempCelsius: Float = 28.0f,
    val batteryLevelPct: Int = 100,
    val acPluggedIn: Boolean = true,
    val batterySwellWarning: Boolean = false,
    val thermalPaused: Boolean = false,
    val autoPurgedCount: Int = 0,
    val deferredVideoCount: Int = 0,
    val quarantinedTotal: Int = 0,
    val lastError: String = ""
)
