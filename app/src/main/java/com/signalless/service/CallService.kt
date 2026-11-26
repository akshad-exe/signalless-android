package com.signalless.app.service

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.os.SystemClock
import com.signalless.app.features.call.CallAction
import com.signalless.app.features.call.CallAudioEngine
import com.signalless.app.features.call.CallAudioPacket
import com.signalless.app.features.call.CallPath
import com.signalless.app.features.call.CallPathMonitor
import kotlinx.coroutines.withContext
import com.signalless.app.features.call.CallTransport
import com.signalless.app.features.call.CallEndReason
import com.signalless.app.features.call.CallNotifications
import com.signalless.app.features.call.CallPhase
import com.signalless.app.features.call.CallSession
import com.signalless.app.features.call.CallSignal
import com.signalless.app.features.call.MeshCallState
import com.signalless.app.features.call.TelecomBridge
import com.signalless.app.features.call.VoiceCodecs
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the one voice call, so it survives the UI going away or the screen
 * locking. Screens send actions as intents and observe [state].
 * All call state is confined to the main thread.
 */
class CallService : Service(), TelecomBridge.Listener {
    private val scope = MainScope()
    private val random = SecureRandom()
    private lateinit var session: CallSession
    private var telecom: TelecomBridge? = null
    private var connecting: Job? = null
    private var connectingPeer: String? = null
    private var ending: Job? = null
    private var muted = false
    private var speakerOn = false
    private var cpuLock: PowerManager.WakeLock? = null
    private var proximityLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL

    @Volatile
    private var engine: CallAudioEngine? = null

    @Volatile
    private var audioPeer: String? = null

    @Volatile
    private var monitor: CallPathMonitor? = null

    private val mesh get() = MeshServiceHolder.getUnifiedOrCreate(applicationContext)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = CallSession(localCodecMask = VoiceCodecs.localMask())
        instance = this
        scope.launch {
            while (isActive) {
                delay(1_000L)
                monitor?.let { current ->
                    val reconnecting = withContext(Dispatchers.IO) {
                        current.tick(SystemClock.elapsedRealtime())
                    }
                    if (monitor === current) {
                        dispatch {
                            session.setReconnecting(reconnecting, System.currentTimeMillis())
                            emptyList()
                        }
                    }
                }
                dispatch { session.tick(System.currentTimeMillis()) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peerID = intent?.getStringExtra(EXTRA_PEER_ID).orEmpty()
        val enabled = intent?.getBooleanExtra(EXTRA_ENABLED, false) == true
        when (intent?.action) {
            ACTION_PLACE -> place(peerID)
            ACTION_SIGNAL -> intent.getByteArrayExtra(EXTRA_PAYLOAD)
                ?.let(CallSignal::decode)
                ?.let { signal -> onSignal(peerID, signal) }
            ACTION_ANSWER -> dispatch { session.accept(System.currentTimeMillis()) }
            ACTION_HANGUP -> hangup()
            ACTION_MUTE -> {
                muted = enabled
                refresh()
            }
            ACTION_SPEAKER -> {
                speakerOn = enabled
                route()
                refresh()
            }
        }
        // startForegroundService callers must be answered even when nothing changed. A visible
        // call already did so in refresh().
        val started = intent?.action == ACTION_PLACE || intent?.action == ACTION_SIGNAL
        if (started && !mutableState.value.visible) showForeground()
        stopIfIdle()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopAudio()
        telecom?.send(TelecomBridge.Command.Disconnect)
        scope.cancel()
        instance = null
        mutableState.value = MeshCallState()
        super.onDestroy()
    }

    override fun onSystemAnswer() {
        scope.launch { dispatch { session.accept(System.currentTimeMillis()) } }
    }

    override fun onSystemDisconnect() {
        scope.launch { hangup() }
    }

    override fun onSystemHold(held: Boolean) {
        scope.launch { dispatch { session.hold(held) } }
    }

    private fun place(peerID: String) {
        if (peerID.isEmpty() || connectingPeer != null || session.snapshot().phase != CallPhase.IDLE) return
        ending?.cancel()
        session.acknowledgeEnd()
        connectingPeer = peerID
        refresh()
        connecting = scope.launch {
            if (!mesh.hasEstablishedSession(peerID)) mesh.initiateNoiseHandshake(peerID)
            var polls = 0
            while (!mesh.hasEstablishedSession(peerID) && polls++ < SESSION_POLLS) delay(SESSION_POLL_MS)
            connectingPeer = null
            if (mesh.hasEstablishedSession(peerID)) {
                val callId = ByteArray(CallSignal.CALL_ID_SIZE).also(random::nextBytes)
                dispatch { session.place(peerID, callId, System.currentTimeMillis()) }
            } else {
                dispatch {
                    session.markEnded(peerID, CallEndReason.UNAVAILABLE)
                    emptyList()
                }
            }
        }
    }

    private fun onSignal(peerID: String, signal: CallSignal) {
        if (signal.action in PATH_ACTIONS) {
            val snapshot = session.snapshot()
            val current = monitor ?: return
            if (snapshot.peerID != peerID || snapshot.callId?.contentEquals(signal.callId) != true) return
            // Replies are sent from here, and a socket write must not run on the main thread.
            scope.launch(Dispatchers.IO) { current.onSignal(signal, SystemClock.elapsedRealtime()) }
        } else {
            dispatch { session.onRemote(peerID, signal, System.currentTimeMillis()) }
        }
    }

    private fun hangup() {
        connecting?.cancel()
        connectingPeer = null
        if (session.snapshot().phase == CallPhase.IDLE) {
            session.acknowledgeEnd()
            refresh()
            stopIfIdle()
        } else {
            dispatch { session.hangup(System.currentTimeMillis()) }
        }
    }

    /** Runs one session transition, sends its signals and follows the phase change. */
    private fun dispatch(transition: () -> List<CallSession.Effect>) {
        val before = session.snapshot()
        val effects = transition()
        val after = session.snapshot()
        effects.forEach { mesh.sendCallSignal(it.peerID, it.signal.encode()) }
        val changed = before.phase != after.phase || before.endReason != after.endReason ||
            before.onHold != after.onHold || before.reconnecting != after.reconnecting ||
            before.peerID != after.peerID
        if (!changed) return

        if (before.phase == CallPhase.IDLE && after.phase != CallPhase.IDLE) {
            ending?.cancel()
            muted = false
            speakerOn = false
            joinTelecom(checkNotNull(after.peerID), incoming = after.phase == CallPhase.INCOMING)
        }
        if (after.phase == CallPhase.ACTIVE && before.phase != CallPhase.ACTIVE) {
            if (before.phase == CallPhase.INCOMING) {
                telecom?.send(TelecomBridge.Command.Answer)
            } else {
                telecom?.send(TelecomBridge.Command.Active)
            }
            val transport = CallTransport(mesh, checkNotNull(after.peerID), after.remotePathMask)
            val failure = when {
                transport.available().isEmpty() -> CallEndReason.UNREACHABLE
                !startAudio(after, transport) -> CallEndReason.MICROPHONE
                else -> null
            }
            if (failure != null) {
                dispatch { session.fail(failure, System.currentTimeMillis()) }
                return
            }
        }
        if (before.phase != CallPhase.IDLE && after.phase == CallPhase.IDLE) {
            stopAudio()
            telecom?.send(TelecomBridge.Command.Disconnect)
            telecom = null
        }
        refresh()
        if (after.phase == CallPhase.IDLE && after.endReason != null) {
            ending?.cancel()
            ending = scope.launch {
                delay(END_BANNER_MS)
                session.acknowledgeEnd()
                refresh()
                stopIfIdle()
            }
        }
    }

    private fun joinTelecom(peerID: String, incoming: Boolean) {
        val bridge = TelecomBridge(applicationContext).also { telecom = it }
        scope.launch { bridge.run(peerID, nickname(peerID), incoming, this@CallService) }
    }

    private fun nickname(peerID: String): String = mesh.getPeerNicknames()[peerID]
        ?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
        .orEmpty()

    private fun refresh() {
        val snapshot = session.snapshot()
        val peer = snapshot.peerID ?: connectingPeer
        engine?.muted = muted || snapshot.onHold
        mutableState.value = MeshCallState(
            phase = snapshot.phase,
            peerID = peer,
            nickname = peer?.let(::nickname).orEmpty(),
            connectedAtMs = snapshot.connectedAtMs,
            endReason = snapshot.endReason,
            muted = muted,
            speakerOn = speakerOn,
            connecting = connectingPeer != null && snapshot.phase == CallPhase.IDLE,
            onHold = snapshot.onHold,
            reconnecting = snapshot.reconnecting
        )
        updateProximity()
        if (mutableState.value.visible) showForeground()
    }

    private fun showForeground() {
        val current = mutableState.value
        val ringing = current.phase == CallPhase.INCOMING
        val notification = CallNotifications.build(this, current.nickname, ringing, current.connectedAtMs)
        // The microphone type is only legal once the user has answered or placed the call.
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && current.phase == CallPhase.ACTIVE) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, CallNotifications.NOTIFICATION_ID, notification, type)
        } catch (error: Exception) {
            Log.w(TAG, "Cannot run in the foreground: ${error.message}")
            // Android rejects a call-style notification with neither a service nor a
            // full-screen intent, so this fallback can fail for an ongoing call.
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(CallNotifications.NOTIFICATION_ID, notification)
            }
        }
    }

    private fun stopIfIdle() {
        if (mutableState.value.visible || session.snapshot().phase != CallPhase.IDLE) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("WakelockTimeout")
    private fun startAudio(snapshot: CallSession.Snapshot, transport: CallTransport): Boolean {
        val peerID = snapshot.peerID ?: return false
        val callId = snapshot.callId ?: return false
        val codec = snapshot.codec ?: return false
        // The foreground type must include the microphone before recording starts.
        mutableState.value = mutableState.value.copy(phase = CallPhase.ACTIVE)
        showForeground()
        val audio = getSystemService(AudioManager::class.java)
        savedAudioMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
            .also { audio.requestAudioFocus(it) }
        cpuLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SignalLess:call")
            .also { it.acquire() }
        // shortcut: the bit rate follows the first path only; changing it needs a new encoder.
        val bitRate = if (transport.available().firstOrNull() == CallPath.WifiAware) {
            VoiceCodecs.OPUS_WIFI_BIT_RATE
        } else {
            VoiceCodecs.OPUS_BLE_BIT_RATE
        }
        val started = CallAudioEngine(
            callId,
            VoiceCodecs.create(codec, bitRate),
            send = { transport.send(it) },
            onFailure = { scope.launch { hangup() } }
        )
        if (!started.start()) return false
        audioPeer = peerID
        engine = started
        if (snapshot.supportsPathControl) {
            monitor = CallPathMonitor(transport, callId, SystemClock.elapsedRealtime())
        }
        route()
        return true
    }

    private fun stopAudio() {
        audioPeer = null
        monitor = null
        engine?.let { current ->
            engine = null
            // Stopping joins the audio threads, which must not block the main thread.
            CoroutineScope(Dispatchers.IO).launch { current.stop() }
        }
        val audio = getSystemService(AudioManager::class.java)
        focus?.let {
            audio.abandonAudioFocusRequest(it)
            audio.mode = savedAudioMode
        }
        focus = null
        cpuLock?.takeIf { it.isHeld }?.release()
        cpuLock = null
        proximityLock?.takeIf { it.isHeld }?.release()
        proximityLock = null
    }

    @Suppress("DEPRECATION")
    private fun route() {
        telecom?.send(TelecomBridge.Command.Speaker(speakerOn))
        // Covers devices where the Telecom registration failed.
        if (focus != null) getSystemService(AudioManager::class.java).isSpeakerphoneOn = speakerOn
    }

    /** Turns the screen off at the ear: during an earpiece call only. */
    @SuppressLint("WakelockTimeout")
    private fun updateProximity() {
        val wanted = session.snapshot().phase == CallPhase.ACTIVE && !speakerOn
        val power = getSystemService(PowerManager::class.java)
        if (wanted && proximityLock == null &&
            power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
        ) {
            proximityLock = power
                .newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "SignalLess:proximity")
                .also { it.acquire() }
        } else if (!wanted) {
            proximityLock?.takeIf { it.isHeld }?.release()
            proximityLock = null
        }
    }

    companion object {
        private const val TAG = "CallService"
        private const val SESSION_POLLS = 30
        private const val SESSION_POLL_MS = 500L
        private const val END_BANNER_MS = 2_200L

        const val ACTION_PLACE = "com.signalless.app.call.PLACE"
        const val ACTION_SIGNAL = "com.signalless.app.call.SIGNAL"
        const val ACTION_ANSWER = "com.signalless.app.call.ANSWER"
        const val ACTION_HANGUP = "com.signalless.app.call.HANGUP"
        const val ACTION_MUTE = "com.signalless.app.call.MUTE"
        const val ACTION_SPEAKER = "com.signalless.app.call.SPEAKER"
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_ENABLED = "enabled"

        private val PATH_ACTIONS =
            setOf(CallAction.PATH_PROBE, CallAction.PATH_SWITCH, CallAction.KEEPALIVE)

        private val mutableState = MutableStateFlow(MeshCallState())
        val state: StateFlow<MeshCallState> = mutableState.asStateFlow()

        @Volatile
        private var instance: CallService? = null

        fun intent(context: Context, action: String): Intent =
            Intent(context, CallService::class.java).setAction(action)

        /** Tells the running call to act. Does nothing when there is no call. */
        fun command(context: Context, action: String, enabled: Boolean = false) {
            // The service exists whenever a call is visible, so a plain start is allowed.
            if (!state.value.visible) return
            context.startService(intent(context, action).putExtra(EXTRA_ENABLED, enabled))
        }

        /** Starts an outgoing call. The caller must already hold the microphone permission. */
        fun place(context: Context, peerID: String) {
            ContextCompat.startForegroundService(
                context, intent(context, ACTION_PLACE).putExtra(EXTRA_PEER_ID, peerID)
            )
        }

        /** An authenticated `CALL_SIGNAL` from the mesh. */
        fun deliverSignal(context: Context, peerID: String, payload: ByteArray) {
            val signal = CallSignal.decode(payload) ?: return
            // Only an invite may start the service; late signals for no call are dropped.
            if (instance == null && signal.action != CallAction.INVITE) return
            try {
                ContextCompat.startForegroundService(
                    context,
                    intent(context, ACTION_SIGNAL)
                        .putExtra(EXTRA_PEER_ID, peerID)
                        .putExtra(EXTRA_PAYLOAD, payload)
                )
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Android refused to start the call service: ${error.message}")
            }
        }

        /** An authenticated `CALL_AUDIO` packet. Dropped unless it belongs to the live call. */
        fun deliverAudio(peerID: String, payload: ByteArray) {
            val service = instance ?: return
            if (service.audioPeer != peerID || service.engine?.onPacket(payload) != true) return
            val packet = CallAudioPacket.decode(payload) ?: return
            service.monitor?.onAudio(packet.seq, packet.frames.size, SystemClock.elapsedRealtime())
        }
    }
}
