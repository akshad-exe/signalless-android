package com.signalless.relay

import com.signalless.app.mesh.FragmentManager
import com.signalless.app.protocol.MessageType
import com.signalless.app.protocol.SignalLessPacket

/**
 * Holds fragments until a complete original packet can be reconstructed.
 *
 * The portable [FragmentManager] enforces the same per-set and global memory
 * caps as the phones, including the 30-second timeout. The relay restores the
 * TTL from the fragment packet because [FragmentManager] deliberately returns a
 * TTL-0 packet so callers do not relay it accidentally.
 */
class RelayFragmentAssembler {
    private val manager = FragmentManager()

    fun accept(fragment: SignalLessPacket): SignalLessPacket? {
        if (fragment.type != MessageType.FRAGMENT.value) return null
        val packet = manager.handleFragment(fragment) ?: return null
        return packet.copy(ttl = fragment.ttl)
    }

    fun close() {
        manager.shutdown()
    }
}
