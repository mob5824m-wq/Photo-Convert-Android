package com.photoconvert.android.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photoconvert.android.model.AspectRatioOption
import com.photoconvert.android.model.ByteFormatter
import com.photoconvert.android.model.ConvertSettings
import com.photoconvert.android.model.ConvertedItem
import com.photoconvert.android.model.FrameFillMode
import com.photoconvert.android.model.OutputFormat
import com.photoconvert.android.model.ResizePreset
import com.photoconvert.android.model.WatermarkAnchor
import com.photoconvert.android.ui.components.ChoicePill
import com.photoconvert.android.ui.components.ConvertedItemRow
import com.photoconvert.android.ui.components.SectionCard
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConvertScreen(
    queuedUris: List<Uri>,
    settings: ConvertSettings,
    isConverting: Boolean,
    loupeLoading: Boolean,
    progressDone: Int,
    progressTotal: Int,
    latestBatch: List<ConvertedItem>,
    onPickPhotos: () -> Unit,
    onInspectLoupe: (Uri) -> Unit,
    onRemoveQueued: (Uri) -> Unit,
    onClearQueue: () -> Unit,
    onUpdateSettings: ((ConvertSettings) -> ConvertSettings) -> Unit,
    onRunConvert: () -> Unit
) {
    var studioExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionCard(
                title = "Photos",
                subtitle = "JPG, PNG, WebP, HEIC/AVIF & DNG RAW supported",
                trailing = if (queuedUris.isNotEmpty()) {
                    { TextButton(onClick = onClearQueue) { Text("Clear") } }
                } else null
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                        .clickable(enabled = !isConverting, onClick = onPickPhotos)
                        .padding(vertical = 26.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.AddPhotoAlternate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Tap to choose photos",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Tap the compare icon on any queued item for Split Loupe",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (loupeLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (queuedUris.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        queuedUris.take(8).forEach { uri ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = uri.lastPathSegment ?: uri.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { onInspectLoupe(uri) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Compare,
                                        contentDescription = "Before/After Loupe",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveQueued(uri) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        if (queuedUris.size > 8) {
                            Text(
                                text = "+${queuedUris.size - 8} more photo(s) queued",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Output Settings",
                subtitle = settings.activePresetName
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "FORMAT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutputFormat.entries.forEach { fmt ->
                            ChoicePill(
                                label = fmt.label,
                                selected = settings.effectiveFormat() == fmt,
                                onClick = { onUpdateSettings { it.copy(format = fmt) } }
                            )
                        }
                    }
                }

                // Compression Mode: Quality % vs Exact Target KB Ceiling (#2)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChoicePill(
                                label = "Quality %",
                                selected = settings.targetSizeKb <= 0,
                                onClick = { onUpdateSettings { it.copy(targetSizeKb = 0) } }
                            )
                            ChoicePill(
                                label = "Fit Target KB",
                                selected = settings.targetSizeKb > 0,
                                onClick = {
                                    onUpdateSettings {
                                        it.copy(targetSizeKb = if (it.targetSizeKb > 0) it.targetSizeKb else 500)
                                    }
                                }
                            )
                        }
                        val rightLabel = when {
                            settings.targetSizeKb > 0 -> "≤ ${settings.targetSizeKb} KB"
                            settings.effectiveFormat().isLossless -> "Lossless"
                            else -> "${settings.quality}%"
                        }
                        Text(
                            text = rightLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (settings.targetSizeKb <= 0) {
                        Slider(
                            value = settings.quality.toFloat(),
                            onValueChange = { v -> onUpdateSettings { it.copy(quality = v.roundToInt()) } },
                            valueRange = 10f..100f,
                            enabled = !settings.effectiveFormat().isLossless
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(200 to "200 KB", 500 to "500 KB", 1024 to "1 MB", 2048 to "2 MB").forEach { (kb, lbl) ->
                                ChoicePill(
                                    label = lbl,
                                    selected = settings.targetSizeKb == kb,
                                    onClick = { onUpdateSettings { it.copy(targetSizeKb = kb) } }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = settings.targetSizeKb.toString(),
                            onValueChange = { raw ->
                                val parsed = raw.filter { it.isDigit() }.toIntOrNull() ?: 500
                                onUpdateSettings { it.copy(targetSizeKb = parsed.coerceIn(20, 51200)) }
                            },
                            label = { Text("Exact ceiling (KB)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "DIMENSIONS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ResizePreset.entries.forEach { preset ->
                            ChoicePill(
                                label = preset.label,
                                selected = settings.resizePreset == preset,
                                onClick = { onUpdateSettings { it.copy(resizePreset = preset) } }
                            )
                        }
                    }

                    if (settings.resizePreset == ResizePreset.CUSTOM_EDGE) {
                        OutlinedTextField(
                            value = settings.customMaxEdgePx.toString(),
                            onValueChange = { raw ->
                                val parsed = raw.filter { it.isDigit() }.toIntOrNull() ?: 2048
                                onUpdateSettings { it.copy(customMaxEdgePx = parsed) }
                            },
                            label = { Text("Max long edge (px)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                SettingSwitchTile(
                    title = "Strip EXIF & GPS metadata",
                    subtitle = "Removes location coordinates and camera serials",
                    checked = settings.stripExif,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(stripExif = c) } }
                )

                if (!settings.stripExif) {
                    SettingSwitchTile(
                        title = "Repair missing capture dates",
                        subtitle = "Reconstructs EXIF dates from IMG_YYYYMMDD / WhatsApp names",
                        checked = settings.repairFilenameDates,
                        onCheckedChange = { c -> onUpdateSettings { it.copy(repairFilenameDates = c) } }
                    )
                }

                SettingSwitchTile(
                    title = "Normalize Display P3 → sRGB",
                    subtitle = "Converts wide-gamut colors to standard sRGB",
                    checked = settings.normalizeSrgb,
                    onCheckedChange = { c -> onUpdateSettings { it.copy(normalizeSrgb = c) } }
                )

                // Progressive Disclosure: Studio Enhancements (#6, #7, #8, #10)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { studioExpanded = !studioExpanded }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Studio Enhancements",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val activeCount = settings.activeEnhancementCount()
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (activeCount > 0) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            ) {
                                Text(
                                    text = if (activeCount == 0) "Off" else "$activeCount active",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Icon(
                                imageVector = if (studioExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = null
                            )
                        }
                    }

                    if (studioExpanded) {
                        Column(
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SettingSwitchTile(
                                title = "Offline Subject Cutout",
                                subtitle = "ML Kit segmentation to transparent WebP/PNG",
                                checked = settings.removeBackground,
                                onCheckedChange = { c -> onUpdateSettings { it.copy(removeBackground = c) } }
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "ASPECT FRAMING",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    AspectRatioOption.entries.forEach { opt ->
                                        ChoicePill(
                                            label = opt.label,
                                            selected = settings.aspectRatio == opt,
                                            onClick = { onUpdateSettings { it.copy(aspectRatio = opt) } }
                                        )
                                    }
                                }
                                if (settings.aspectRatio != AspectRatioOption.ORIGINAL) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FrameFillMode.entries.forEach { fmode ->
                                            ChoicePill(
                                                label = fmode.label,
                                                selected = settings.frameFillMode == fmode,
                                                onClick = { onUpdateSettings { it.copy(frameFillMode = fmode) } }
                                            )
                                        }
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = settings.watermarkText,
                                    onValueChange = { v -> onUpdateSettings { it.copy(watermarkText = v.take(80)) } },
                                    label = { Text("Watermark text (optional, e.g. © 2026)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (settings.watermarkText.isNotBlank()) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        WatermarkAnchor.entries.forEach { anchor ->
                                            ChoicePill(
                                                label = anchor.label,
                                                selected = settings.watermarkAnchor == anchor,
                                                onClick = { onUpdateSettings { it.copy(watermarkAnchor = anchor) } }
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Watermark opacity", style = MaterialTheme.typography.labelSmall)
                                        Text("${settings.watermarkOpacity}%", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Slider(
                                        value = settings.watermarkOpacity.toFloat(),
                                        onValueChange = { v ->
                                            onUpdateSettings { it.copy(watermarkOpacity = v.roundToInt()) }
                                        },
                                        valueRange = 15f..100f
                                    )
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = settings.renamePattern,
                                    onValueChange = { v -> onUpdateSettings { it.copy(renamePattern = v) } },
                                    label = { Text("Batch rename pattern") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("{orig}", "{date}", "{width}x{height}", "{seq}", "{format}").forEach { tok ->
                                        ChoicePill(
                                            label = tok,
                                            selected = false,
                                            onClick = {
                                                onUpdateSettings {
                                                    val base = it.renamePattern.trim()
                                                    it.copy(renamePattern = if (base.isEmpty()) tok else "${base}_$tok")
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (isConverting) {
                    val progress = if (progressTotal > 0) {
                        progressDone.toFloat() / progressTotal.toFloat()
                    } else 0f
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Converting $progressDone of $progressTotal...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = onRunConvert,
                    enabled = queuedUris.isNotEmpty() && !isConverting,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    val count = queuedUris.size
                    val fmtLabel = settings.effectiveFormat().label
                    val text = if (count == 0) {
                        "Select Photos to Convert"
                    } else {
                        "Convert $count Photo${if (count == 1) "" else "s"} to $fmtLabel"
                    }
                    Text(text, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (latestBatch.isNotEmpty()) {
            val savedTotal = latestBatch.sumOf { max(0L, it.savedBytes) }
            item {
                Text(
                    text = if (savedTotal > 0L) {
                        "Latest Batch · Saved ${ByteFormatter.format(savedTotal)}"
                    } else {
                        "Latest Batch (${latestBatch.size})"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(latestBatch, key = { it.id }) { item ->
                ConvertedItemRow(item = item)
            }
        }
    }
}

@Composable
private fun SettingSwitchTile(
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
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
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
