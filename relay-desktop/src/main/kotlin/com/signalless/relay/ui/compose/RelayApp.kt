package com.signalless.relay.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
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
                onClearEvents = controller::clearEvents,
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
    val density = LocalDensity.current.density
    val savedBounds = remember { WindowBoundsStore.load() }
    val windowState = rememberWindowState(
        position = savedBounds?.let { bounds ->
            WindowPosition((bounds.x / density).dp, (bounds.y / density).dp)
        } ?: WindowPosition(Alignment.Center),
        size = savedBounds?.let { bounds ->
            DpSize((bounds.width / density).dp, (bounds.height / density).dp)
        } ?: DpSize(600.dp, 760.dp),
    )
    Window(
        onCloseRequest = actions.onQuit,
        state = windowState,
        title = if (demo) "SignalLess Relay (demo)" else "SignalLess Relay",
        onPreviewKeyEvent = { event -> handleShortcut(event, state, actions) },
    ) {
        LaunchedEffect(density) {
            window.minimumSize = Dimension(
                (MIN_WIDTH_DP * density).roundToInt(),
                (MIN_HEIGHT_DP * density).roundToInt(),
            )
        }

        DisposableEffect(window) {
            onDispose { WindowBoundsStore.save(window.bounds) }
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

private fun handleShortcut(
    event: KeyEvent,
    state: RelayUiState,
    actions: RelayActions,
): Boolean {
    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) {
        return false
    }
    return when {
        event.key == Key.Q -> {
            actions.onQuit()
            true
        }
        event.key == Key.C && event.isShiftPressed -> {
            val copy = actions.onCopyId
            copy?.invoke()
            copy != null
        }
        event.key == Key.R && !event.isShiftPressed -> {
            val restart = actions.onRestart
            if (restart != null && RelayControlPolicy.canRestart(state.phase)) {
                restart()
                true
            } else {
                false
            }
        }
        else -> false
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
