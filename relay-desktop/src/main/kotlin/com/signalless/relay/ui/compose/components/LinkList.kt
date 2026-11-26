package com.signalless.relay.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalless.relay.ui.RelayLinkView
import com.signalless.relay.ui.compose.RelayColors
import com.signalless.relay.ui.compose.RelayFormat

@Composable
fun LinkList(
    links: List<RelayLinkView>,
    nowMs: Long,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (links.isEmpty()) {
        EmptyLinks(modifier, compact)
        return
    }

    LazyColumn(modifier = modifier) {
        itemsIndexed(items = links, key = { _, link -> link.id }) { index, link ->
            LinkRow(link = link, nowMs = nowMs, compact = compact)
            if (index < links.lastIndex) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(RelayColors.Border),
                )
            }
        }
    }
}

@Composable
private fun LinkRow(link: RelayLinkView, nowMs: Long, compact: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) 6.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = link.id,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = RelayColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 36.dp),
        )
        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = link.peerId?.let(RelayFormat::peerId) ?: "waiting for first packet",
                    fontFamily = if (link.peerId != null) FontFamily.Monospace else FontFamily.SansSerif,
                    fontSize = 12.sp,
                    color = if (link.peerId != null) RelayColors.Text else RelayColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                LinkBadge(bound = link.peerId != null)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "MTU ${link.mtu}   notify ${link.notificationSize}",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = RelayColors.Muted,
            )
        }

        Spacer(Modifier.width(10.dp))
        Text(
            text = "up ${RelayFormat.duration(nowMs - link.firstSeenMs)}",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = RelayColors.Muted,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 52.dp),
        )
    }
}

@Composable
private fun LinkBadge(bound: Boolean) {
    val color = if (bound) RelayColors.Accent else RelayColors.Muted
    Text(
        text = if (bound) "bound" else "unbound",
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun EmptyLinks(modifier: Modifier, compact: Boolean) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .drawBehind {
                    drawRoundRect(
                        color = RelayColors.Border,
                        cornerRadius = CornerRadius(12.dp.toPx()),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(6.dp.toPx(), 5.dp.toPx()),
                            ),
                        ),
                    )
                }
                .padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "No phones yet — start the relay and stay nearby.",
                style = MaterialTheme.typography.bodySmall,
                color = RelayColors.Muted,
            )
        }
    }
}
