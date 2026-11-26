package com.signalless.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signalless.app.service.CallService
import com.signalless.app.ui.theme.SignalLessTheme

/** Full-screen call UI shown over the lock screen from the call notification. */
class IncomingCallActivity : ComponentActivity() {
    private val microphone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            command(if (granted) CallService.ACTION_ANSWER else CallService.ACTION_HANGUP)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContent {
            SignalLessTheme {
                val state by CallService.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.visible) { if (!state.visible) finish() }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                        MeshCallCard(
                            state = state,
                            actions = CallUiActions(
                                dismiss = ::finish,
                                accept = ::answer,
                                decline = { command(CallService.ACTION_HANGUP) },
                                hangup = { command(CallService.ACTION_HANGUP) },
                                setMuted = { command(CallService.ACTION_MUTE, it) },
                                setSpeaker = { command(CallService.ACTION_SPEAKER, it) }
                            )
                        )
                    }
                }
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_ANSWER, false)) answer()
    }

    private fun command(action: String, enabled: Boolean = false) =
        CallService.command(this, action, enabled)

    private fun answer() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) command(CallService.ACTION_ANSWER) else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }

    companion object {
        const val EXTRA_ANSWER = "answer_call"
    }
}
