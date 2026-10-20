package com.signalless.relay

/**
 * One connected phone link.
 *
 * [peerId] is learned from the packet sender field, not from a MAC address or
 * an advertisement hint. A link can exist before its peer ID is known.
 */
data class RelayLink(
    val id: String,
    val mtu: Int,
    val maxNotificationSize: Int,
    val peerId: String? = null,
)

/**
 * Thread-safe link and peer mapping used by [RelayEngine].
 *
 * Link IDs are opaque connection generations. A phone reconnect gets a new
 * ID; the peer ID is rebound on the new link.
 */
class RelayLinkRegistry {
    private val lock = Any()
    private val links = LinkedHashMap<String, RelayLink>()

    fun add(linkId: String, mtu: Int, maxNotificationSize: Int): Boolean {
        if (linkId.isBlank() || mtu < MIN_MTU || maxNotificationSize !in 1..mtu) {
            return false
        }
        synchronized(lock) {
            val previous = links[linkId]
            links[linkId] = RelayLink(
                id = linkId,
                mtu = mtu,
                maxNotificationSize = maxNotificationSize,
                peerId = previous?.peerId,
            )
        }
        return true
    }

    fun remove(linkId: String): RelayLink? = synchronized(lock) { links.remove(linkId) }

    fun bindPeer(linkId: String, peerId: String): Boolean {
        val normalized = normalizePeerId(peerId) ?: return false
        synchronized(lock) {
            val link = links[linkId] ?: return false
            links[linkId] = link.copy(peerId = normalized)
        }
        return true
    }

    fun peerFor(linkId: String): String? = synchronized(lock) { links[linkId]?.peerId }

    fun link(linkId: String): RelayLink? = synchronized(lock) { links[linkId] }

    fun snapshot(): List<RelayLink> = synchronized(lock) { links.values.toList() }

    fun linksForPeer(peerId: String): List<RelayLink> {
        val normalized = normalizePeerId(peerId) ?: return emptyList()
        return synchronized(lock) {
            links.values.filter { it.peerId == normalized }
        }
    }

    fun allExcept(linkId: String): List<RelayLink> = synchronized(lock) {
        links.values.filter { it.id != linkId }
    }

    fun size(): Int = synchronized(lock) { links.size }

    fun clear() {
        synchronized(lock) { links.clear() }
    }

    companion object {
        const val MIN_MTU: Int = 23
        private const val PEER_ID_HEX_CHARS = 16
        private val PEER_ID_PATTERN = Regex("^[0-9a-f]{$PEER_ID_HEX_CHARS}$")

        fun normalizePeerId(peerId: String): String? =
            peerId.lowercase().takeIf { PEER_ID_PATTERN.matches(it) }
    }
}
