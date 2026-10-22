package com.signalless.relay.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.signalless.relay.RelayConfig
import com.signalless.relay.VERSION
import com.signalless.relay.identity.RelayIdentity

/**
 * Phase 0 spike only.
 *
 * Proves that Compose Desktop builds, runs, and packages on this toolchain.
 * Phase 2 replaces this file with the real relay window.
 */
private val SmokeColors = darkColorScheme(
    primary = Color(0xFF34D399),
    background = Color(0xFF0E0F10),
    surface = Color(0xFF17181A),
    onBackground = Color(0xFFECEDEE),
    onSurface = Color(0xFFECEDEE),
)

fun runUiSmoke(config: RelayConfig, identity: RelayIdentity): Int {
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "SignalLess Relay",
            state = rememberWindowState(width = 600.dp, height = 760.dp),
        ) {
            MaterialTheme(colorScheme = SmokeColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SmokeScreen(
                        nickname = config.nickname,
                        transport = config.transport.name.lowercase(),
                        peerId = identity.peerId,
                        onQuit = ::exitApplication,
                    )
                }
            }
        }
    }
    return 0
}

@Composable
private fun SmokeScreen(
    nickname: String,
    transport: String,
    peerId: String,
    onQuit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("SignalLess Relay", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Compose Desktop smoke window",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LabeledValue("Version", "relay-desktop $VERSION")
        LabeledValue("Nickname", nickname)
        LabeledValue("Transport", transport)
        LabeledValue("Peer ID", peerId, monospace = true)

        Spacer(modifier = Modifier.weight(1f))

        Text(
            "Phase 0: window plumbing only. No engine is running.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onQuit,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("Quit")
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String, monospace: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}
