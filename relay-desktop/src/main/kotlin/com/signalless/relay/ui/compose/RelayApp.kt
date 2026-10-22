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
import com.signalless.relay.ui.RelayUiState
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

/** Runs the operator window for a live controller; blocks until the window exits. */
fun runRelayApp(controller: RelayController, logFile: Path?) {
    application {
        val state by controller.state.collectAsState()
        val actions = remember(controller, logFile) {
            RelayActions(
                onStart = controller::start,
                onStop = controller::stop,
                onRestart = controller::restart,
                onCopyId = {
                    copyToClipboard(controller.state.value.peerId)
                },
                onOpenLog = logFile?.let { path -> { openLogFile(path) } },
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
    state: RelayUiState,
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

/** Copies plain text through AWT; composition locals are not available at the application root. */
internal fun copyToClipboard(text: String) {
    try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    } catch (error: Exception) {
        System.err.println("relay-desktop: could not copy peer ID: ${error.message}")
    }
}

/** Opens the configured log file, creating it first so an empty log is still reachable. */
private fun openLogFile(path: Path) {
    try {
        val target = path.toAbsolutePath()
        target.parent?.let { Files.createDirectories(it) }
        if (!Files.exists(target)) {
            Files.createFile(target)
        }
        if (!Desktop.isDesktopSupported()) {
            System.err.println("relay-desktop: opening files is not supported on this desktop")
            return
        }
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.OPEN)) {
            System.err.println("relay-desktop: opening files is not supported on this desktop")
            return
        }
        desktop.open(target.toFile())
    } catch (error: Exception) {
        System.err.println("relay-desktop: could not open log: ${error.message}")
    }
}
