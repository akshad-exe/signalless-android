package com.signalless.app.features.call

/**
 * Holds encrypted call signals that arrive while no screen is attached, then
 * releases them in order when a delegate returns. A live delegate receives the
 * signal immediately. Signals older than a ring are discarded.
 */
class CallSignalRouter(
    private val maxAgeMs: Long = CallSession.RING_TIMEOUT_MS,
    private val maxSignals: Int = 32
) {
    private class Held(
        val peerID: String,
        val payload: ByteArray,
        val receivedAtMs: Long
    )

    private val held = ArrayDeque<Held>()
    private val lock = Any()
    private var attached: CallSignalSink? = null

    /**
     * Returns the action when the signal was queued for a detached screen.
     * A live screen consumes the signal and this returns null.
     */
    fun deliver(peerID: String, payload: ByteArray, nowMs: Long): CallAction? {
        synchronized(lock) {
            val sink = attached
            if (sink != null) {
                sink.didReceiveCallSignal(peerID, payload, nowMs)
                return null
            }
            val signal = CallSignal.decode(payload) ?: return null
            if (peerID.isEmpty()) return null
            dropExpired(nowMs)
            while (held.size >= maxSignals) held.removeFirst()
            held.addLast(Held(peerID, payload.copyOf(), nowMs))
            return signal.action
        }
    }

    /**
     * Switches the live sink. The returned peer ids cover signals just delivered
     * and signals that aged out, so a ringing notification can be cleared.
     */
    fun onDelegateChanged(delegate: CallSignalSink?, nowMs: Long): List<String> {
        synchronized(lock) {
            attached = delegate
            val expired = dropExpired(nowMs)
            if (delegate == null || held.isEmpty()) return expired
            val pending = held.toList()
            held.clear()
            pending.forEach { item ->
                delegate.didReceiveCallSignal(item.peerID, item.payload, item.receivedAtMs)
            }
            return (expired + pending.map { it.peerID }).distinct()
        }
    }

    private fun dropExpired(nowMs: Long): List<String> {
        val removed = mutableListOf<String>()
        while (held.isNotEmpty() && nowMs - held.first().receivedAtMs >= maxAgeMs) {
            removed.add(held.removeFirst().peerID)
        }
        return removed
    }
}

fun interface CallSignalSink {
    fun didReceiveCallSignal(peerID: String, payload: ByteArray, receivedAtMs: Long)
}
