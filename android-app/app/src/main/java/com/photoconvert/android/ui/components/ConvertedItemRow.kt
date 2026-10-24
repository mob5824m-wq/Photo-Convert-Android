package com.photoconvert.android.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.photoconvert.android.model.ByteFormatter
import com.photoconvert.android.model.ConvertedItem
import java.io.File

@Composable
fun ConvertedItemRow(
    item: ConvertedItem,
    onDelete: (() -> Unit)? = null
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = item.outputName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val tags = buildList {
                if (item.cutoutApplied) add("Cutout")
                if (item.repairedDate) add("Date repaired")
                else if (item.exifKept) add("EXIF kept")
                else add("No EXIF")
            }.joinToString(" · ")

            Text(
                text = "${item.originalFormat} → ${item.outputFormat} · ${item.origWidth}×${item.origHeight} → ${item.outWidth}×${item.outHeight}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${ByteFormatter.format(item.origBytes)} → ${ByteFormatter.format(item.outBytes)} · $tags",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.savedBytes > 0L) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "-${item.savedPct}%",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            IconButton(onClick = { shareFile(context, File(item.localFilePath), item.mimeType, item.outputName) }) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "Share converted photo"
                )
            }

            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete from history",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

fun shareFile(context: Context, file: File, mimeType: String, displayName: String) {
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, "Share $displayName"))
}
