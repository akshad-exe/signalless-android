package com.signalless.relay

import com.signalless.app.protocol.SignalLessPacket
import com.signalless.app.protocol.SpecialRecipients
import com.signalless.app.sync.PacketIdUtil
import com.signalless.app.util.SLog
import com.signalless.app.util.toHexString
import com.signalless.relay.transport.RelayTransport
import com.signalless.relay.transport.RelayTransportEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Protocol-level relay engine.
 *
 * Phase 1 forwards complete packets and fragments opaquely. It deliberately
 * does not reassemble fragments or generate ANNOUNCE packets yet; those belong
 * to the identity and fragmentation phases. The engine is independent of the
 * concrete transport and is therefore testable with [FakeRelayTransport].
 */
class RelayEngine(
    myPeerId: String,
    private val transport: RelayTransport,
    private val scope: CoroutineScope,
    maxLinks: Int = DEFAULT_MAX_LINKS,
    dedupCapacity: Int = DEFAULT_DEDUP_CAPACITY,
    dedupWindowMs: Long = DEFAULT_DEDUP_WINDOW_MS,
) {
    private val myPeerId: String = requireNotNull(RelayLinkRegistry.normalizePeerId(myPeerId)) {
        "myPeerId must be 16 hex characters"
    }
    private val links = RelayLinkRegistry()
    private val maxLinks = maxLinks.also {
        require(it >= 1) { "maxLinks must be positive" }
    }
    private val dedup = PacketDeduplicator(dedupCapacity, dedupWindowMs)
    private val counters = Counters()

    @Volatile
    private var running: Boolean = false

    private var eventJob: Job? = null

    suspend fun start() {
        check(!running) { "relay engine is already running" }
        transport.start().getOrThrow()
        running = true
        eventJob = scope.launch {
            try {
                transport.events.collect(::handleEvent)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SLog.e(TAG, "transport event stream failed: ${error.message}")
                counters.transportErrors.incrementAndGet()
            }
        }
    }

    suspend fun stop() {
        if (!running && eventJob == null) return
        running = false
        eventJob?.cancelAndJoin()
        eventJob = null
        transport.stop()
        links.clear()
    }

    fun linkCount(): Int = links.size()

    fun linksSnapshot(): List<RelayLink> = links.snapshot()

    fun stats(): RelayStats = counters.snapshot()

    private suspend fun handleEvent(event: RelayTransportEvent) {
        when (event) {
            is RelayTransportEvent.Ready -> {
                SLog.i(
                    TAG,
                    "transport ready platform=${event.platform} maxLinks=${event.maxLinks} " +
                        "serviceData=${event.serviceDataSupported}",
                )
            }

            is RelayTransportEvent.LinkUp -> {
                if (!links.add(event.linkId, event.mtu, event.maxNotificationSize)) {
                    counters.invalidLinks.incrementAndGet()
                    SLog.w(TAG, "ignored invalid link ${event.linkId}")
                    return
                }
                if (links.size() > maxLinks) {
                    counters.overCapacityLinks.incrementAndGet()
                    SLog.w(
                        TAG,
                        "link ${event.linkId} exceeds configured maxLinks=$maxLinks; " +
                            "the transport must enforce admission",
                    )
                }
                SLog.i(
                    TAG,
                    "link ${event.linkId} up mtu=${event.mtu} " +
                        "maxNotification=${event.maxNotificationSize}",
                )
            }

            is RelayTransportEvent.LinkDown -> {
                links.remove(event.linkId)
                SLog.i(TAG, "link ${event.linkId} down reason=${event.reason}")
            }

            is RelayTransportEvent.Bytes -> handleFrame(event.linkId, event.data)

            is RelayTransportEvent.Error -> {
                counters.transportErrors.incrementAndGet()
                SLog.e(TAG, "transport error ${event.code}: ${event.message}")
            }
        }
    }

    private suspend fun handleFrame(linkId: String, data: ByteArray) {
        counters.received.incrementAndGet()

        if (links.link(linkId) == null) {
            counters.unknownLinks.incrementAndGet()
            return
        }

        val packet = SignalLessPacket.fromBinaryData(data)
        if (packet == null) {
            counters.malformed.incrementAndGet()
            return
        }

        val senderId = packet.senderID.toHexString()
        if (senderId == myPeerId) {
            counters.ownPackets.incrementAndGet()
            return
        }
        links.bindPeer(linkId, senderId)

        if (isAddressedToMe(packet)) {
            counters.addressedToMe.incrementAndGet()
            return
        }

        if (packet.ttl == 0u.toUByte()) {
            counters.ttlExpired.incrementAndGet()
            return
        }

        if (!dedup.firstSeen(packet)) {
            counters.duplicates.incrementAndGet()
            return
        }

        val relayed = packet.copy(ttl = (packet.ttl - 1u).toUByte())
        val targets = targetsFor(packet, linkId)
        if (targets.isEmpty()) {
            counters.noRoute.incrementAndGet()
            return
        }

        val encoded = relayed.toBinaryData()
        if (encoded == null) {
            counters.encodeFailed.incrementAndGet()
            return
        }

        for (target in targets) {
            if (encoded.size > target.maxNotificationSize) {
                counters.oversized.incrementAndGet()
                SLog.w(
                    TAG,
                    "dropping ${encoded.size}-byte packet for ${target.id}; " +
                        "maxNotification=${target.maxNotificationSize}",
                )
                continue
            }

            val result = transport.send(target.id, encoded)
            if (result.isSuccess) {
                counters.forwarded.incrementAndGet()
            } else {
                counters.sendFailed.incrementAndGet()
            }
        }
    }

    private fun targetsFor(packet: SignalLessPacket, ingressLinkId: String): List<RelayLink> {
        val recipient = packet.recipientID
        if (recipient == null || recipient.contentEquals(SpecialRecipients.BROADCAST)) {
            return links.allExcept(ingressLinkId)
        }
        return links.linksForPeer(recipient.toHexString())
            .filter { it.id != ingressLinkId }
    }

    private fun isAddressedToMe(packet: SignalLessPacket): Boolean {
        val recipient = packet.recipientID ?: return false
        if (recipient.contentEquals(SpecialRecipients.BROADCAST)) return false
        return recipient.toHexString() == myPeerId
    }

    private class PacketDeduplicator(
        private val capacity: Int,
        private val windowMs: Long,
    ) {
        private val lock = Any()
        private val seen = LinkedHashMap<String, Long>()

        fun firstSeen(packet: SignalLessPacket, nowMs: Long = System.currentTimeMillis()): Boolean {
            val id = PacketIdUtil.computeIdHex(packet)
            synchronized(lock) {
                prune(nowMs)
                if (seen.containsKey(id)) return false
                seen[id] = nowMs
                while (seen.size > capacity) {
                    val oldest = seen.entries.iterator()
                    if (oldest.hasNext()) {
                        oldest.next()
                        oldest.remove()
                    }
                }
                return true
            }
        }

        private fun prune(nowMs: Long) {
            val cutoff = nowMs - windowMs
            val iterator = seen.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value < cutoff) {
                    iterator.remove()
                } else {
                    break
                }
            }
        }
    }

    private class Counters {
        val received = AtomicLong()
        val forwarded = AtomicLong()
        val malformed = AtomicLong()
        val duplicates = AtomicLong()
        val ttlExpired = AtomicLong()
        val addressedToMe = AtomicLong()
        val ownPackets = AtomicLong()
        val noRoute = AtomicLong()
        val oversized = AtomicLong()
        val encodeFailed = AtomicLong()
        val sendFailed = AtomicLong()
        val unknownLinks = AtomicLong()
        val invalidLinks = AtomicLong()
        val overCapacityLinks = AtomicLong()
        val transportErrors = AtomicLong()

        fun snapshot(): RelayStats = RelayStats(
            received = received.get(),
            forwarded = forwarded.get(),
            malformed = malformed.get(),
            duplicates = duplicates.get(),
            ttlExpired = ttlExpired.get(),
            addressedToMe = addressedToMe.get(),
            ownPackets = ownPackets.get(),
            noRoute = noRoute.get(),
            oversized = oversized.get(),
            encodeFailed = encodeFailed.get(),
            sendFailed = sendFailed.get(),
            unknownLinks = unknownLinks.get(),
            invalidLinks = invalidLinks.get(),
            overCapacityLinks = overCapacityLinks.get(),
            transportErrors = transportErrors.get(),
        )
    }

    companion object {
        private const val TAG = "RelayEngine"
        const val DEFAULT_MAX_LINKS: Int = 7
        const val DEFAULT_DEDUP_CAPACITY: Int = 2_048
        const val DEFAULT_DEDUP_WINDOW_MS: Long = 60_000L
    }
}

data class RelayStats(
    val received: Long,
    val forwarded: Long,
    val malformed: Long,
    val duplicates: Long,
    val ttlExpired: Long,
    val addressedToMe: Long,
    val ownPackets: Long,
    val noRoute: Long,
    val oversized: Long,
    val encodeFailed: Long,
    val sendFailed: Long,
    val unknownLinks: Long,
    val invalidLinks: Long,
    val overCapacityLinks: Long,
    val transportErrors: Long,
)
