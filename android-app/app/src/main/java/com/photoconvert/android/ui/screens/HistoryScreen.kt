package com.photoconvert.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.photoconvert.android.model.ByteFormatter
import com.photoconvert.android.model.ConvertedItem
import com.photoconvert.android.model.StatsSummary
import com.photoconvert.android.ui.components.ConvertedItemRow
import com.photoconvert.android.ui.components.StatTile

@Composable
fun HistoryScreen(
    history: List<ConvertedItem>,
    summary: StatsSummary,
    onDeleteItem: (String) -> Unit,
    onClearAll: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(history, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            history
        } else {
            history.filter {
                it.outputName.lowercase().contains(q) ||
                    it.originalName.lowercase().contains(q) ||
                    it.outputFormat.lowercase().contains(q)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatTile(
                    label = "Converted",
                    value = summary.totalConverted.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "Space Saved",
                    value = ByteFormatter.format(summary.totalSavedBytes),
                    highlight = true,
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "Avg Reduction",
                    value = "${summary.avgSavedPct}%",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Filter by filename or format...") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
                if (history.isNotEmpty()) {
                    TextButton(onClick = onClearAll) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 42.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (history.isEmpty()) {
                            "No converted photos yet."
                        } else {
                            "No matches for \"$query\"."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(filtered, key = { it.id }) { item ->
                ConvertedItemRow(
                    item = item,
                    onDelete = { onDeleteItem(item.id) }
                )
            }
        }
    }
}
