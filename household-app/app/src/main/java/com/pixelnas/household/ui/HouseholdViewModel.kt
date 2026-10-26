package com.pixelnas.household.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelnas.household.HouseholdApp
import com.pixelnas.household.discovery.NsdSmbDiscovery
import com.pixelnas.household.model.DiscoveredSmbHost
import com.pixelnas.household.model.SmbConnectionConfig
import com.pixelnas.household.model.SmbRemoteEntry
import com.pixelnas.household.model.SmbShareSpace
import com.pixelnas.household.model.TransferBatchState
import com.pixelnas.household.model.UploadLedgerEntry
import com.pixelnas.household.sync.CameraRollSmbWorker
import com.pixelnas.household.sync.SmbUploadService
import com.pixelnas.household.widget.HouseholdUploadWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class BrowserUiState(
    val currentPath: String = "",
    val entries: List<SmbRemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String = ""
)

data class ConnectionCheckState(
    val checking: Boolean = false,
    val connected: Boolean = false,
    val space: SmbShareSpace? = null,
    val message: String = "Not tested yet — scan Wi-Fi, scan Pairing QR, or enter NAS IP"
)

data class RemotePreviewState(
    val entry: SmbRemoteEntry? = null,
    val loading: Boolean = false,
    val bitmap: Bitmap? = null,
    val error: String = ""
)

class HouseholdViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HouseholdApp

    val configFlow: StateFlow<SmbConnectionConfig> = app.preferences.configFlow
    val recentUploadsFlow: StateFlow<List<UploadLedgerEntry>> = app.preferences.recentUploadsFlow
    val transferFlow: StateFlow<TransferBatchState> = app.transferState.asStateFlow()

    private val _discoveredHosts = MutableStateFlow<List<DiscoveredSmbHost>>(emptyList())
    val discoveredHosts: StateFlow<List<DiscoveredSmbHost>> = _discoveredHosts.asStateFlow()

    private val _scanningLan = MutableStateFlow(false)
    val scanningLan: StateFlow<Boolean> = _scanningLan.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionCheckState())
    val connectionState: StateFlow<ConnectionCheckState> = _connectionState.asStateFlow()

    private val _browserState = MutableStateFlow(BrowserUiState())
    val browserState: StateFlow<BrowserUiState> = _browserState.asStateFlow()

    private val _previewState = MutableStateFlow(RemotePreviewState())
    val previewState: StateFlow<RemotePreviewState> = _previewState.asStateFlow()

    init {
        if (app.preferences.current.host.isNotBlank()) {
            testSambaConnection()
        }
    }

    fun updateConfig(transform: (SmbConnectionConfig) -> SmbConnectionConfig) {
        val prev = app.preferences.current
        val updated = app.preferences.update(transform)
        if (prev.autoCameraSyncWifiOnly != updated.autoCameraSyncWifiOnly ||
            prev.syncChargingOnly != updated.syncChargingOnly
        ) {
            app.syncAutoBackupSchedule(updated.autoCameraSyncWifiOnly, updated.syncChargingOnly)
        }
        HouseholdUploadWidgetProvider.refreshAllWidgets(app)
    }

    fun applyPairingUri(rawUri: String): Boolean {
        val parsed = SmbConnectionConfig.parsePairingUri(rawUri, app.preferences.current) ?: return false
        updateConfig { parsed }
        testSambaConnection()
        return true
    }

    fun findSyncedCameraRollUris(onReady: (List<Uri>) -> Unit) {
        viewModelScope.launch {
            val uris = withContext(Dispatchers.IO) {
                CameraRollSmbWorker.findAlreadySyncedCameraRollUris(
                    app,
                    app.preferences,
                    app.preferences.current.syncVideosToo
                )
            }
            onReady(uris)
        }
    }

    fun scanWifiForNas(onComplete: ((Int) -> Unit)? = null) {
        if (_scanningLan.value) return
        _scanningLan.value = true
        viewModelScope.launch {
            val hosts = runCatching { NsdSmbDiscovery.scanLanForSambaShares(app) }
                .getOrDefault(emptyList())
            _discoveredHosts.value = hosts
            _scanningLan.value = false
            if (hosts.size == 1 && app.preferences.current.host.isBlank()) {
                selectDiscoveredHost(hosts.first())
            }
            onComplete?.invoke(hosts.size)
        }
    }

    fun selectDiscoveredHost(host: DiscoveredSmbHost) {
        updateConfig {
            it.copy(
                host = host.host,
                port = host.port,
                shareName = host.suggestedShare
            )
        }
        testSambaConnection()
    }

    fun testSambaConnection() {
        val cfg = app.preferences.current
        if (cfg.host.isBlank()) {
            _connectionState.value = ConnectionCheckState(
                checking = false,
                connected = false,
                message = "Enter your NAS IP address or tap Scan Wi-Fi first."
            )
            return
        }
        _connectionState.value = _connectionState.value.copy(
            checking = true,
            message = "Connecting to \\\\${cfg.host}\\${cfg.cleanShareName} over SMB2/3..."
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val space = app.smbClient.testConnectionAndInspect(cfg)
                if (space.activePort != cfg.port) {
                    app.preferences.update { it.copy(port = space.activePort) }
                    HouseholdUploadWidgetProvider.refreshAllWidgets(app)
                }
                _connectionState.value = ConnectionCheckState(
                    checking = false,
                    connected = true,
                    space = space,
                    message = "Connected via Samba SMB2/3 (:${space.activePort}) · Shares: ${space.topFolders.joinToString(", ").ifBlank { "Ready" }}"
                )
                refreshBrowser(_browserState.value.currentPath)
            } catch (e: Throwable) {
                _connectionState.value = ConnectionCheckState(
                    checking = false,
                    connected = false,
                    space = null,
                    message = "Samba error: ${e.message ?: "Connection failed"}"
                )
            }
        }
    }

    fun uploadPickedMedia(uris: List<Uri>) {
        SmbUploadService.enqueueBatch(app, uris)
    }

    fun refreshBrowser(relativePath: String = _browserState.value.currentPath) {
        val cfg = app.preferences.current
        if (cfg.host.isBlank()) {
            _browserState.value = BrowserUiState(
                currentPath = "",
                entries = emptyList(),
                loading = false,
                error = "Configure NAS Samba IP first in Samba Setup."
            )
            return
        }
        _browserState.value = _browserState.value.copy(
            currentPath = relativePath,
            loading = true,
            error = ""
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = app.smbClient.browseFolder(cfg, relativePath)
                _browserState.value = BrowserUiState(
                    currentPath = relativePath,
                    entries = list,
                    loading = false,
                    error = ""
                )
            } catch (e: Throwable) {
                _browserState.value = BrowserUiState(
                    currentPath = relativePath,
                    entries = emptyList(),
                    loading = false,
                    error = e.message ?: "Unable to list Samba directory"
                )
            }
        }
    }

    fun navigateUpInBrowser() {
        val curr = _browserState.value.currentPath.trim('\\', '/')
        if (curr.isEmpty()) return
        val parent = curr.substringBeforeLast('\\', "")
        refreshBrowser(parent)
    }

    fun createRemoteFolder(folderName: String) {
        val clean = SmbConnectionConfig.sanitizeSegment(folderName)
        if (clean.isEmpty()) return
        val curr = _browserState.value.currentPath
        val full = if (curr.isEmpty()) clean else "$curr\\$clean"
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.smbClient.createFolder(app.preferences.current, full) }
            refreshBrowser(curr)
        }
    }

    fun deleteRemoteEntry(entry: SmbRemoteEntry) {
        val curr = _browserState.value.currentPath
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                app.smbClient.deleteEntry(app.preferences.current, entry.relativePath, entry.isDirectory)
            }
            refreshBrowser(curr)
        }
    }

    fun clearUploadHistory() {
        app.preferences.clearRecentUploads()
    }

    fun openRemotePreview(entry: SmbRemoteEntry) {
        if (!entry.isPreviewableImage) return
        _previewState.value = RemotePreviewState(entry = entry, loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = app.smbClient.readFileBytes(app.preferences.current, entry.relativePath)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
                    sample *= 2
                }
                val bmp = BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
                if (bmp != null) {
                    _previewState.value = RemotePreviewState(entry = entry, loading = false, bitmap = bmp)
                } else {
                    _previewState.value = RemotePreviewState(
                        entry = entry,
                        loading = false,
                        error = "Could not decode image preview"
                    )
                }
            } catch (e: Throwable) {
                _previewState.value = RemotePreviewState(
                    entry = entry,
                    loading = false,
                    error = e.message ?: "Failed reading Samba file"
                )
            }
        }
    }

    fun closeRemotePreview() {
        _previewState.value = RemotePreviewState()
    }

    fun saveRemoteFileToDevice(entry: SmbRemoteEntry, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    val bytes = app.smbClient.readFileBytes(app.preferences.current, entry.relativePath)
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                        ?: if (ext in setOf("mp4", "mov", "mkv", "webm")) "video/mp4" else "image/jpeg"
                    val isVideo = mime.startsWith("video/")
                    val isImage = mime.startsWith("image/")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val collection = when {
                            isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        }
                        val relFolder = when {
                            isImage -> "${Environment.DIRECTORY_PICTURES}/PixelNAS"
                            isVideo -> "${Environment.DIRECTORY_MOVIES}/PixelNAS"
                            else -> "${Environment.DIRECTORY_DOWNLOADS}/PixelNAS"
                        }
                        val cv = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
                            put(MediaStore.MediaColumns.MIME_TYPE, mime)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, relFolder)
                        }
                        val uri = app.contentResolver.insert(collection, cv)
                            ?: error("Could not create MediaStore entry")
                        app.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(bytes)
                        } ?: error("Could not open MediaStore stream")
                        true to "Saved ${entry.name} to $relFolder"
                    } else {
                        @Suppress("DEPRECATION")
                        val pubDir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            "PixelNAS"
                        ).apply { mkdirs() }
                        val dest = File(pubDir, entry.name)
                        dest.writeBytes(bytes)
                        MediaScannerConnection.scanFile(app, arrayOf(dest.absolutePath), arrayOf(mime), null)
                        true to "Saved ${entry.name} to Downloads/PixelNAS"
                    }
                } catch (e: Throwable) {
                    false to (e.message ?: "Save failed")
                }
            }
            onResult(res.first, res.second)
        }
    }

    fun shareRemoteFile(entry: SmbRemoteEntry, onReady: (Uri?, String, String?) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val bytes = app.smbClient.readFileBytes(app.preferences.current, entry.relativePath)
                    val shareDir = File(app.cacheDir, "smb_shared").apply { mkdirs() }
                    val localFile = File(shareDir, entry.name.replace(Regex("[^A-Za-z0-9._ -]"), "_"))
                    localFile.writeBytes(bytes)
                    val ext = localFile.extension.lowercase()
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                    val uri = FileProvider.getUriForFile(
                        app,
                        "${app.packageName}.fileprovider",
                        localFile
                    )
                    Triple(uri, mime, null as String?)
                } catch (e: Throwable) {
                    Triple(null as Uri?, "*/*", e.message ?: "Share failed")
                }
            }
            onReady(result.first, result.second, result.third)
        }
    }
}
