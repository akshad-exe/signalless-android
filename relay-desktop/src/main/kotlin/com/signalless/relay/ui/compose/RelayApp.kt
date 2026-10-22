package com.signalless.relay.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.signalless.relay.ui.RelayController
import java.awt.Dimension
import kotlin.math.roundToInt

/** Runs the operator window for a live controller; blocks until the window exits. */
fun runRelayApp(controller: RelayController) {
    application {
        val state by controller.state.collectAsState()
        val actions = remember(controller) {
            RelayActions(
                onQuit = {
                    controller.shutdown()
                    exitApplication()
                },
            )
        }

        LaunchedEffect(Unit) {
            controller.start()
        }

        RelayWindow(
            state = state,
            demo = false,
            actions = actions,
        )
    }
}

@Composable
internal fun RelayWindow(
    state: com.signalless.relay.ui.RelayUiState,
    demo: Boolean,
    actions: RelayActions,
) {
    val windowState = rememberWindowState(width = 600.dp, height = 760.dp)
    Window(
        onCloseRequest = actions.onQuit,
        state = windowState,
        title = if (demo) "SignalLess Relay (demo)" else "SignalLess Relay",
    ) {
        val density = LocalDensity.current.density
        LaunchedEffect(density) {
            window.minimumSize = Dimension(
                (MIN_WIDTH_DP * density).roundToInt(),
                (MIN_HEIGHT_DP * density).roundToInt(),
            )
        }

        RelayTheme {
            RelayScreen(
                state = state,
                demo = demo,
                actions = actions,
            )
        }
    }
}

private const val MIN_WIDTH_DP = 520
private const val MIN_HEIGHT_DP = 620
