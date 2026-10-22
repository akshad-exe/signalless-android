package com.signalless.relay.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalless.relay.ui.RelayPhase
import com.signalless.relay.ui.RelayUiState
import com.signalless.relay.ui.compose.RelayColors
import com.signalless.relay.ui.compose.RelayFormat

@Composable
fun StatusHeader(
    state: RelayUiState,
    demo: Boolean,
    onCopyId: (() -> Unit)?,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(state.phase)
            Spacer(Modifier.width(14.dp))
            Text(
                text = state.nickname,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = if (compact) 18.sp else 20.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (demo) {
                Spacer(Modifier.width(8.dp))
                DemoBadge()
            }
        }

        Spacer(Modifier.size(if (compact) 6.dp else 8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer {
                Text(
                    text = RelayFormat.peerId(state.peerId),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = RelayColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onCopyId != null) {
                Spacer(Modifier.width(10.dp))
                HeaderAction(label = "Copy ID", onClick = onCopyId)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "${RelayFormat.transportLabel(state.transport)} · ${state.version}",
                fontSize = 11.sp,
                color = RelayColors.Muted,
            )
        }

        Spacer(Modifier.size(if (compact) 4.dp else 6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            val uptime = state.advertisingSinceMs?.let { since ->
                "up ${RelayFormat.duration(state.nowMs - since, alwaysHours = true)}"
            } ?: "up --:--:--"
            Text(
                text = uptime,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = RelayColors.Muted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${state.links.size} / ${state.maxLinks} links",
                fontSize = 11.sp,
                color = RelayColors.Muted,
            )
        }
    }
}

@Composable
fun StatusPill(phase: RelayPhase, modifier: Modifier = Modifier) {
    val color = statusColor(phase)
    val label = statusLabel(phase)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "Relay status: $label"
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontFamily = FontFamily.SansSerif,
        )
    }
}

@Composable
private fun HeaderAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 11.sp,
        color = RelayColors.Accent,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, RelayColors.Border, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
    )
}

@Composable
private fun DemoBadge() {
    Text(
        text = "DEMO",
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = RelayColors.Warning,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(RelayColors.Warning.copy(alpha = 0.12f))
            .border(1.dp, RelayColors.Warning.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun statusColor(phase: RelayPhase): Color = when (phase) {
    RelayPhase.ADVERTISING -> RelayColors.Accent
    RelayPhase.STARTING, RelayPhase.STOPPING -> RelayColors.Warning
    RelayPhase.FAILED -> RelayColors.Error
    RelayPhase.STOPPED -> RelayColors.Muted
}

private fun statusLabel(phase: RelayPhase): String = when (phase) {
    RelayPhase.ADVERTISING -> "Advertising"
    RelayPhase.STARTING -> "Starting…"
    RelayPhase.STOPPING -> "Stopping…"
    RelayPhase.FAILED -> "Needs attention"
    RelayPhase.STOPPED -> "Stopped"
}
