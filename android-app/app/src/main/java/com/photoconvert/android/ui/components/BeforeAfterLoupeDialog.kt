package com.photoconvert.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.photoconvert.android.engine.LoupePreviewResult
import com.photoconvert.android.model.ByteFormatter

@Composable
fun BeforeAfterLoupeDialog(
    preview: LoupePreviewResult,
    onDismiss: () -> Unit
) {
    var splitFraction by remember { mutableFloatStateOf(0.5f) }
    var zoomLevel by remember { mutableIntStateOf(1) }
    var stageWidthPx by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 20.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Before / After Loupe",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = preview.filename,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3, 4).forEach { z ->
                        ChoicePill(
                            label = "${z}×",
                            selected = zoomLevel == z,
                            onClick = { zoomLevel = z }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .onSizeChanged { stageWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                splitFraction = (change.position.x / stageWidthPx).coerceIn(0f, 1f)
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = zoomLevel.toFloat(),
                                scaleY = zoomLevel.toFloat()
                            )
                    ) {
                        Image(
                            bitmap = preview.afterBitmap.asImageBitmap(),
                            contentDescription = "Converted preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        Image(
                            bitmap = preview.beforeBitmap.asImageBitmap(),
                            contentDescription = "Original preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .drawWithContent {
                                    clipRect(right = size.width * splitFraction) {
                                        this@drawWithContent.drawContent()
                                    }
                                }
                        )
                    }

                    val dividerOffsetDp = with(density) { (stageWidthPx * splitFraction).toDp() }
                    Box(
                        modifier = Modifier
                            .offset(x = dividerOffsetDp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(Color.White)
                    )

                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = Color(0xBB0F172A)
                    ) {
                        Text(
                            text = "Original",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = Color(0xBB0F172A)
                    ) {
                        Text(
                            text = "${preview.outputFormatLabel} (${preview.usedQuality}%)",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Slider(
                    value = splitFraction,
                    onValueChange = { splitFraction = it }
                )

                val deltaText = if (preview.savedBytes > 0L) {
                    "Saved ${ByteFormatter.format(preview.savedBytes)} (-${preview.savedPct}%)"
                } else {
                    "Output ${ByteFormatter.format(preview.outBytes)}"
                }
                Text(
                    text = "${preview.origWidth}×${preview.origHeight} (${ByteFormatter.format(preview.origBytes)}) → ${preview.outWidth}×${preview.outHeight} (${ByteFormatter.format(preview.outBytes)}) · $deltaText",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
