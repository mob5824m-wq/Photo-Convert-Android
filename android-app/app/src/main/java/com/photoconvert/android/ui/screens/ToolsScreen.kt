package com.photoconvert.android.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photoconvert.android.model.CollageLayout
import com.photoconvert.android.model.PdfMargin
import com.photoconvert.android.model.PdfPageSize
import com.photoconvert.android.model.WatchedFolderState
import com.photoconvert.android.model.WebPackBundleResult
import com.photoconvert.android.ui.components.ChoicePill
import com.photoconvert.android.ui.components.SectionCard
import com.photoconvert.android.ui.components.shareFile
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsScreen(
    isBuildingPdf: Boolean,
    latestPdfFile: File?,
    isBuildingCollage: Boolean,
    latestCollageFile: File?,
    isBuildingAnimation: Boolean,
    latestAnimationFile: File?,
    isBuildingWebPack: Boolean,
    latestWebPack: WebPackBundleResult?,
    watchedFolder: WatchedFolderState,
    onBuildPdf: (List<Uri>, PdfPageSize, PdfMargin, Boolean, Boolean) -> Unit,
    onBuildCollage: (List<Uri>, CollageLayout, Boolean) -> Unit,
    onBuildAnimation: (List<Uri>, Int, Boolean) -> Unit,
    onBuildWebPack: (Uri) -> Unit,
    onSelectWatchedFolder: (Uri) -> Unit,
    onToggleWatchedFolder: (Boolean) -> Unit,
    onScanWatchedNow: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var pdfUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pageSize by remember { mutableStateOf(PdfPageSize.A4) }
    var margin by remember { mutableStateOf(PdfMargin.SMALL) }
    var scanFilter by remember { mutableStateOf(false) }
    var pdfDeskew by remember { mutableStateOf(true) }

    var collageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var collageLayout by remember { mutableStateOf(CollageLayout.GRID_2X2) }
    var collageCaptions by remember { mutableStateOf(false) }

    var animUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var animDelayMs by remember { mutableIntStateOf(250) }
    var animBoomerang by remember { mutableStateOf(false) }

    var webPackUri by remember { mutableStateOf<Uri?>(null) }

    val pdfPhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris -> if (uris.isNotEmpty()) pdfUris = uris }

    val collagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(12)
    ) { uris -> if (uris.isNotEmpty()) collageUris = uris }

    val animPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris -> if (uris.isNotEmpty()) animUris = uris }

    val webPackPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) webPackUri = uri }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri -> if (treeUri != null) onSelectWatchedFolder(treeUri) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Photo-to-PDF & Scan Sheet Builder (#3 + #4 Deskew)
        item {
            SectionCard(
                title = "Photo-to-PDF & Scan Sheet",
                subtitle = "Pack multiple photos or deskewed scans into a compact PDF"
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

                ToolToggleRow(
                    title = "Auto-Crop & 4-Corner Deskew",
                    subtitle = "Rectifies skewed pages and trims dark borders",
                    checked = pdfDeskew,
                    onCheckedChange = { pdfDeskew = it }
                )

                ToolToggleRow(
                    title = "Document Scan High-Contrast",
                    subtitle = "Crisp grayscale boost for receipts, notes & documents",
                    checked = scanFilter,
                    onCheckedChange = { scanFilter = it }
                )

                if (isBuildingPdf) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onBuildPdf(pdfUris, pageSize, margin, scanFilter, pdfDeskew) },
                        enabled = pdfUris.isNotEmpty() && !isBuildingPdf,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Text("Export PDF", fontWeight = FontWeight.SemiBold)
                    }

                    if (latestPdfFile != null && latestPdfFile.exists()) {
                        OutlinedButton(
                            onClick = { shareFile(context, latestPdfFile, "application/pdf", latestPdfFile.name) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(46.dp)
                        ) {
                            Text("Share PDF")
                        }
                    }
                }
            }
        }

        // 2. Contact Sheet, Collage & 2-Up ID Card (#3 Batch 2)
        item {
            SectionCard(
                title = "Collage & 2-Up ID Card Sheet",
                subtitle = "Compose grids or front + back ID cards centered on A4"
            ) {
                OutlinedButton(
                    onClick = {
                        collagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val label = if (collageUris.isEmpty()) {
                        "Select Photos for Collage / ID Sheet"
                    } else {
                        "${collageUris.size} Photo(s) Selected (Tap to Change)"
                    }
                    Text(label)
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CollageLayout.entries.forEach { ly ->
                        ChoicePill(
                            label = ly.label,
                            selected = collageLayout == ly,
                            onClick = { collageLayout = ly }
                        )
                    }
                }

                ToolToggleRow(
                    title = "Show Filename Captions",
                    subtitle = "Prints subtle labels under each photo cell",
                    checked = collageCaptions,
                    onCheckedChange = { collageCaptions = it }
                )

                if (isBuildingCollage) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onBuildCollage(collageUris, collageLayout, collageCaptions) },
                        enabled = collageUris.isNotEmpty() && !isBuildingCollage,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Text("Export Sheet", fontWeight = FontWeight.SemiBold)
                    }
                    if (latestCollageFile != null && latestCollageFile.exists()) {
                        OutlinedButton(
                            onClick = {
                                shareFile(context, latestCollageFile, "image/jpeg", latestCollageFile.name)
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(46.dp)
                        ) {
                            Text("Share")
                        }
                    }
                }
            }
        }

        // 3. Burst-to-Animated GIF Maker (#5 Batch 2)
        item {
            SectionCard(
                title = "Burst-to-Animation Maker",
                subtitle = "Turn 2+ burst frames into a looping animated GIF"
            ) {
                OutlinedButton(
                    onClick = {
                        animPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val label = if (animUris.isEmpty()) {
                        "Select 2+ Burst Photos"
                    } else {
                        "${animUris.size} Frame(s) Selected (Tap to Change)"
                    }
                    Text(label)
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(120 to "Fast (120ms)", 250 to "Normal (250ms)", 450 to "Slow (450ms)").forEach { (ms, lbl) ->
                        ChoicePill(
                            label = lbl,
                            selected = animDelayMs == ms,
                            onClick = { animDelayMs = ms }
                        )
                    }
                }

                ToolToggleRow(
                    title = "Ping-Pong Boomerang",
                    subtitle = "Plays forward then backward for smooth loops",
                    checked = animBoomerang,
                    onCheckedChange = { animBoomerang = it }
                )

                if (isBuildingAnimation) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onBuildAnimation(animUris, animDelayMs, animBoomerang) },
                        enabled = animUris.size >= 2 && !isBuildingAnimation,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Text("Create GIF", fontWeight = FontWeight.SemiBold)
                    }
                    if (latestAnimationFile != null && latestAnimationFile.exists()) {
                        OutlinedButton(
                            onClick = {
                                shareFile(context, latestAnimationFile, "image/gif", latestAnimationFile.name)
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(46.dp)
                        ) {
                            Text("Share GIF")
                        }
                    }
                }
            }
        }

        // 4. Responsive <picture> Web Pack + LQIP & Palette (#10 Batch 2)
        item {
            SectionCard(
                title = "Responsive <picture> Web Pack",
                subtitle = "Exports 480w/960w/1920w WebP+JPG, blur-up LQIP & color palette .zip"
            ) {
                OutlinedButton(
                    onClick = {
                        webPackPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val label = if (webPackUri == null) {
                        "Select Hero Image"
                    } else {
                        "Hero Image Selected (Tap to Change)"
                    }
                    Text(label)
                }

                if (isBuildingWebPack) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { webPackUri?.let(onBuildWebPack) },
                        enabled = webPackUri != null && !isBuildingWebPack,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Text("Build Web Pack .zip", fontWeight = FontWeight.SemiBold)
                    }
                    if (latestWebPack != null && latestWebPack.zipFile.exists()) {
                        OutlinedButton(
                            onClick = {
                                shareFile(
                                    context,
                                    latestWebPack.zipFile,
                                    "application/zip",
                                    latestWebPack.zipFile.name
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(46.dp)
                        ) {
                            Text("Share .zip")
                        }
                    }
                }

                if (latestWebPack != null) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        latestWebPack.paletteHex.forEach { hex ->
                            val c = runCatching {
                                Color(android.graphics.Color.parseColor(hex))
                            }.getOrDefault(Color.Gray)
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { clipboard.setText(AnnotatedString(hex)) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(c)
                                        .border(0.5.dp, Color.Gray, RoundedCornerShape(999.dp))
                                )
                                Text(
                                    text = hex,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(latestWebPack.htmlSnippet)) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Copy <picture> + LQIP HTML Snippet")
                    }
                }
            }
        }

        // 5. Background Watched-Folder Auto-Converter
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

                ToolToggleRow(
                    title = "Enable Background Auto-Watch",
                    subtitle = "Applies your active Output Settings & Enhancements",
                    checked = watchedFolder.enabled,
                    onCheckedChange = onToggleWatchedFolder
                )

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

@Composable
private fun ToolToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
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
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
