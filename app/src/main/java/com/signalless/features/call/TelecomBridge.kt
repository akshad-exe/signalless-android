package com.signalless.app.features.call

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Registers one call with the system through Core-Telecom, so the platform knows
 * about cellular calls, headsets and audio routes. A device without a working
 * Telecom stack keeps the call, just without that integration.
 */
class TelecomBridge(private val context: Context) {
    interface Listener {
        fun onSystemAnswer()
        fun onSystemDisconnect()
        fun onSystemHold(held: Boolean)
    }

    sealed interface Command {
        data object Answer : Command
        data object Active : Command
        data class Speaker(val on: Boolean) : Command
        data object Disconnect : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)

    fun send(command: Command) {
        commands.trySend(command)
    }

    /** Suspends for the life of the call. Send [Command.Disconnect] to finish. */
    suspend fun run(peerID: String, name: String, incoming: Boolean, listener: Listener) {
        try {
            val manager = CallsManager(context)
            manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            val attributes = CallAttributesCompat(
                displayName = name,
                address = Uri.fromParts("signalless", peerID, null),
                direction = if (incoming) {
                    CallAttributesCompat.DIRECTION_INCOMING
                } else {
                    CallAttributesCompat.DIRECTION_OUTGOING
                },
                callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE
            )
            manager.addCall(
                attributes,
                onAnswer = { listener.onSystemAnswer() },
                onDisconnect = { listener.onSystemDisconnect() },
                onSetActive = { listener.onSystemHold(false) },
                onSetInactive = { listener.onSystemHold(true) }
            ) {
                launch {
                    for (command in commands) {
                        when (command) {
                            Command.Answer -> answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
                            Command.Active -> setActive()
                            is Command.Speaker -> {
                                val wanted = if (command.on) {
                                    CallEndpointCompat.TYPE_SPEAKER
                                } else {
                                    CallEndpointCompat.TYPE_EARPIECE
                                }
                                availableEndpoints.first().firstOrNull { it.type == wanted }
                                    ?.let { requestEndpointChange(it) }
                            }
                            Command.Disconnect -> {
                                disconnect(DisconnectCause(DisconnectCause.LOCAL))
                                return@launch
                            }
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Telecom unavailable: ${error.message}")
        }
    }

    private companion object {
        const val TAG = "TelecomBridge"
    }
}
