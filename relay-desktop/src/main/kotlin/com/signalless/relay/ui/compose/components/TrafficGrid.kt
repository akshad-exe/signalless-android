package com.signalless.relay.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalless.relay.RelayStats
import com.signalless.relay.ui.compose.RelayColors
import com.signalless.relay.ui.compose.RelayFormat

private data class StatCell(val label: String, val value: Long, val isError: Boolean = false)

@Composable
fun TrafficGrid(stats: RelayStats, modifier: Modifier = Modifier, compact: Boolean = false) {
    val errors = stats.transportErrors + stats.sendFailed + stats.encodeFailed
    val cells = listOf(
        StatCell("Received", stats.received),
        StatCell("Forwarded", stats.forwarded),
        StatCell("Frames sent", stats.framesSent),
        StatCell("Stored", stats.stored),
        StatCell("Flushed", stats.storeFlushed),
        StatCell("Duplicates", stats.duplicates),
        StatCell("TTL expired", stats.ttlExpired),
        StatCell("Errors", errors, isError = errors > 0L),
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        cells.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { cell ->
                    StatCellView(cell, Modifier.weight(1f), compact)
                }
                repeat(4 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RowScope.StatCellView(cell: StatCell, modifier: Modifier, compact: Boolean) {
    Column(modifier = modifier) {
        Text(
            text = RelayFormat.count(cell.value),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (compact) 16.sp else 18.sp,
            color = if (cell.isError) RelayColors.Error else RelayColors.Text,
            maxLines = 1,
        )
        Text(
            text = cell.label,
            fontSize = if (compact) 9.sp else 10.sp,
            color = RelayColors.Muted,
            maxLines = 1,
        )
    }
}
