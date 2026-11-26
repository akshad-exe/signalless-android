package com.signalless.relay

import com.signalless.app.protocol.SignalLessPacket

/**
 * Bounded store-and-forward cache for unicast packets whose recipient has no
 * current link.
 *
 * The cache is bounded by both packet count and approximated byte size so a
 * burst of large media messages cannot consume the relay's heap. Entries expire
 * so a temporarily absent phone cannot cause unbounded growth.
 */
class PendingPacketStore(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val packet: SignalLessPacket,
        val storedAt: Long,
        val size: Int,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, MutableList<Entry>>()
    private var storedBytes: Int = 0

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(retentionMs > 0) { "retentionMs must be positive" }
    }

    /** Returns true when the packet was stored. Oversized packets are rejected. */
    fun store(peerId: String, packet: SignalLessPacket): Boolean {
        val now = clock()
        val size = packet.payload.size + APPROXIMATE_OVERHEAD_BYTES
        if (size > maxBytes) return false

        synchronized(lock) {
            pruneLocked(now)
            val queue = entries.getOrPut(peerId) { mutableListOf() }
            queue += Entry(packet, now, size)
            storedBytes += size
            while (totalLocked() > capacity || storedBytes > maxBytes) {
                val oldestPeer = entries.entries.firstOrNull() ?: break
                if (oldestPeer.value.isEmpty()) {
                    entries.remove(oldestPeer.key)
                } else {
                    val removed = oldestPeer.value.removeAt(0)
                    storedBytes -= removed.size
                    if (oldestPeer.value.isEmpty()) {
                        entries.remove(oldestPeer.key)
                    }
                }
            }
        }
        return true
    }

    fun take(peerId: String): List<SignalLessPacket> {
        val now = clock()
        return synchronized(lock) {
            pruneLocked(now)
            entries.remove(peerId)?.let { queue ->
                storedBytes -= queue.sumOf { it.size }
                queue.map { it.packet }
            } ?: emptyList()
        }
    }

    fun size(): Int = synchronized(lock) {
        pruneLocked(clock())
        totalLocked()
    }

    fun bytes(): Int = synchronized(lock) {
        pruneLocked(clock())
        storedBytes
    }

    fun peerCount(): Int = synchronized(lock) {
        pruneLocked(clock())
        entries.size
    }

    private fun pruneLocked(now: Long) {
        val cutoff = now - retentionMs
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val queue = iterator.next().value
            queue.removeAll { entry ->
                val expired = entry.storedAt < cutoff
                if (expired) storedBytes -= entry.size
                expired
            }
            if (queue.isEmpty()) iterator.remove()
        }
    }

    private fun totalLocked(): Int = entries.values.sumOf { it.size }

    companion object {
        const val DEFAULT_CAPACITY: Int = 256
        const val DEFAULT_RETENTION_MS: Long = 5 * 60_000L
        const val DEFAULT_MAX_BYTES: Int = 4 * 1024 * 1024
        private const val APPROXIMATE_OVERHEAD_BYTES: Int = 160
    }
}