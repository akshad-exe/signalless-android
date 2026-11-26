package com.signalless.app.features.call

/**
 * Watches one call's connection and moves it to another path when it degrades.
 *
 * Every [INTERVAL_MS] it sends a keepalive on the current path and probes the others for their
 * round trip time. Loss comes from gaps in the received audio frame numbers. Each side chooses
 * its own sending path; `PATH_SWITCH` asks the peer to leave a bad one too. Call [tick] once a
 * second. Methods send on the caller's thread, so call them off the main thread.
 */
class CallPathMonitor(
    private val transport: CallTransport,
    private val callId: ByteArray,
    startedAtMs: Long
) {
    private val sentAt = mutableMapOf<Int, Long>()
    private val roundTrips = mutableMapOf<Int, Long>()
    private var lastHeard = startedAtMs
    private var heardAt = startedAtMs
    private var lastKeepalive = startedAtMs - INTERVAL_MS
    private var chosen = false

    private var nextSeq = -1
    private var expected = 0
    private var received = 0
    private var lossyTicks = 0
    private var reconnectingSince: Long? = null

    @Synchronized
    fun onAudio(seq: Int, frames: Int, nowMs: Long) {
        heard(nowMs)
        if (nextSeq < 0) nextSeq = seq
        val gap = (seq - nextSeq) and 0xFFFF
        received += frames
        // A gap in the upper half is a late packet filling an earlier hole.
        if (gap < 0x8000) {
            expected += gap + frames
            nextSeq = (seq + frames) and 0xFFFF
        }
    }

    @Synchronized
    fun onSignal(signal: CallSignal, nowMs: Long) {
        heard(nowMs)
        val bit = signal.pathMask
        when (signal.action) {
            CallAction.PATH_PROBE, CallAction.KEEPALIVE ->
                if (signal.flags and FLAG_REPLY == 0) {
                    transport.find(bit)?.let { send(it, signal.action, FLAG_REPLY) }
                } else {
                    sentAt[bit]?.let { roundTrips[bit] = nowMs - it }
                }
            CallAction.PATH_SWITCH -> transport.find(bit)?.let(transport::switchTo)
            else -> Unit
        }
    }

    /** Returns true while the call is reconnecting. */
    @Synchronized
    fun tick(nowMs: Long): Boolean {
        measureLoss()
        // Once, after the first probes had a tick to answer.
        if (!chosen && roundTrips.isNotEmpty()) {
            chosen = true
            fastest(transport.available())?.let(transport::switchTo)
        }
        val silent = nowMs - lastHeard >= SILENCE_MS
        if (silent || lossyTicks >= LOSSY_TICKS) {
            if (reconnectingSince == null) reconnectingSince = nowMs
            switchAway()
            lossyTicks = 0
            // Give the new path the full window before judging it.
            if (silent) lastHeard = nowMs - SILENCE_MS + RETRY_MS
        } else {
            val since = reconnectingSince
            if (since != null && lossyTicks == 0 && heardAt > since) reconnectingSince = null
        }
        if (nowMs - lastKeepalive >= INTERVAL_MS) {
            lastKeepalive = nowMs
            val current = transport.path
            transport.available().forEach { path ->
                val action = if (path == current) CallAction.KEEPALIVE else CallAction.PATH_PROBE
                sentAt[path.bit] = nowMs
                send(path, action, 0)
            }
        }
        return reconnectingSince != null
    }

    private fun heard(nowMs: Long) {
        heardAt = nowMs
        lastHeard = nowMs
    }

    private fun measureLoss() {
        // No audio is not loss (the peer may be muted); the silence timeout covers a dead path.
        val lost = (expected - received).coerceAtLeast(0)
        lossyTicks = if (lost * 100 > expected * LOSS_PERCENT) lossyTicks + 1 else 0
        expected = 0
        received = 0
    }

    private fun switchAway() {
        val current = transport.path
        val others = transport.available().filter { it != current }
        val next = fastest(others) ?: others.firstOrNull() ?: return
        transport.switchTo(next)
        send(next, CallAction.PATH_SWITCH, 0)
    }

    private fun fastest(paths: List<CallPath>): CallPath? = paths
        .filter { it.bit in roundTrips }
        .minByOrNull { roundTrips.getValue(it.bit) }

    private fun send(path: CallPath, action: CallAction, flags: Int) {
        transport.sendSignal(path, CallSignal(action, callId, flags = flags, pathMask = path.bit).encode())
    }

    companion object {
        const val FLAG_REPLY = 0x01
        const val INTERVAL_MS = 2_000L
        private const val SILENCE_MS = 5_000L
        private const val RETRY_MS = 3_000L
        private const val LOSS_PERCENT = 15
        private const val LOSSY_TICKS = 3
    }
}
