package com.signalless.app.features.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.signalless.app.features.voice.LiveVoiceTarget
import com.signalless.app.features.voice.VoiceRecorder
import kotlin.coroutines.coroutineContext
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MeshCallState(
    val phase: CallPhase = CallPhase.IDLE,
    val peerID: String? = null,
    val nickname: String = "",
    val connectedAtMs: Long? = null,
    val endReason: CallEndReason? = null,
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    val needsMicrophone: Boolean = false,
    val connecting: Boolean = false
) {
    val visible: Boolean
        get() = connecting || phase != CallPhase.IDLE || endReason != null
}

/**
 * Drives one mesh call: Noise signaling, open-microphone live voice, and the
 * state the call screen renders. Audio is rotated in short segments so a call
 * stays inside the live-voice burst budget.
 */
class MeshCallController(
    private val scope: CoroutineScope,
    private val context: Context,
    private val hasSession: (String) -> Boolean,
    private val requestHandshake: (String) -> Unit,
    private val sendSignal: (String, ByteArray) -> Unit,
    private val sendVoiceFrame: (String, ByteArray) -> Unit,
    private val peerNickname: (String) -> String,
    private val onActivePeerChanged: (String?) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val session = CallSession()
    private val mutex = Mutex()
    private val random = SecureRandom()
    private val _state = MutableStateFlow(MeshCallState())
    val state: StateFlow<MeshCallState> = _state.asStateFlow()

    private var connectingPeer: String? = null
    private var pending: Pending? = null
    private var connectJob: Job? = null
    private var audioJob: Job? = null
    private var endBannerJob: Job? = null
    private var muted = false
    private var speakerOn = false
    private var needsMicrophone = false
    private val audioGeneration = AtomicInteger(0)

    @Volatile private var mutedFlag = false
    @Volatile private var phaseMirror = CallPhase.IDLE
    @Volatile private var activePeer: String? = null

    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeakerphone: Boolean = false
    private var inCallAudio = false

    init {
        scope.launch {
            while (isActive) {
                delay(1_000L)
                mutex.withLock { applyLocked { session.tick(clock()) } }
            }
        }
    }

    fun place(peerID: String) {
        if (peerID.isEmpty()) return
        connectJob = scope.launch {
            val allowed = mutex.withLock {
                if (session.snapshot().phase != CallPhase.IDLE || connectingPeer != null) {
                    false
                } else if (!microphoneGranted()) {
                    pending = Pending.Place(peerID)
                    needsMicrophone = true
                    publish()
                    false
                } else {
                    true
                }
            }
            if (!allowed) return@launch
            try {
                openOutgoing(peerID)
            } finally {
                if (!isActive) {
                    mutex.withLock {
                        if (connectingPeer == peerID && session.snapshot().phase == CallPhase.IDLE) {
                            connectingPeer = null
                            publish()
                        }
                    }
                }
            }
        }
    }

    fun accept() {
        scope.launch {
            mutex.withLock {
                if (session.snapshot().phase != CallPhase.INCOMING) return@withLock
                if (!microphoneGranted()) {
                    pending = Pending.Accept
                    needsMicrophone = true
                    publish()
                    return@withLock
                }
                applyLocked { session.accept(clock()) }
            }
        }
    }

    fun decline() {
        connectJob?.cancel()
        scope.launch {
            mutex.withLock {
                connectingPeer = null
                applyLocked { session.decline(clock()) }
            }
        }
    }

    fun hangup() {
        connectJob?.cancel()
        scope.launch {
            mutex.withLock {
                connectingPeer = null
                when (session.snapshot().phase) {
                    CallPhase.INCOMING -> applyLocked { session.decline(clock()) }
                    CallPhase.OUTGOING, CallPhase.ACTIVE -> applyLocked { session.hangup(clock()) }
                    CallPhase.IDLE -> {
                        session.acknowledgeEnd()
                        publish()
                    }
                }
            }
        }
    }

    fun onRemote(peerID: String, payload: ByteArray, receivedAtMs: Long = 0L) {
        val signal = CallSignal.decode(payload) ?: return
        scope.launch {
            mutex.withLock {
                if (connectingPeer == peerID && signal.action == CallAction.INVITE) {
                    connectingPeer = null
                }
                val atMs = if (receivedAtMs > 0L) receivedAtMs else clock()
                applyLocked { session.onRemote(peerID, signal, atMs) }
            }
        }
    }

    fun onMicrophoneResult(granted: Boolean) {
        scope.launch {
            val action = mutex.withLock {
                needsMicrophone = false
                val action = pending
                pending = null
                if (granted) {
                    publish()
                    action
                } else {
                    val peer = session.snapshot().peerID ?: connectingPeer
                    connectingPeer = null
                    if (session.snapshot().phase == CallPhase.INCOMING) {
                        applyLocked { session.decline(clock()) }
                    } else if (peer != null) {
                        session.markEnded(peer, CallEndReason.MICROPHONE)
                        publish()
                        scheduleEndDismiss()
                    } else {
                        publish()
                    }
                    null
                }
            }
            when (action) {
                is Pending.Place -> {
                    connectJob?.cancel()
                    connectJob = scope.launch { openOutgoing(action.peerID) }
                }
                Pending.Accept -> accept()
                null -> Unit
            }
        }
    }

    fun setMuted(muted: Boolean) {
        scope.launch {
            mutex.withLock {
                this@MeshCallController.muted = muted
                mutedFlag = muted
                publish()
            }
        }
    }

    fun setSpeaker(on: Boolean) {
        scope.launch {
            mutex.withLock {
                speakerOn = on
                if (inCallAudio) applySpeakerphone()
                publish()
            }
        }
    }

    fun isOngoing(): Boolean {
        val current = _state.value
        return current.connecting || current.phase != CallPhase.IDLE
    }

    private suspend fun openOutgoing(peerID: String) {
        val waitingForSession = mutex.withLock {
            if (session.snapshot().phase != CallPhase.IDLE) return
            if (hasSession(peerID)) {
                applyLocked { session.place(peerID, newCallId(), clock()) }
                return
            }
            connectingPeer = peerID
            publish()
            true
        }
        if (!waitingForSession) return
        requestHandshake(peerID)
        val ready = awaitSession(peerID)
        mutex.withLock {
            if (connectingPeer != peerID) return@withLock
            connectingPeer = null
            if (session.snapshot().phase != CallPhase.IDLE) {
                publish()
                return@withLock
            }
            if (ready) {
                applyLocked { session.place(peerID, newCallId(), clock()) }
            } else {
                session.markEnded(peerID, CallEndReason.UNAVAILABLE)
                publish()
                scheduleEndDismiss()
            }
        }
    }

    private suspend fun awaitSession(peerID: String): Boolean {
        if (hasSession(peerID)) return true
        repeat(SESSION_POLLS) {
            delay(SESSION_POLL_MS)
            if (!coroutineContext.isActive) return false
            if (hasSession(peerID)) return true
            val stillWaiting = mutex.withLock { connectingPeer == peerID }
            if (!stillWaiting) return false
        }
        return hasSession(peerID)
    }

    private fun applyLocked(block: () -> List<CallSession.Effect>) {
        val before = session.snapshot()
        val effects = block()
        val after = session.snapshot()
        val changed = effects.isNotEmpty() ||
            before.phase != after.phase ||
            before.endReason != after.endReason ||
            before.peerID != after.peerID ||
            before.connectedAtMs != after.connectedAtMs
        if (!changed) return
        effects.forEach { effect ->
            sendSignal(effect.peerID, effect.signal.encode())
        }
        publish()
        if (after.phase == CallPhase.ACTIVE && before.phase != CallPhase.ACTIVE) startAudio()
        if (before.phase == CallPhase.ACTIVE && after.phase != CallPhase.ACTIVE) stopAudio()
        if (
            after.phase == CallPhase.IDLE &&
            after.endReason != null &&
            before.endReason != after.endReason
        ) {
            scheduleEndDismiss()
        }
    }

    private fun publish() {
        val snapshot = session.snapshot()
        val peer = snapshot.peerID ?: connectingPeer
        phaseMirror = snapshot.phase
        activePeer = if (snapshot.phase == CallPhase.ACTIVE) snapshot.peerID else null
        mutedFlag = muted
        _state.value = MeshCallState(
            phase = snapshot.phase,
            peerID = peer,
            nickname = peer?.let(peerNickname).orEmpty(),
            connectedAtMs = snapshot.connectedAtMs,
            endReason = snapshot.endReason,
            muted = muted,
            speakerOn = speakerOn,
            needsMicrophone = needsMicrophone,
            connecting = connectingPeer != null && snapshot.phase == CallPhase.IDLE && snapshot.endReason == null
        )
        onActivePeerChanged(activePeer)
    }

    private fun scheduleEndDismiss() {
        endBannerJob?.cancel()
        endBannerJob = scope.launch {
            delay(END_BANNER_MS)
            mutex.withLock {
                if (session.snapshot().phase == CallPhase.IDLE) {
                    session.acknowledgeEnd()
                    publish()
                }
            }
        }
    }

    private fun startAudio() {
        audioJob?.cancel()
        val generation = audioGeneration.incrementAndGet()
        audioJob = scope.launch(Dispatchers.IO) {
            setInCallAudio(true)
            var recorder: VoiceRecorder? = null
            try {
                while (isActive && phaseMirror == CallPhase.ACTIVE) {
                    if (mutedFlag) {
                        delay(SLICE_MS)
                        continue
                    }
                    val peer = activePeer ?: break
                    val current = VoiceRecorder(
                        context,
                        LiveVoiceTarget { frame -> sendVoiceFrame(peer, frame) },
                        voiceCommunication = true
                    )
                    recorder = current
                    if (current.start() == null) {
                        recorder = null
                        delay(1_000L)
                        continue
                    }
                    var discard = false
                    var slices = 0
                    while (slices < SEGMENT_SLICES) {
                        delay(SLICE_MS)
                        slices++
                        if (mutedFlag) {
                            discard = true
                            break
                        }
                        if (!isActive || phaseMirror != CallPhase.ACTIVE || activePeer != peer) break
                    }
                    val file = current.stop(canceled = discard)
                    recorder = null
                    file?.delete()
                }
            } finally {
                runCatching { recorder?.stop(canceled = false)?.delete() }
                if (generation == audioGeneration.get()) setInCallAudio(false)
            }
        }
    }

    private fun stopAudio() {
        audioJob?.cancel()
        audioJob = null
    }

    private fun microphoneGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun newCallId(): ByteArray =
        ByteArray(CallSignal.CALL_ID_SIZE).also(random::nextBytes)

    @Suppress("DEPRECATION")
    private fun setInCallAudio(active: Boolean) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (active && !inCallAudio) {
            savedAudioMode = manager.mode
            savedSpeakerphone = manager.isSpeakerphoneOn
            inCallAudio = true
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        if (!active && inCallAudio) {
            manager.isSpeakerphoneOn = savedSpeakerphone
            manager.mode = savedAudioMode
            inCallAudio = false
            return
        }
        if (inCallAudio) applySpeakerphone()
    }

    @Suppress("DEPRECATION")
    private fun applySpeakerphone() {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        manager.isSpeakerphoneOn = speakerOn
    }

    private sealed interface Pending {
        data class Place(val peerID: String) : Pending
        data object Accept : Pending
    }

    companion object {
        private const val SESSION_POLLS = 30
        private const val SESSION_POLL_MS = 500L
        private const val END_BANNER_MS = 2_200L
        private const val SLICE_MS = 200L
        private const val SEGMENT_SLICES = 100
    }
}
