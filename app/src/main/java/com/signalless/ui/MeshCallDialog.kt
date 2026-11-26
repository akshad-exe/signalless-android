package com.signalless.app.ui

import android.Manifest
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.signalless.app.R
import com.signalless.app.features.call.CallEndReason
import com.signalless.app.features.call.CallPhase
import com.signalless.app.features.call.MeshCallState
import com.signalless.app.service.CallService
import kotlinx.coroutines.delay

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MeshCallDialog(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val state by CallService.state.collectAsStateWithLifecycle()
    val request by viewModel.callRequest.collectAsStateWithLifecycle()
    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val microphone = rememberPermissionState(Manifest.permission.RECORD_AUDIO) { granted ->
        if (granted) {
            afterGrant?.invoke()
        } else {
            Toast.makeText(context, R.string.call_ended_microphone, Toast.LENGTH_LONG).show()
            // Declines a ringing call that cannot be answered.
            CallService.command(context, CallService.ACTION_HANGUP)
        }
        afterGrant = null
    }
    fun withMicrophone(action: () -> Unit) {
        if (microphone.status.isGranted) {
            action()
        } else {
            afterGrant = action
            microphone.launchPermissionRequest()
        }
    }
    LaunchedEffect(request) {
        val peerID = request ?: return@LaunchedEffect
        viewModel.onCallRequestHandled()
        withMicrophone { CallService.place(context, peerID) }
    }
    if (!state.visible) return

    fun command(action: String, enabled: Boolean = false) =
        CallService.command(context, action, enabled)
    // Back clears a finished or still connecting call; a live call stays until hung up.
    Dialog(onDismissRequest = {
        if (state.endReason != null || state.connecting) command(CallService.ACTION_HANGUP)
    }) {
        MeshCallCard(
            state = state,
            actions = CallUiActions(
                dismiss = { command(CallService.ACTION_HANGUP) },
                accept = {
                    withMicrophone {
                        command(CallService.ACTION_ANSWER)
                        state.peerID?.let(viewModel::showPrivateChatSheet)
                    }
                },
                decline = { command(CallService.ACTION_HANGUP) },
                hangup = { command(CallService.ACTION_HANGUP) },
                setMuted = { command(CallService.ACTION_MUTE, it) },
                setSpeaker = { command(CallService.ACTION_SPEAKER, it) }
            )
        )
    }
}

/** What the call card can ask for, from the chat screen or the incoming-call screen. */
internal class CallUiActions(
    val dismiss: () -> Unit,
    val accept: () -> Unit,
    val decline: () -> Unit,
    val hangup: () -> Unit,
    val setMuted: (Boolean) -> Unit,
    val setSpeaker: (Boolean) -> Unit
)

@Composable
internal fun MeshCallCard(state: MeshCallState, actions: CallUiActions) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colorScheme.surface,
        contentColor = colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = state.nickname.ifBlank { stringResource(R.string.call_mesh_peer) },
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = callStatusText(state),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(28.dp))
            CallActions(state = state, actions = actions)
        }
    }
}

@Composable
private fun callStatusText(state: MeshCallState): String {
    if (state.connecting) return stringResource(R.string.call_securing)
    state.endReason?.let { return endReasonText(it) }
    return when (state.phase) {
        CallPhase.OUTGOING -> stringResource(R.string.call_outgoing)
        CallPhase.INCOMING -> stringResource(R.string.call_incoming)
        CallPhase.ACTIVE ->
            if (state.reconnecting) stringResource(R.string.call_reconnecting)
            else callDuration(state.connectedAtMs)
        CallPhase.IDLE -> ""
    }
}

@Composable
private fun endReasonText(reason: CallEndReason): String = when (reason) {
    CallEndReason.DECLINED -> stringResource(R.string.call_ended_declined)
    CallEndReason.BUSY -> stringResource(R.string.call_ended_busy)
    CallEndReason.TIMEOUT -> stringResource(R.string.call_ended_timeout)
    CallEndReason.HUNG_UP -> stringResource(R.string.call_ended_hung_up)
    CallEndReason.UNAVAILABLE -> stringResource(R.string.call_ended_unavailable)
    CallEndReason.MICROPHONE -> stringResource(R.string.call_ended_microphone)
    CallEndReason.UNREACHABLE -> stringResource(R.string.call_ended_unreachable)
    CallEndReason.CONNECTION_LOST -> stringResource(R.string.call_ended_connection_lost)
}

@Composable
private fun callDuration(connectedAtMs: Long?): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val elapsed = ((now - (connectedAtMs ?: now)).coerceAtLeast(0L)) / 1_000L
    val minutes = elapsed / 60L
    val seconds = elapsed % 60L
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun CallActions(state: MeshCallState, actions: CallUiActions) {
    when {
        state.endReason != null || state.connecting -> {
            CallActionButton(
                onClick = actions.dismiss,
                icon = Icons.Filled.CallEnd,
                contentDescription = stringResource(R.string.cd_end_call),
                container = MaterialTheme.colorScheme.error,
                content = MaterialTheme.colorScheme.onError
            )
        }
        state.phase == CallPhase.INCOMING -> {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                CallActionButton(
                    onClick = actions.decline,
                    icon = Icons.Filled.CallEnd,
                    contentDescription = stringResource(R.string.cd_decline_call),
                    container = MaterialTheme.colorScheme.error,
                    content = MaterialTheme.colorScheme.onError
                )
                CallActionButton(
                    onClick = actions.accept,
                    icon = Icons.Filled.Call,
                    contentDescription = stringResource(R.string.cd_accept_call),
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        state.phase == CallPhase.ACTIVE -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallActionButton(
                    onClick = { actions.setMuted(!state.muted) },
                    icon = if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = stringResource(
                        if (state.muted) R.string.cd_unmute_call else R.string.cd_mute_call
                    ),
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CallActionButton(
                    onClick = actions.hangup,
                    icon = Icons.Filled.CallEnd,
                    contentDescription = stringResource(R.string.cd_end_call),
                    container = MaterialTheme.colorScheme.error,
                    content = MaterialTheme.colorScheme.onError
                )
                CallActionButton(
                    onClick = { actions.setSpeaker(!state.speakerOn) },
                    icon = if (state.speakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = stringResource(
                        if (state.speakerOn) R.string.cd_call_earpiece else R.string.cd_call_speaker
                    ),
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        else -> {
            CallActionButton(
                onClick = actions.hangup,
                icon = Icons.Filled.CallEnd,
                contentDescription = stringResource(R.string.cd_end_call),
                container = MaterialTheme.colorScheme.error,
                content = MaterialTheme.colorScheme.onError
            )
        }
    }
}

@Composable
private fun CallActionButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    container: Color,
    content: Color
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(64.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = container,
            contentColor = content
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription
        )
    }
}
