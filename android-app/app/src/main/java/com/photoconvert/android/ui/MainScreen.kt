package com.photoconvert.android.ui

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photoconvert.android.model.ByteFormatter
import com.photoconvert.android.model.ExifPrivacyMode
import com.photoconvert.android.ui.components.BeforeAfterLoupeDialog
import com.photoconvert.android.ui.components.ExifInspectorDialog
import com.photoconvert.android.ui.components.shareFile
import com.photoconvert.android.ui.screens.ConvertScreen
import com.photoconvert.android.ui.screens.HistoryScreen
import com.photoconvert.android.ui.screens.PresetsScreen
import com.photoconvert.android.ui.screens.ToolsScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ConvertViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = state.darkThemeOverride ?: systemDark

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris ->
        viewModel.enqueueUris(uris)
    }

    val trashLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.clearLastBatchSourceUris(trashed = result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeStatusMessage()
    }

    state.loupePreview?.let { preview ->
        BeforeAfterLoupeDialog(
            preview = preview,
            hasCustomRedactions = state.settings.redactBoxesSpec.isNotBlank(),
            onAddRedactBox = viewModel::addCustomLoupeRedactBox,
            onClearRedactBoxes = viewModel::clearCustomLoupeRedactBoxes,
            onDismiss = viewModel::closeLoupePreview
        )
    }

    state.exifReport?.let { report ->
        ExifInspectorDialog(
            report = report,
            onApplyScrubGpsOnly = {
                viewModel.updateSettings { it.copy(exifPrivacyMode = ExifPrivacyMode.SCRUB_GPS_ONLY) }
            },
            onDismiss = viewModel::closeExifInspector
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Photo Convert",
                            fontWeight = FontWeight.Bold
                        )
                        if (state.summary.totalSavedBytes > 0L) {
                            Spacer(Modifier.width(10.dp))
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "${ByteFormatter.format(state.summary.totalSavedBytes)} saved",
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleDarkTheme(effectiveDark) }) {
                        Icon(
                            imageVector = if (effectiveDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            contentDescription = "Toggle theme"
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.selectedTab == 0,
                    onClick = { viewModel.selectTab(0) },
                    icon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                    label = { Text("Convert") }
                )
                NavigationBarItem(
                    selected = state.selectedTab == 1,
                    onClick = { viewModel.selectTab(1) },
                    icon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
                    label = { Text("Tools") }
                )
                NavigationBarItem(
                    selected = state.selectedTab == 2,
                    onClick = { viewModel.selectTab(2) },
                    icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                    label = { Text("Presets") }
                )
                NavigationBarItem(
                    selected = state.selectedTab == 3,
                    onClick = { viewModel.selectTab(3) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (state.summary.totalConverted > 0) {
                                    Badge { Text(state.summary.totalConverted.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Outlined.History, contentDescription = null)
                        }
                    },
                    label = { Text("History") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (state.selectedTab) {
                0 -> ConvertScreen(
                    queuedUris = state.queuedUris,
                    canTrashOriginals = state.lastBatchSourceUris.isNotEmpty(),
                    settings = state.settings,
                    isConverting = state.isConverting,
                    isDeduplicating = state.isDeduplicating,
                    loupeLoading = state.loupeLoading,
                    progressDone = state.progressDone,
                    progressTotal = state.progressTotal,
                    latestBatch = state.latestBatch,
                    onPickPhotos = {
                        mediaPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onDedupeBursts = viewModel::dedupeQueuedBursts,
                    onInspectExif = viewModel::inspectUriExif,
                    onInspectLoupe = viewModel::openLoupePreview,
                    onRemoveQueued = viewModel::removeQueuedUri,
                    onClearQueue = viewModel::clearQueue,
                    onSelectLogoUri = viewModel::selectWatermarkLogo,
                    onUpdateSettings = viewModel::updateSettings,
                    onRunConvert = viewModel::convertQueuedPhotos,
                    onShareBatchZip = {
                        viewModel.exportItemsAsZip(state.latestBatch) { zip ->
                            shareFile(context, zip, "application/zip", zip.name)
                        }
                    },
                    onTrashOriginals = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && state.lastBatchSourceUris.isNotEmpty()) {
                            runCatching {
                                val pending = MediaStore.createTrashRequest(
                                    context.contentResolver,
                                    state.lastBatchSourceUris,
                                    true
                                )
                                trashLauncher.launch(
                                    IntentSenderRequest.Builder(pending.intentSender).build()
                                )
                            }.onFailure {
                                viewModel.clearLastBatchSourceUris(trashed = false)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Originals came from read-only Photo Picker; share from Gallery to trash MediaStore originals."
                                    )
                                }
                            }
                        }
                    }
                )

                1 -> ToolsScreen(
                    isBuildingPdf = state.isBuildingPdf,
                    latestPdfFile = state.latestPdfFile,
                    isBuildingCollage = state.isBuildingCollage,
                    latestCollageFile = state.latestCollageFile,
                    isBuildingAnimation = state.isBuildingAnimation,
                    latestAnimationFile = state.latestAnimationFile,
                    isBuildingWebPack = state.isBuildingWebPack,
                    latestWebPack = state.latestWebPack,
                    watchedFolder = state.watchedFolder,
                    onBuildPdf = viewModel::buildPdfDocument,
                    onBuildCollage = viewModel::buildCollageSheet,
                    onBuildAnimation = viewModel::buildBurstAnimation,
                    onBuildWebPack = viewModel::buildResponsiveWebPack,
                    onSelectWatchedFolder = viewModel::selectWatchedFolderUri,
                    onToggleWatchedFolder = viewModel::toggleWatchedFolder,
                    onScanWatchedNow = viewModel::scanWatchedFolderNow
                )

                2 -> PresetsScreen(
                    presets = state.presets,
                    onApplyPreset = viewModel::applyPreset,
                    onCreatePreset = viewModel::saveCurrentAsPreset,
                    onDeletePreset = viewModel::deleteCustomPreset
                )

                else -> HistoryScreen(
                    history = state.history,
                    summary = state.summary,
                    onExportFilteredZip = { items ->
                        viewModel.exportItemsAsZip(items) { zip ->
                            shareFile(context, zip, "application/zip", zip.name)
                        }
                    },
                    onDeleteItem = viewModel::deleteHistoryItem,
                    onClearAll = viewModel::clearHistory
                )
            }
        }
    }
}
