package com.signalless.relay

import com.signalless.app.mesh.AnnouncementIdentityValidator
import com.signalless.app.protocol.MessageType
import com.signalless.app.protocol.SignalLessPacket
import com.signalless.app.protocol.SpecialRecipients
import com.signalless.app.sync.PacketIdUtil
import com.signalless.app.util.SLog
import com.signalless.app.util.toHexString
import com.signalless.relay.identity.RelayIdentity
import com.signalless.relay.transport.RelayTransport
import com.signalless.relay.transport.RelayTransportEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Protocol-level relay engine.
 *
 * The engine owns link admission, identity announcements, fragment reassembly,
 * TTL/dedup decisions, per-link fragmentation, and bounded store-and-forward.
 * Transports only move opaque bytes and report link lifecycle.
 */
class RelayEngine(
    myPeerId: String,
    private val transport: RelayTransport,
    private val scope: CoroutineScope,
    maxLinks: Int = DEFAULT_MAX_LINKS,
    dedupCapacity: Int = DEFAULT_DEDUP_CAPACITY,
    dedupWindowMs: Long = DEFAULT_DEDUP_WINDOW_MS,
    private val maxTtl: UByte = DEFAULT_MAX_TTL,
    private val identity: RelayIdentity? = null,
    private val announcementNickname: String = DEFAULT_NICKNAME,
    private val announceIntervalMs: Long = DEFAULT_ANNOUNCE_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fragmentSendDelayMs: Long = DEFAULT_FRAGMENT_DELAY_MS,
    private val pendingStore: PendingPacketStore = PendingPacketStore(),
    private val notice: (String) -> Unit = {},
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
    private val fragmenter = RelayFragmenter()
    private val assembler = RelayFragmentAssembler()

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var transportReady: Boolean = false

    @Volatile
    private var transportError: String? = null

    private var eventJob: Job? = null
    private var announceJob: Job? = null

    init {
        require(identity == null || identity.peerId == myPeerId) {
            "relay identity peer ID does not match myPeerId"
        }
        require(maxTtl > 0u.toUByte()) { "maxTtl must be positive" }
        require(announceIntervalMs > 0) { "announceIntervalMs must be positive" }
        require(fragmentSendDelayMs >= 0) { "fragmentSendDelayMs must not be negative" }
    }

    suspend fun start() {
        check(!running) { "relay engine is already running" }
        transportReady = false
        transportError = null
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
        if (identity != null) {
            announceJob = scope.launch {
                while (isActive) {
                    delay(announceIntervalMs)
                    if (running) announceToAllLinks()
                }
            }
        }
    }

    suspend fun stop() {
        running = false
        transportReady = false
        transportError = null
        announceJob?.cancelAndJoin()
        announceJob = null
        eventJob?.cancelAndJoin()
        eventJob = null
        assembler.close()
        transport.stop()
        links.clear()
    }

    fun linkCount(): Int = links.size()

    fun linksSnapshot(): List<RelayLink> = links.snapshot()

    fun stats(): RelayStats = counters.snapshot()

    /** True after the transport reported `Ready`; cleared by [stop]. */
    fun isTransportReady(): Boolean = transportReady

    /** Last transport error as `code: message`; cleared on start and stop. */
    fun lastTransportError(): String? = transportError

    private suspend fun handleEvent(event: RelayTransportEvent) {
        when (event) {
            is RelayTransportEvent.Ready -> {
                transportReady = true
                transportError = null
                SLog.i(
                    TAG,
                    "transport ready platform=${event.platform} maxLinks=${event.maxLinks} " +
                        "serviceData=${event.serviceDataSupported}",
                )
            }

            is RelayTransportEvent.LinkUp -> {
                val existing = links.link(event.linkId)
                if (existing == null && links.size() >= maxLinks) {
                    counters.overCapacityLinks.incrementAndGet()
                    SLog.w(
                        TAG,
                        "rejecting link ${event.linkId}: configured maxLinks=$maxLinks",
                    )
                    return
                }
                if (!links.add(event.linkId, event.mtu, event.maxNotificationSize)) {
                    counters.invalidLinks.incrementAndGet()
                    SLog.w(TAG, "ignored invalid link ${event.linkId}")
                    return
                }
                SLog.i(
                    TAG,
                    "link ${event.linkId} up mtu=${event.mtu} " +
                        "maxNotification=${event.maxNotificationSize}",
                )
                notice("link up link=${event.linkId} mtu=${event.mtu}")

                val link = links.link(event.linkId) ?: return
                if (identity != null) {
                    sendAnnouncementTo(link)
                }
                link.peerId?.let { flushPending(it) }
            }

            is RelayTransportEvent.LinkDown -> {
                links.remove(event.linkId)
                SLog.i(TAG, "link ${event.linkId} down reason=${event.reason}")
                notice("link down link=${event.linkId} reason=${event.reason}")
            }

            is RelayTransportEvent.Bytes -> handleFrame(event.linkId, event.data)

            is RelayTransportEvent.Error -> {
                counters.transportErrors.incrementAndGet()
                transportError = "${event.code}: ${event.message}"
                SLog.e(TAG, "transport error ${event.code}: ${event.message}")
                notice("transport error ${event.code}: ${event.message}")
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

        val previousPeer = links.peerFor(linkId)
        links.bindPeer(linkId, senderId)
        if (previousPeer != senderId) {
            flushPending(senderId)
        }

        if (packet.type == MessageType.ANNOUNCE.value && identity != null) {
            val verified = AnnouncementIdentityValidator.verify(packet, senderId, clock()) {
                    signature, signedData, publicKey ->
                identity.verify(signature, signedData, publicKey)
            }
            if (verified == null) {
                counters.announcementsRejected.incrementAndGet()
                SLog.w(TAG, "rejected an invalid ANNOUNCE")
                return
            }
        }

        if (isAddressedToMe(packet)) {
            counters.addressedToMe.incrementAndGet()
            return
        }

        if (packet.ttl == 0u.toUByte()) {
            counters.ttlExpired.incrementAndGet()
            return
        }

        if (packet.type == MessageType.FRAGMENT.value) {
            if (!dedup.firstSeen(packet)) {
                counters.duplicates.incrementAndGet()
                return
            }
            val reassembled = assembler.accept(packet)
            if (reassembled == null) {
                counters.fragmentsPending.incrementAndGet()
                return
            }
            counters.fragmentsAssembled.incrementAndGet()
            relayPacket(reassembled, linkId)
            return
        }

        relayPacket(packet, linkId)
    }

    private suspend fun relayPacket(packet: SignalLessPacket, ingressLinkId: String) {
        if (packet.ttl == 0u.toUByte()) {
            counters.ttlExpired.incrementAndGet()
            return
        }

        if (!dedup.firstSeen(packet)) {
            counters.duplicates.incrementAndGet()
            return
        }

        val relayTtl = minOf(packet.ttl, maxTtl)
        val relayed = packet.copy(ttl = (relayTtl - 1u).toUByte())
        val targets = targetsFor(relayed, ingressLinkId)
        if (targets.isEmpty()) {
            val recipient = relayed.recipientID
            if (recipient != null && !recipient.contentEquals(SpecialRecipients.BROADCAST)) {
                if (pendingStore.store(recipient.toHexString(), relayed)) {
                    counters.stored.incrementAndGet()
                } else {
                    counters.storeRejected.incrementAndGet()
                }
                counters.noRoute.incrementAndGet()
            } else {
                counters.noRoute.incrementAndGet()
            }
            return
        }

        for (target in targets) {
            if (sendPacketToTarget(relayed, target)) {
                counters.forwarded.incrementAndGet()
            }
        }
    }

    private suspend fun sendPacketToTarget(
        packet: SignalLessPacket,
        target: RelayLink,
        countAnnouncement: Boolean = false,
    ): Boolean {
        val encoded = packet.toBinaryData()
        if (encoded == null) {
            counters.encodeFailed.incrementAndGet()
            return false
        }

        if (encoded.size <= target.maxNotificationSize) {
            val result = transport.send(target.id, encoded)
            if (result.isSuccess) {
                counters.framesSent.incrementAndGet()
                if (countAnnouncement) counters.announcementsSent.incrementAndGet()
                return true
            }
            counters.sendFailed.incrementAndGet()
            return false
        }

        val fragments = fragmenter.fragment(packet, target.maxNotificationSize)
        if (fragments.isNullOrEmpty()) {
            counters.oversized.incrementAndGet()
            counters.fragmentSendFailed.incrementAndGet()
            SLog.w(
                TAG,
                "cannot fragment ${encoded.size}-byte packet for ${target.id}; " +
                    "maxNotification=${target.maxNotificationSize}",
            )
            return false
        }

        for ((index, fragment) in fragments.withIndex()) {
            val bytes = fragment.toBinaryData(padding = false)
            if (bytes == null || bytes.size > target.maxNotificationSize) {
                counters.fragmentSendFailed.incrementAndGet()
                return false
            }
            val result = transport.send(target.id, bytes)
            if (result.isFailure) {
                counters.sendFailed.incrementAndGet()
                return false
            }
            counters.framesSent.incrementAndGet()
            if (index < fragments.lastIndex && fragmentSendDelayMs > 0) {
                delay(fragmentSendDelayMs)
            }
        }
        if (countAnnouncement) counters.announcementsSent.incrementAndGet()
        return true
    }

    private suspend fun sendAnnouncementTo(link: RelayLink) {
        val currentIdentity = identity ?: return
        val announcement = currentIdentity.signedAnnouncement(
            nickname = announcementNickname,
            ttl = DEFAULT_ANNOUNCE_TTL,
            timestampMs = clock(),
        )
        sendPacketToTarget(announcement, link, countAnnouncement = true)
    }

    private suspend fun announceToAllLinks() {
        links.snapshot().forEach { link ->
            sendAnnouncementTo(link)
        }
    }

    private suspend fun flushPending(peerId: String) {
        val targets = links.linksForPeer(peerId)
        if (targets.isEmpty()) return
        val packets = pendingStore.take(peerId)
        if (packets.isEmpty()) return

        for (packet in packets) {
            for (target in targets) {
                if (sendPacketToTarget(packet, target)) {
                    counters.storeFlushed.incrementAndGet()
                }
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
        val fragmentsPending = AtomicLong()
        val fragmentsAssembled = AtomicLong()
        val fragmentSendFailed = AtomicLong()
        val stored = AtomicLong()
        val storeFlushed = AtomicLong()
        val storeRejected = AtomicLong()
        val announcementsSent = AtomicLong()
        val announcementsRejected = AtomicLong()
        val framesSent = AtomicLong()

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
            fragmentsPending = fragmentsPending.get(),
            fragmentsAssembled = fragmentsAssembled.get(),
            fragmentSendFailed = fragmentSendFailed.get(),
            stored = stored.get(),
            storeFlushed = storeFlushed.get(),
            storeRejected = storeRejected.get(),
            announcementsSent = announcementsSent.get(),
            announcementsRejected = announcementsRejected.get(),
            framesSent = framesSent.get(),
        )
    }

    companion object {
        private const val TAG = "RelayEngine"
        const val DEFAULT_MAX_LINKS: Int = 7
        val DEFAULT_MAX_TTL: UByte = 7u
        const val DEFAULT_DEDUP_CAPACITY: Int = 2_048
        const val DEFAULT_DEDUP_WINDOW_MS: Long = 60_000L
        const val DEFAULT_NICKNAME: String = "SignalLessRelay"
        const val DEFAULT_ANNOUNCE_INTERVAL_MS: Long = 60_000L
        const val DEFAULT_FRAGMENT_DELAY_MS: Long = 15L
        val DEFAULT_ANNOUNCE_TTL: UByte = 7u
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
    val fragmentsPending: Long = 0L,
    val fragmentsAssembled: Long = 0L,
    val fragmentSendFailed: Long = 0L,
    val stored: Long = 0L,
    val storeFlushed: Long = 0L,
    val storeRejected: Long = 0L,
    val announcementsSent: Long = 0L,
    val announcementsRejected: Long = 0L,
    val framesSent: Long = 0L,
)
