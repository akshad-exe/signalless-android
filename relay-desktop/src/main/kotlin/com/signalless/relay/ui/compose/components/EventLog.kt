package com.signalless.relay.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalless.relay.ui.RelayEvent
import com.signalless.relay.ui.RelayEventKind
import com.signalless.relay.ui.compose.RelayColors
import com.signalless.relay.ui.compose.RelayFormat

@Composable
fun EventLog(
    events: List<RelayEvent>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (events.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            Text(
                text = "No events yet.",
                fontSize = 12.sp,
                color = RelayColors.Muted,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        return
    }

    val listState = rememberLazyListState()
    var stickToBottom by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (scrolling) {
                    stickToBottom = !canScrollForward
                }
            }
    }

    LaunchedEffect(events.size) {
        if (stickToBottom) {
            listState.scrollToItem(events.lastIndex)
        }
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp),
    ) {
        itemsIndexed(events) { _, event ->
            EventRow(event)
        }
    }
}

@Composable
private fun EventRow(event: RelayEvent) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = RelayFormat.clock(event.atMs),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = RelayColors.Muted,
            maxLines = 1,
            modifier = Modifier.width(62.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = event.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = eventColor(event.kind),
        )
    }
}

private fun eventColor(kind: RelayEventKind) = when (kind) {
    RelayEventKind.ERROR -> RelayColors.Error
    RelayEventKind.LINK_DOWN -> RelayColors.Warning
    RelayEventKind.READY, RelayEventKind.LINK_UP -> RelayColors.Text
    RelayEventKind.LIFECYCLE -> RelayColors.Muted
}
