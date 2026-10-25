package com.photoconvert.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BeforeAfterLoupeDialog(
    preview: LoupePreviewResult,
    hasCustomRedactions: Boolean,
    onAddRedactBox: (String) -> Unit,
    onClearRedactBoxes: () -> Unit,
    onDismiss: () -> Unit
) {
    var splitFraction by remember { mutableFloatStateOf(0.5f) }
    var zoomLevel by remember { mutableIntStateOf(1) }
    var drawRedactMode by remember { mutableStateOf(false) }
    var stageWidthPx by remember { mutableFloatStateOf(1f) }
    var stageHeightPx by remember { mutableFloatStateOf(1f) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
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

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(1, 2, 3, 4).forEach { z ->
                        ChoicePill(
                            label = "${z}×",
                            selected = zoomLevel == z && !drawRedactMode,
                            onClick = {
                                drawRedactMode = false
                                zoomLevel = z
                            }
                        )
                    }
                    ChoicePill(
                        label = if (drawRedactMode) "Drawing Redaction" else "+ Redact Box",
                        selected = drawRedactMode,
                        onClick = {
                            drawRedactMode = !drawRedactMode
                            if (drawRedactMode) zoomLevel = 1
                        }
                    )
                    if (hasCustomRedactions) {
                        ChoicePill(
                            label = "Clear Boxes",
                            selected = false,
                            onClick = onClearRedactBoxes
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .onSizeChanged {
                            stageWidthPx = it.width.toFloat().coerceAtLeast(1f)
                            stageHeightPx = it.height.toFloat().coerceAtLeast(1f)
                        }
                        .pointerInput(drawRedactMode, stageWidthPx, stageHeightPx) {
                            if (drawRedactMode) {
                                detectDragGestures(
                                    onDragStart = { pos ->
                                        dragStart = pos
                                        dragCurrent = pos
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        dragCurrent = change.position
                                    },
                                    onDragEnd = {
                                        val s = dragStart
                                        val c = dragCurrent
                                        dragStart = null
                                        dragCurrent = null
                                        if (s != null && c != null) {
                                            val bmpW = preview.beforeBitmap.width.toFloat().coerceAtLeast(1f)
                                            val bmpH = preview.beforeBitmap.height.toFloat().coerceAtLeast(1f)
                                            val fitScale = min(stageWidthPx / bmpW, stageHeightPx / bmpH)
                                            val drawnW = bmpW * fitScale
                                            val drawnH = bmpH * fitScale
                                            val leftPad = (stageWidthPx - drawnW) / 2f
                                            val topPad = (stageHeightPx - drawnH) / 2f

                                            val nx0 = ((min(s.x, c.x) - leftPad) / drawnW).coerceIn(0f, 1f)
                                            val ny0 = ((min(s.y, c.y) - topPad) / drawnH).coerceIn(0f, 1f)
                                            val nx1 = ((max(s.x, c.x) - leftPad) / drawnW).coerceIn(0f, 1f)
                                            val ny1 = ((max(s.y, c.y) - topPad) / drawnH).coerceIn(0f, 1f)
                                            val nw = nx1 - nx0
                                            val nh = ny1 - ny0
                                            if (nw >= 0.02f && nh >= 0.02f) {
                                                val spec = String.format(
                                                    Locale.US,
                                                    "%.4f,%.4f,%.4f,%.4f",
                                                    nx0, ny0, nw, nh
                                                )
                                                onAddRedactBox(spec)
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        dragStart = null
                                        dragCurrent = null
                                    }
                                )
                            } else {
                                detectHorizontalDragGestures { change, _ ->
                                    change.consume()
                                    splitFraction = (change.position.x / stageWidthPx).coerceIn(0f, 1f)
                                }
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

                    val s = dragStart
                    val c = dragCurrent
                    if (s != null && c != null) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val left = min(s.x, c.x)
                            val top = min(s.y, c.y)
                            val w = abs(c.x - s.x)
                            val h = abs(c.y - s.y)
                            drawRect(
                                color = Color(0x44EF4444),
                                topLeft = Offset(left, top),
                                size = Size(w, h)
                            )
                            drawRect(
                                color = Color(0xFFEF4444),
                                topLeft = Offset(left, top),
                                size = Size(w, h),
                                style = Stroke(width = 3f)
                            )
                        }
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
                            text = if (drawRedactMode) "Drag over area to redact" else "Original",
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
                        val redactTag = if (preview.redactedRegions > 0) " · ${preview.redactedRegions} redacted" else ""
                        Text(
                            text = "${preview.outputFormatLabel} (${preview.usedQuality}%)$redactTag",
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
