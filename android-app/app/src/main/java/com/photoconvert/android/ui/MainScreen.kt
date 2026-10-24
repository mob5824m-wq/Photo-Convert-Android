package com.photoconvert.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photoconvert.android.model.ByteFormatter
import com.photoconvert.android.ui.components.BeforeAfterLoupeDialog
import com.photoconvert.android.ui.screens.ConvertScreen
import com.photoconvert.android.ui.screens.HistoryScreen
import com.photoconvert.android.ui.screens.PresetsScreen
import com.photoconvert.android.ui.screens.ToolsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ConvertViewModel) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = state.darkThemeOverride ?: systemDark

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris ->
        viewModel.enqueueUris(uris)
    }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeStatusMessage()
    }

    state.loupePreview?.let { preview ->
        BeforeAfterLoupeDialog(
            preview = preview,
            onDismiss = viewModel::closeLoupePreview
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
                    label = { Text("PDF & Watch") }
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
                    settings = state.settings,
                    isConverting = state.isConverting,
                    loupeLoading = state.loupeLoading,
                    progressDone = state.progressDone,
                    progressTotal = state.progressTotal,
                    latestBatch = state.latestBatch,
                    onPickPhotos = {
                        mediaPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onInspectLoupe = viewModel::openLoupePreview,
                    onRemoveQueued = viewModel::removeQueuedUri,
                    onClearQueue = viewModel::clearQueue,
                    onUpdateSettings = viewModel::updateSettings,
                    onRunConvert = viewModel::convertQueuedPhotos
                )

                1 -> ToolsScreen(
                    isBuildingPdf = state.isBuildingPdf,
                    latestPdfFile = state.latestPdfFile,
                    watchedFolder = state.watchedFolder,
                    onBuildPdf = viewModel::buildPdfDocument,
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
                    onDeleteItem = viewModel::deleteHistoryItem,
                    onClearAll = viewModel::clearHistory
                )
            }
        }
    }
}
