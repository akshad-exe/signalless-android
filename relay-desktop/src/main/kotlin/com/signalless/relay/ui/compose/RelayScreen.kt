package com.signalless.relay.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalless.relay.ui.RelayPhase
import com.signalless.relay.ui.RelayUiState
import com.signalless.relay.ui.compose.components.EventLog
import com.signalless.relay.ui.compose.components.LinkList
import com.signalless.relay.ui.compose.components.SectionCard
import com.signalless.relay.ui.compose.components.StatusHeader
import com.signalless.relay.ui.compose.components.TrafficGrid

/**
 * Footer callbacks. Null means the control is not available in this build
 * phase; Phase 3 wires every action except Quit.
 */
data class RelayActions(
    val onStart: (() -> Unit)? = null,
    val onStop: (() -> Unit)? = null,
    val onRestart: (() -> Unit)? = null,
    val onCopyId: (() -> Unit)? = null,
    val onOpenLog: (() -> Unit)? = null,
    val onQuit: () -> Unit,
)

@Composable
fun RelayScreen(
    state: RelayUiState,
    demo: Boolean,
    actions: RelayActions,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(RelayColors.Background),
    ) {
        val compact = maxHeight < 700.dp
        val pagePadding = if (compact) 12.dp else 16.dp
        val gap = if (compact) 8.dp else 12.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pagePadding),
        ) {
            StatusHeader(
                state = state,
                demo = demo,
                onCopyId = actions.onCopyId,
                compact = compact,
            )

            Spacer(Modifier.height(gap))

            val linksHeight = if (state.links.isEmpty()) {
                if (compact) 112.dp else 132.dp
            } else {
                if (compact) 162.dp else 172.dp
            }
            SectionCard(
                title = "Links",
                modifier = Modifier.height(linksHeight),
                compact = compact,
                trailing = {
                    Text(
                        text = "${state.links.size} / ${state.maxLinks}",
                        fontSize = 11.sp,
                        color = RelayColors.Muted,
                    )
                },
            ) {
                LinkList(
                    links = state.links,
                    nowMs = state.nowMs,
                    compact = compact,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }

            Spacer(Modifier.height(gap))

            SectionCard(title = "Traffic", compact = compact) {
                TrafficGrid(
                    stats = state.stats,
                    compact = compact,
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = if (compact) 8.dp else 16.dp,
                    ),
                )
            }

            Spacer(Modifier.height(gap))

            SectionCard(
                title = "Events",
                modifier = Modifier.weight(1f),
                compact = compact,
                trailing = {
                    Text(
                        text = "${state.events.size}",
                        fontSize = 11.sp,
                        color = RelayColors.Muted,
                    )
                },
            ) {
                EventLog(
                    events = state.events,
                    compact = compact,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }

            Spacer(Modifier.height(gap))

            Footer(state = state, actions = actions, compact = compact)
        }
    }
}

@Composable
private fun Footer(state: RelayUiState, actions: RelayActions, compact: Boolean) {
    val canStart = state.phase == RelayPhase.STOPPED || state.phase == RelayPhase.FAILED
    val canStop = state.phase == RelayPhase.STARTING || state.phase == RelayPhase.ADVERTISING
    val canRestart = state.phase != RelayPhase.STOPPING

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.onStart?.let { onStart ->
            Button(
                onClick = onStart,
                enabled = canStart,
                modifier = Modifier.heightIn(min = 34.dp),
            ) {
                Text("Start", fontSize = 12.sp)
            }
        }
        actions.onStop?.let { onStop ->
            OutlinedButton(
                onClick = onStop,
                enabled = canStop,
                modifier = Modifier.heightIn(min = 34.dp),
            ) {
                Text("Stop", fontSize = 12.sp)
            }
        }
        actions.onRestart?.let { onRestart ->
            OutlinedButton(
                onClick = onRestart,
                enabled = canRestart,
                modifier = Modifier.heightIn(min = 34.dp),
            ) {
                Text("Restart", fontSize = 12.sp)
            }
        }
        actions.onOpenLog?.let { onOpenLog ->
            OutlinedButton(
                onClick = onOpenLog,
                modifier = Modifier.heightIn(min = 34.dp),
            ) {
                Text("Open log", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = actions.onQuit,
            modifier = Modifier.heightIn(min = 34.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RelayColors.Muted),
        ) {
            Text("Quit", style = MaterialTheme.typography.bodyMedium, fontSize = 12.sp)
        }
    }
}
