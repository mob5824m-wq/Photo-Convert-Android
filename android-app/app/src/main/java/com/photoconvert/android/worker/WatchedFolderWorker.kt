package com.photoconvert.android.worker

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.photoconvert.android.data.ConvertRepository
import com.photoconvert.android.engine.PhotoConverterEngine
import com.photoconvert.android.model.ConvertedItem
import java.util.concurrent.TimeUnit

class WatchedFolderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = ConvertRepository(applicationContext)
        val watchState = repo.loadWatchedFolderState()
        if (watchState.treeUriString.isBlank()) return Result.success()

        val treeUri = runCatching { Uri.parse(watchState.treeUriString) }.getOrNull()
            ?: return Result.success()
        val rootDir = DocumentFile.fromTreeUri(applicationContext, treeUri)
            ?: return Result.success()

        val seenKeys = repo.loadProcessedWatchKeys().toMutableSet()
        val engine = PhotoConverterEngine(applicationContext)
        val settings = repo.loadSettings()
        val newlyConverted = mutableListOf<ConvertedItem>()

        val imageFiles = rootDir.listFiles().filter { doc ->
            doc.isFile && (doc.type?.startsWith("image/") == true || hasSupportedExtension(doc.name))
        }

        imageFiles.forEachIndexed { idx, doc ->
            val key = "${doc.uri}|${doc.lastModified()}|${doc.length()}"
            if (key in seenKeys) return@forEachIndexed
            runCatching {
                engine.convertUri(doc.uri, settings, sequenceIndex = idx + 1)
            }.onSuccess { item ->
                newlyConverted.add(item)
                seenKeys.add(key)
            }
        }

        if (newlyConverted.isNotEmpty()) {
            repo.appendHistory(newlyConverted.reversed())
            repo.saveProcessedWatchKeys(seenKeys)
            repo.incrementWatchedProcessedCount(newlyConverted.size)
        }

        return Result.success()
    }

    private fun hasSupportedExtension(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase()
        return SUPPORTED_EXTS.any { lower.endsWith(it) }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "photoconvert_watched_folder_periodic"
        private const val IMMEDIATE_WORK_NAME = "photoconvert_watched_folder_now"
        private val SUPPORTED_EXTS = listOf(
            ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".avif", ".dng"
        )

        fun syncSchedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (enabled) {
                val periodic = PeriodicWorkRequestBuilder<WatchedFolderWorker>(15, TimeUnit.MINUTES)
                    .build()
                wm.enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodic
                )
                triggerScanNow(context)
            } else {
                wm.cancelUniqueWork(PERIODIC_WORK_NAME)
            }
        }

        fun triggerScanNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<WatchedFolderWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
