package com.photoconvert.android.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoconvert.android.data.ConvertRepository
import com.photoconvert.android.engine.AnimationExporter
import com.photoconvert.android.engine.BatchZipExporter
import com.photoconvert.android.engine.BurstDeduplicator
import com.photoconvert.android.engine.CollageBuilder
import com.photoconvert.android.engine.LoupePreviewResult
import com.photoconvert.android.engine.PdfExporter
import com.photoconvert.android.engine.PhotoConverterEngine
import com.photoconvert.android.engine.WebPackExporter
import com.photoconvert.android.model.CollageLayout
import com.photoconvert.android.model.ConvertSettings
import com.photoconvert.android.model.ConvertedItem
import com.photoconvert.android.model.ExifInspectionReport
import com.photoconvert.android.model.PdfMargin
import com.photoconvert.android.model.PdfPageSize
import com.photoconvert.android.model.PresetProfile
import com.photoconvert.android.model.StatsSummary
import com.photoconvert.android.model.WatchedFolderState
import com.photoconvert.android.model.WebPackBundleResult
import com.photoconvert.android.worker.WatchedFolderWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ConvertUiState(
    val selectedTab: Int = 0,
    val darkThemeOverride: Boolean? = null,
    val queuedUris: List<Uri> = emptyList(),
    val lastBatchSourceUris: List<Uri> = emptyList(),
    val settings: ConvertSettings = ConvertSettings(),
    val isConverting: Boolean = false,
    val isDeduplicating: Boolean = false,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val loupeLoading: Boolean = false,
    val loupePreview: LoupePreviewResult? = null,
    val exifReport: ExifInspectionReport? = null,
    val isBuildingPdf: Boolean = false,
    val latestPdfFile: File? = null,
    val isBuildingCollage: Boolean = false,
    val latestCollageFile: File? = null,
    val isBuildingAnimation: Boolean = false,
    val latestAnimationFile: File? = null,
    val isBuildingWebPack: Boolean = false,
    val latestWebPack: WebPackBundleResult? = null,
    val latestBatchZipFile: File? = null,
    val watchedFolder: WatchedFolderState = WatchedFolderState(),
    val latestBatch: List<ConvertedItem> = emptyList(),
    val presets: List<PresetProfile> = emptyList(),
    val history: List<ConvertedItem> = emptyList(),
    val summary: StatsSummary = StatsSummary(),
    val statusMessage: String? = null
)

class ConvertViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ConvertRepository(application)
    private val engine = PhotoConverterEngine(application)
    private val pdfExporter = PdfExporter(application)
    private val collageBuilder = CollageBuilder(application)
    private val animationExporter = AnimationExporter(application)
    private val zipExporter = BatchZipExporter(application)
    private val webPackExporter = WebPackExporter(application)

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<ConvertUiState> = _uiState.asStateFlow()

    private fun loadInitialState(): ConvertUiState {
        val history = repo.loadHistory()
        return ConvertUiState(
            darkThemeOverride = repo.loadDarkThemeOverride(),
            settings = repo.loadSettings(),
            watchedFolder = repo.loadWatchedFolderState(),
            presets = repo.loadPresets(),
            history = history,
            summary = repo.computeSummary(history)
        )
    }

    fun handleShortcutAction(actionCode: String?) {
        if (actionCode.isNullOrBlank()) return
        when {
            actionCode == "tab:tools" -> selectTab(1)
            actionCode.startsWith("preset:") -> {
                val id = actionCode.removePrefix("preset:")
                val found = _uiState.value.presets.firstOrNull { it.id == id }
                if (found != null) applyPreset(found)
            }
        }
    }

    fun selectTab(index: Int) {
        val history = repo.loadHistory()
        _uiState.update {
            it.copy(
                selectedTab = index,
                watchedFolder = repo.loadWatchedFolderState(),
                history = history,
                summary = repo.computeSummary(history)
            )
        }
    }

    fun toggleDarkTheme(currentlyDark: Boolean) {
        val next = !currentlyDark
        repo.saveDarkThemeOverride(next)
        _uiState.update { it.copy(darkThemeOverride = next) }
    }

    fun enqueueUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _uiState.update { state ->
            val merged = (state.queuedUris + uris).distinct()
            state.copy(queuedUris = merged, selectedTab = 0)
        }
    }

    fun removeQueuedUri(uri: Uri) {
        _uiState.update { it.copy(queuedUris = it.queuedUris - uri) }
    }

    fun clearQueue() {
        _uiState.update { it.copy(queuedUris = emptyList()) }
    }

    fun dedupeQueuedBursts() {
        val queue = _uiState.value.queuedUris
        if (queue.size < 2 || _uiState.value.isDeduplicating) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(isDeduplicating = true) }
            runCatching { BurstDeduplicator.dedupeQueue(app, queue) }
                .onSuccess { outcome ->
                    val msg = if (outcome.droppedCount == 0) {
                        "No near-duplicate burst photos found!"
                    } else {
                        "Removed ${outcome.droppedCount} duplicate/blurry burst shot(s)"
                    }
                    _uiState.update {
                        it.copy(
                            isDeduplicating = false,
                            queuedUris = outcome.keptUris,
                            statusMessage = msg
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(isDeduplicating = false, statusMessage = err.message ?: "De-dupe failed")
                    }
                }
        }
    }

    fun updateSettings(transform: (ConvertSettings) -> ConvertSettings) {
        _uiState.update { state ->
            val updated = transform(state.settings).copy(
                presetSlug = "custom",
                activePresetName = "Custom configuration"
            )
            repo.saveSettings(updated)
            state.copy(settings = updated)
        }
    }

    fun selectWatermarkLogo(uri: Uri?) {
        val app = getApplication<Application>()
        if (uri != null) {
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        updateSettings { it.copy(watermarkLogoUriString = uri?.toString().orEmpty()) }
    }

    fun applyPreset(preset: PresetProfile) {
        val nextSettings = preset.toSettings()
        repo.saveSettings(nextSettings)
        _uiState.update {
            it.copy(
                settings = nextSettings,
                selectedTab = 0,
                statusMessage = "Applied \"${preset.name}\""
            )
        }
    }

    fun saveCurrentAsPreset(name: String, description: String) {
        val updatedPresets = repo.addCustomPreset(name, description, _uiState.value.settings)
        _uiState.update {
            it.copy(
                presets = updatedPresets,
                statusMessage = "Saved custom preset"
            )
        }
    }

    fun deleteCustomPreset(presetId: String) {
        val updatedPresets = repo.deleteCustomPreset(presetId)
        _uiState.update { it.copy(presets = updatedPresets) }
    }

    fun inspectUriExif(uri: Uri) {
        viewModelScope.launch {
            runCatching { engine.inspectUri(uri) }
                .onSuccess { report ->
                    _uiState.update { it.copy(exifReport = report) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(statusMessage = err.message ?: "Could not inspect EXIF") }
                }
        }
    }

    fun closeExifInspector() {
        _uiState.update { it.copy(exifReport = null) }
    }

    fun openLoupePreview(uri: Uri) {
        if (_uiState.value.loupeLoading) return
        val settings = _uiState.value.settings
        viewModelScope.launch {
            _uiState.update { it.copy(loupeLoading = true) }
            runCatching { engine.buildLoupePreview(uri, settings) }
                .onSuccess { preview ->
                    _uiState.update { it.copy(loupeLoading = false, loupePreview = preview) }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            loupeLoading = false,
                            statusMessage = err.message ?: "Preview failed"
                        )
                    }
                }
        }
    }

    fun addCustomLoupeRedactBox(normBoxSpec: String) {
        val preview = _uiState.value.loupePreview ?: return
        val existing = _uiState.value.settings.redactBoxesSpec
        val merged = if (existing.isBlank()) normBoxSpec else "$existing;$normBoxSpec"
        updateSettings { it.copy(redactBoxesSpec = merged) }
        openLoupePreview(preview.sourceUri)
    }

    fun clearCustomLoupeRedactBoxes() {
        val preview = _uiState.value.loupePreview
        updateSettings { it.copy(redactBoxesSpec = "") }
        if (preview != null) openLoupePreview(preview.sourceUri)
    }

    fun closeLoupePreview() {
        _uiState.update { it.copy(loupePreview = null) }
    }

    fun convertQueuedPhotos() {
        val queue = _uiState.value.queuedUris
        if (queue.isEmpty() || _uiState.value.isConverting) return
        val settings = _uiState.value.settings

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isConverting = true,
                    progressDone = 0,
                    progressTotal = queue.size,
                    latestBatchZipFile = null
                )
            }

            val converted = mutableListOf<ConvertedItem>()
            val succeededSourceUris = mutableListOf<Uri>()
            var failures = 0

            for ((idx, uri) in queue.withIndex()) {
                runCatching { engine.convertUri(uri, settings, sequenceIndex = idx + 1) }
                    .onSuccess {
                        converted.add(it)
                        succeededSourceUris.add(uri)
                    }
                    .onFailure { failures++ }
                _uiState.update { it.copy(progressDone = idx + 1) }
            }

            val updatedHistory = if (converted.isNotEmpty()) {
                repo.appendHistory(converted.reversed())
            } else {
                _uiState.value.history
            }

            val msg = when {
                converted.isNotEmpty() && failures == 0 ->
                    "Saved ${converted.size} photo${if (converted.size == 1) "" else "s"} to Pictures/PhotoConvert"
                converted.isNotEmpty() ->
                    "Converted ${converted.size} photo(s), $failures skipped"
                else -> "Could not convert selected file(s)"
            }

            _uiState.update {
                it.copy(
                    isConverting = false,
                    queuedUris = emptyList(),
                    lastBatchSourceUris = succeededSourceUris,
                    latestBatch = converted,
                    history = updatedHistory,
                    summary = repo.computeSummary(updatedHistory),
                    statusMessage = msg
                )
            }
        }
    }

    fun clearLastBatchSourceUris(trashed: Boolean) {
        _uiState.update {
            it.copy(
                lastBatchSourceUris = emptyList(),
                statusMessage = if (trashed) "Moved original photo(s) to system Trash" else null
            )
        }
    }

    fun exportItemsAsZip(items: List<ConvertedItem>, onReady: (File) -> Unit) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            runCatching { zipExporter.exportItemsToZip(items) }
                .onSuccess { zip ->
                    _uiState.update {
                        it.copy(
                            latestBatchZipFile = zip,
                            statusMessage = "Saved ${zip.name} to Downloads/PhotoConvert"
                        )
                    }
                    onReady(zip)
                }
                .onFailure { err ->
                    _uiState.update { it.copy(statusMessage = err.message ?: "ZIP export failed") }
                }
        }
    }

    fun buildPdfDocument(
        uris: List<Uri>,
        pageSize: PdfPageSize,
        margin: PdfMargin,
        scanFilter: Boolean,
        autoDeskew: Boolean
    ) {
        if (uris.isEmpty() || _uiState.value.isBuildingPdf) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBuildingPdf = true) }
            runCatching {
                pdfExporter.exportPhotosToPdf(
                    uris = uris,
                    pageSize = pageSize,
                    margin = margin,
                    documentScanFilter = scanFilter,
                    autoDeskew = autoDeskew
                )
            }.onSuccess { pdfFile ->
                _uiState.update {
                    it.copy(
                        isBuildingPdf = false,
                        latestPdfFile = pdfFile,
                        statusMessage = "Saved PDF (${uris.size} page${if (uris.size == 1) "" else "s"}) to Downloads/PhotoConvert"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isBuildingPdf = false, statusMessage = err.message ?: "Could not create PDF")
                }
            }
        }
    }

    fun buildCollageSheet(uris: List<Uri>, layout: CollageLayout, showCaptions: Boolean) {
        if (uris.isEmpty() || _uiState.value.isBuildingCollage) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBuildingCollage = true) }
            runCatching { collageBuilder.buildCollageSheet(uris, layout, showCaptions) }
                .onSuccess { file ->
                    _uiState.update {
                        it.copy(
                            isBuildingCollage = false,
                            latestCollageFile = file,
                            statusMessage = "Saved ${layout.label} to Pictures/PhotoConvert"
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(isBuildingCollage = false, statusMessage = err.message ?: "Collage failed")
                    }
                }
        }
    }

    fun buildBurstAnimation(uris: List<Uri>, delayMs: Int, boomerang: Boolean) {
        if (uris.size < 2 || _uiState.value.isBuildingAnimation) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBuildingAnimation = true) }
            runCatching { animationExporter.exportBurstGif(uris, delayMs, boomerang) }
                .onSuccess { gif ->
                    _uiState.update {
                        it.copy(
                            isBuildingAnimation = false,
                            latestAnimationFile = gif,
                            statusMessage = "Saved Animated GIF to Pictures/PhotoConvert"
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(isBuildingAnimation = false, statusMessage = err.message ?: "Animation failed")
                    }
                }
        }
    }

    fun buildResponsiveWebPack(uri: Uri) {
        if (_uiState.value.isBuildingWebPack) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBuildingWebPack = true) }
            runCatching { webPackExporter.buildResponsiveWebPack(uri) }
                .onSuccess { bundle ->
                    _uiState.update {
                        it.copy(
                            isBuildingWebPack = false,
                            latestWebPack = bundle,
                            statusMessage = "Saved ${bundle.zipFile.name} to Downloads/PhotoConvert"
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(isBuildingWebPack = false, statusMessage = err.message ?: "Web Pack failed")
                    }
                }
        }
    }

    fun selectWatchedFolderUri(treeUri: Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val current = _uiState.value.watchedFolder
        val display = treeUri.lastPathSegment ?: treeUri.toString()
        val next = current.copy(treeUriString = treeUri.toString(), displayPath = display)
        repo.saveWatchedFolderState(next)
        if (next.enabled) {
            WatchedFolderWorker.syncSchedule(app, true)
        }
        _uiState.update {
            it.copy(
                watchedFolder = next,
                statusMessage = "Selected watched folder: $display"
            )
        }
    }

    fun toggleWatchedFolder(enabled: Boolean) {
        val app = getApplication<Application>()
        val current = _uiState.value.watchedFolder
        if (enabled && current.treeUriString.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Choose a folder first to enable Auto-Watch") }
            return
        }
        val next = current.copy(enabled = enabled)
        repo.saveWatchedFolderState(next)
        WatchedFolderWorker.syncSchedule(app, enabled)
        _uiState.update {
            it.copy(
                watchedFolder = next,
                statusMessage = if (enabled) "Background folder Auto-Watch active" else "Folder Auto-Watch paused"
            )
        }
    }

    fun scanWatchedFolderNow() {
        val app = getApplication<Application>()
        if (_uiState.value.watchedFolder.treeUriString.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Choose a folder first") }
            return
        }
        WatchedFolderWorker.triggerScanNow(app)
        _uiState.update { it.copy(statusMessage = "Scanning watched folder in background...") }
    }

    fun deleteHistoryItem(id: String) {
        val updated = repo.removeHistoryItem(id)
        _uiState.update {
            it.copy(
                history = updated,
                latestBatch = it.latestBatch.filterNot { b -> b.id == id },
                summary = repo.computeSummary(updated)
            )
        }
    }

    fun clearHistory() {
        val updated = repo.clearHistory()
        _uiState.update {
            it.copy(
                history = updated,
                latestBatch = emptyList(),
                summary = repo.computeSummary(updated),
                statusMessage = "Cleared conversion history"
            )
        }
    }

    fun consumeStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}
