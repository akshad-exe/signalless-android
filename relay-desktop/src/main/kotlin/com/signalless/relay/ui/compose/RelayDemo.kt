package com.signalless.relay.ui.compose

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import com.signalless.relay.ui.RelayEvent
import com.signalless.relay.ui.RelayEventKind
import com.signalless.relay.ui.RelayLinkView
import com.signalless.relay.ui.RelayPhase
import com.signalless.relay.ui.RelayUiState
import com.signalless.relay.ui.emptyRelayStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive

/** Synthetic state for layout work. Never starts an engine or resolves a helper. */
internal class DemoRelayState(version: String, startedAt: Long = System.currentTimeMillis()) {
    private val _state = MutableStateFlow(initialState(version, startedAt))
    val state: StateFlow<RelayUiState> = _state.asStateFlow()

    fun tick(now: Long = System.currentTimeMillis()) {
        _state.update { it.copy(nowMs = now) }
    }

    private fun initialState(version: String, now: Long): RelayUiState {
        val events = listOf(
            demoEvent(now - 510_000L, RelayEventKind.LINK_DOWN, "link down l9 reason=session_closed"),
            demoEvent(now - 420_000L, RelayEventKind.LINK_UP, "link up l10 mtu=517"),
            demoEvent(now - 310_000L, RelayEventKind.ERROR, "ADVERTISING_FAILED: radio busy"),
            demoEvent(now - 250_000L, RelayEventKind.LIFECYCLE, "restart requested"),
            demoEvent(now - 240_000L, RelayEventKind.LIFECYCLE, "relay starting"),
            demoEvent(now - 200_000L, RelayEventKind.READY, "advertising ready"),
            demoEvent(now - 192_000L, RelayEventKind.LINK_UP, "link up l11 mtu=517"),
            demoEvent(now - 41_000L, RelayEventKind.LINK_UP, "link up l10 mtu=517"),
        )

        return RelayUiState(
            phase = RelayPhase.ADVERTISING,
            nickname = "SignalLessRelay",
            peerId = "9f97f3321a2b3c4d",
            transport = "helper",
            version = version,
            maxLinks = 7,
            advertisingSinceMs = now - 2_531_000L,
            links = listOf(
                RelayLinkView(
                    id = "l11",
                    peerId = "4a7c9e105b228d31",
                    mtu = 517,
                    notificationSize = 514,
                    firstSeenMs = now - 192_000L,
                ),
                RelayLinkView(
                    id = "l10",
                    peerId = null,
                    mtu = 517,
                    notificationSize = 514,
                    firstSeenMs = now - 41_000L,
                ),
            ),
            stats = emptyRelayStats().copy(
                received = 128L,
                forwarded = 96L,
                framesSent = 193L,
                stored = 2L,
                storeFlushed = 1L,
                duplicates = 4L,
            ),
            events = events,
            error = null,
            nowMs = now,
        )
    }

    private fun demoEvent(atMs: Long, kind: RelayEventKind, text: String) =
        RelayEvent(atMs = atMs, kind = kind, text = text)
}

/** Opens the demo window. Blocks until the window exits. */
fun runDemoApp(version: String) {
    application {
        val demo = remember { DemoRelayState(version) }
        val state by demo.state.collectAsState()

        LaunchedEffect(Unit) {
            while (isActive) {
                delay(1_000L)
                demo.tick()
            }
        }

        val actions = remember(demo) {
            RelayActions(
                onCopyId = { copyToClipboard(demo.state.value.peerId) },
                onQuit = { exitApplication() },
            )
        }
        RelayWindow(
            state = state,
            demo = true,
            actions = actions,
        )
    }
}
