package com.signalless.relay.ui

import com.signalless.app.util.SLog
import com.signalless.relay.RelayConfig
import com.signalless.relay.RelayEngine
import com.signalless.relay.RelayLink
import com.signalless.relay.RelayStats
import com.signalless.relay.TransportMode
import com.signalless.relay.identity.RelayIdentity
import com.signalless.relay.transport.RelayTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns relay lifecycle and publishes immutable [RelayUiState] snapshots.
 *
 * All start/stop/restart work is queued on one daemon thread, so two clicks
 * cannot race. Engine work runs on its own [Dispatchers.IO] scope per session;
 * the 500 ms refresh reads the engine's thread-safe accessors and never blocks
 * the UI thread.
 *
 * [transportFactory] is the test seam. Production passes a factory that builds
 * [com.signalless.relay.transport.HelperProcessTransport]; tests pass
 * [com.signalless.relay.transport.FakeRelayTransport].
 */
class RelayController(
    private val config: RelayConfig,
    private val identity: RelayIdentity,
    private val version: String,
    private val transportFactory: (CoroutineScope) -> RelayTransport,
    private val tickIntervalMs: Long = DEFAULT_TICK_INTERVAL_MS,
    private val eventLimit: Int = DEFAULT_EVENT_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val eventLogger: (String) -> Unit = {},
) {
    init {
        require(tickIntervalMs > 0) { "tickIntervalMs must be positive" }
        require(eventLimit > 0) { "eventLimit must be positive" }
    }

    private class Session(
        val engine: RelayEngine,
        val scope: CoroutineScope,
    )

    private val lock = Any()
    private val shuttingDown = AtomicBoolean(false)

    private val lifecycleDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "relay-ui-lifecycle").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val lifecycleScope = CoroutineScope(SupervisorJob() + lifecycleDispatcher)
    private val tickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var session: Session? = null
    private var phase = RelayPhase.STOPPED
    private var error: String? = null
    private var advertisingSinceMs: Long? = null
    private val events = ArrayDeque<RelayEvent>()
    private val firstSeen = LinkedHashMap<String, Long>()
    private var observedErrorCount = 0L
    private var links: List<RelayLinkView> = emptyList()
    private var stats: RelayStats = emptyRelayStats()

    private val effectiveMaxLinks: Int =
        if (config.transport == TransportMode.FAKE) config.maxPeers else minOf(config.maxPeers, HELPER_MAX_LINKS)

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<RelayUiState> = _state.asStateFlow()

    private val tickerJob: Job = tickerScope.launch {
        while (isActive) {
            delay(tickIntervalMs)
            try {
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SLog.e(TAG, "state refresh failed: ${failure.message}")
            }
        }
    }

    /** Starts a session unless one is already starting or running. Repeated calls are no-ops. */
    fun start() {
        synchronized(lock) {
            if (shuttingDown.get() || (phase != RelayPhase.STOPPED && phase != RelayPhase.FAILED)) {
                return
            }
            phase = RelayPhase.STARTING
            error = null
            advertisingSinceMs = null
            appendLocked(RelayEventKind.LIFECYCLE, "relay starting")
            publishLocked()
        }
        lifecycleScope.launch { startSession() }
    }

    /** Stops a starting or advertising session. Repeated calls are no-ops. */
    fun stop() {
        synchronized(lock) {
            if (shuttingDown.get() || (phase != RelayPhase.STARTING && phase != RelayPhase.ADVERTISING)) {
                return
            }
            phase = RelayPhase.STOPPING
            error = null
            appendLocked(RelayEventKind.LIFECYCLE, "stopping")
            publishLocked()
        }
        lifecycleScope.launch { stopSession() }
    }

    /**
     * Stops and starts in one serialized operation.
     *
     * Ignored while a transition is already in flight, which makes double
     * clicks harmless. The same identity object is reused, so the peer ID does
     * not change.
     */
    fun restart() {
        synchronized(lock) {
            if (shuttingDown.get() || phase == RelayPhase.STARTING || phase == RelayPhase.STOPPING) {
                return
            }
            appendLocked(RelayEventKind.LIFECYCLE, "restart requested")
            phase = RelayPhase.STOPPING
            error = null
            advertisingSinceMs = null
            publishLocked()
        }
        lifecycleScope.launch {
            stopSession(nextPhase = RelayPhase.STARTING)
            if (shuttingDown.get()) {
                return@launch
            }
            synchronized(lock) {
                if (phase != RelayPhase.STARTING) {
                    return@launch
                }
                appendLocked(RelayEventKind.LIFECYCLE, "relay starting")
                publishLocked()
            }
            startSession()
        }
    }

    /**
     * Stops the current session, stops the ticker, and releases the lifecycle
     * thread. Idempotent; safe to call from a window close handler.
     */
    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return
        }
        tickerJob.cancel()
        runBlocking {
            withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                lifecycleScope.launch { stopSession() }.join()
            }
        }
        lifecycleScope.cancel()
        lifecycleDispatcher.close()
        tickerScope.cancel()
    }

    private suspend fun startSession() {
        synchronized(lock) {
            if (shuttingDown.get() || phase != RelayPhase.STARTING) {
                return
            }
        }

        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = try {
            transportFactory(sessionScope)
        } catch (failure: Exception) {
            sessionScope.cancel()
            failStart(failure.message ?: failure.javaClass.simpleName)
            return
        }

        val engine = try {
            RelayEngine(
                myPeerId = identity.peerId,
                transport = transport,
                scope = sessionScope,
                maxLinks = effectiveMaxLinks,
                maxTtl = config.ttl.toUByte(),
                identity = identity,
                announcementNickname = config.nickname,
                announceIntervalMs = config.announceIntervalSeconds * 1_000L,
                notice = ::onEngineNotice,
            )
        } catch (failure: Exception) {
            sessionScope.cancel()
            failStart(failure.message ?: failure.javaClass.simpleName)
            return
        }

        val newSession = Session(engine = engine, scope = sessionScope)
        synchronized(lock) {
            if (shuttingDown.get() || phase != RelayPhase.STARTING) {
                sessionScope.cancel()
                return
            }
            session = newSession
            firstSeen.clear()
            observedErrorCount = 0L
            links = emptyList()
            stats = emptyRelayStats()
            publishLocked()
        }

        try {
            engine.start()
        } catch (cancelled: CancellationException) {
            cleanup(newSession)
            throw cancelled
        } catch (failure: Exception) {
            cleanup(newSession)
            synchronized(lock) {
                if (session === newSession) {
                    session = null
                }
                if (phase == RelayPhase.STARTING) {
                    phase = RelayPhase.FAILED
                    error = failure.message ?: failure.javaClass.simpleName
                    appendLocked(RelayEventKind.ERROR, "start failed: $error")
                }
                publishLocked()
            }
            return
        }

        synchronized(lock) {
            if (session === newSession && phase == RelayPhase.STARTING) {
                appendLocked(RelayEventKind.LIFECYCLE, "session started; waiting for the radio")
                publishLocked()
            }
        }
    }

    private suspend fun stopSession(nextPhase: RelayPhase = RelayPhase.STOPPED) {
        val current = synchronized(lock) {
            session.also { session = null }
        }
        if (current != null) {
            withContext(NonCancellable) {
                runCatching { current.engine.stop() }
            }
            current.scope.cancel()
        }

        synchronized(lock) {
            links = emptyList()
            firstSeen.clear()
            stats = emptyRelayStats()
            advertisingSinceMs = null
            observedErrorCount = 0L
            error = null
            phase = nextPhase
            if (nextPhase == RelayPhase.STOPPED && !shuttingDown.get()) {
                appendLocked(RelayEventKind.LIFECYCLE, "stopped")
            }
            publishLocked()
        }
    }

    private suspend fun failStart(message: String) {
        synchronized(lock) {
            if (phase == RelayPhase.STARTING) {
                phase = RelayPhase.FAILED
                error = message
                appendLocked(RelayEventKind.ERROR, "start failed: $message")
            }
            publishLocked()
        }
    }

    private suspend fun cleanup(failed: Session) {
        withContext(NonCancellable) {
            runCatching { failed.engine.stop() }
        }
        failed.scope.cancel()
    }

    /**
     * Refreshes engine-derived fields. Called on the ticker thread; all state
     * mutation is guarded by [lock].
     */
    private fun refresh() {
        val now = clock()
        synchronized(lock) {
            val current = session ?: return
            val ready = current.engine.isTransportReady()
            val currentStats = current.engine.stats()
            val snapshot = current.engine.linksSnapshot()
            val lastError = current.engine.lastTransportError()

            stats = currentStats
            updateLinksLocked(snapshot, now)
            updateErrorLocked(currentStats, lastError)

            if (phase == RelayPhase.STARTING && ready) {
                phase = RelayPhase.ADVERTISING
                advertisingSinceMs = now
                error = null
                appendLocked(RelayEventKind.READY, "advertising ready")
            }

            publishLocked()
        }
    }

    private fun updateLinksLocked(snapshot: List<RelayLink>, now: Long) {
        val currentIds = snapshot.mapTo(HashSet()) { it.id }
        val removed = firstSeen.keys.filter { it !in currentIds }
        for (id in removed) {
            firstSeen.remove(id)
        }

        for (link in snapshot) {
            if (firstSeen[link.id] == null) {
                firstSeen[link.id] = now
            }
        }

        links = snapshot.map { link ->
            RelayLinkView(
                id = link.id,
                peerId = link.peerId,
                mtu = link.mtu,
                notificationSize = link.maxNotificationSize,
                firstSeenMs = firstSeen[link.id] ?: now,
            )
        }
    }

    /**
     * Called on engine threads. Link lifecycle is appended here, not from the
     * 500 ms snapshot diff, so short-lived links are not lost between ticks.
     */
    private fun onEngineNotice(line: String) {
        val kind = when {
            line.startsWith("link up ") -> RelayEventKind.LINK_UP
            line.startsWith("link down ") -> RelayEventKind.LINK_DOWN
            else -> return
        }
        synchronized(lock) {
            if (shuttingDown.get()) {
                return
            }
            appendLocked(kind, line.replaceFirst("link=", ""))
            publishLocked()
        }
    }

    private fun updateErrorLocked(currentStats: RelayStats, lastError: String?) {
        if (currentStats.transportErrors <= observedErrorCount) {
            return
        }
        observedErrorCount = currentStats.transportErrors
        val message = lastError ?: "transport error"
        error = message
        appendLocked(RelayEventKind.ERROR, message)
    }

    private fun appendLocked(kind: RelayEventKind, text: String) {
        events.addLast(RelayEvent(atMs = clock(), kind = kind, text = text))
        while (events.size > eventLimit) {
            events.removeFirst()
        }
        eventLogger(text)
    }

    private fun buildState(): RelayUiState = RelayUiState(
        phase = phase,
        nickname = config.nickname,
        peerId = identity.peerId,
        transport = config.transport.name.lowercase(),
        version = version,
        maxLinks = effectiveMaxLinks,
        advertisingSinceMs = advertisingSinceMs,
        links = links.toList(),
        stats = stats,
        events = events.toList(),
        error = error,
        nowMs = clock(),
    )

    private fun publishLocked() {
        _state.value = buildState()
    }

    companion object {
        const val DEFAULT_TICK_INTERVAL_MS: Long = 500L
        const val DEFAULT_EVENT_LIMIT: Int = 200
        private const val SHUTDOWN_TIMEOUT_MS: Long = 3_000L
        private const val HELPER_MAX_LINKS = 7
        private const val TAG = "RelayController"
    }
}
