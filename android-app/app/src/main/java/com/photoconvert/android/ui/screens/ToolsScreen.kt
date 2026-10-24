package com.photoconvert.android.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photoconvert.android.model.PdfMargin
import com.photoconvert.android.model.PdfPageSize
import com.photoconvert.android.model.WatchedFolderState
import com.photoconvert.android.ui.components.ChoicePill
import com.photoconvert.android.ui.components.SectionCard
import com.photoconvert.android.ui.components.shareFile
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsScreen(
    isBuildingPdf: Boolean,
    latestPdfFile: File?,
    watchedFolder: WatchedFolderState,
    onBuildPdf: (List<Uri>, PdfPageSize, PdfMargin, Boolean) -> Unit,
    onSelectWatchedFolder: (Uri) -> Unit,
    onToggleWatchedFolder: (Boolean) -> Unit,
    onScanWatchedNow: () -> Unit
) {
    val context = LocalContext.current
    var pdfUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pageSize by remember { mutableStateOf(PdfPageSize.A4) }
    var margin by remember { mutableStateOf(PdfMargin.SMALL) }
    var scanFilter by remember { mutableStateOf(false) }

    val pdfPhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris ->
        if (uris.isNotEmpty()) pdfUris = uris
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) onSelectWatchedFolder(treeUri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // #3 Photo-to-PDF & Scan Sheet Builder
        item {
            SectionCard(
                title = "Photo-to-PDF & Scan Sheet",
                subtitle = "Pack multiple photos into a compact multi-page PDF"
            ) {
                OutlinedButton(
                    onClick = {
                        pdfPhotoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val label = if (pdfUris.isEmpty()) {
                        "Select Photos for PDF Pages"
                    } else {
                        "${pdfUris.size} Photo${if (pdfUris.size == 1) "" else "s"} Selected (Tap to Change)"
                    }
                    Text(label)
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "PAGE SIZE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PdfPageSize.entries.forEach { ps ->
                            ChoicePill(
                                label = ps.label,
                                selected = pageSize == ps,
                                onClick = { pageSize = ps }
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "MARGINS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PdfMargin.entries.forEach { m ->
                            ChoicePill(
                                label = m.label,
                                selected = margin == m,
                                onClick = { margin = m }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Document Scan High-Contrast",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Crisp grayscale boost for receipts, notes & documents",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = scanFilter, onCheckedChange = { scanFilter = it })
                }

                if (isBuildingPdf) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onBuildPdf(pdfUris, pageSize, margin, scanFilter) },
                        enabled = pdfUris.isNotEmpty() && !isBuildingPdf,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("Export PDF", fontWeight = FontWeight.SemiBold)
                    }

                    if (latestPdfFile != null && latestPdfFile.exists()) {
                        OutlinedButton(
                            onClick = {
                                shareFile(context, latestPdfFile, "application/pdf", latestPdfFile.name)
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(46.dp)
                        ) {
                            Text("Share PDF")
                        }
                    }
                }
            }
        }

        // #4 Background Watched-Folder Auto-Converter
        item {
            SectionCard(
                title = "Watched-Folder Auto-Converter",
                subtitle = "Background WorkManager auto-converts new images in your chosen folder"
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "WATCHED FOLDER",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = watchedFolder.displayPath,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Auto-converted so far: ${watchedFolder.processedCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Enable Background Auto-Watch",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Applies your active Output Settings & Enhancements",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = watchedFolder.enabled,
                        onCheckedChange = onToggleWatchedFolder
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { folderPicker.launch(null) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Choose Folder")
                    }
                    Button(
                        onClick = onScanWatchedNow,
                        enabled = watchedFolder.treeUriString.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Scan Now")
                    }
                }
            }
        }
    }
}
