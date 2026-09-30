package com.signalless.app.services

import android.content.Context
import android.util.Log
import com.signalless.app.favorites.FavoriteControlMessage
import com.signalless.app.mesh.MeshService
import com.signalless.app.model.ReadReceipt
import com.signalless.app.nostr.NostrTransport
import com.signalless.app.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes messages between local mesh transports and Nostr, matching iOS behavior.
 */
class MessageRouter private constructor(
    private val context: Context,
    private var mesh: MeshService,
    private val nostr: NostrTransport
) {
    enum class RouteResult {
        MESH,
        NOSTR,
        QUEUED,
        DROPPED
    }

    private data class QueuedMessage(
        val content: String,
        val nickname: String,
        val messageID: String,
        val enqueuedAtMs: Long,
        val attempts: Int = 0
    )

    private data class ConversationRetry(
        val handshakeAttempts: Int,
        val nextHandshakeAttemptAtMs: Long
    )

    companion object {
        private const val TAG = "MessageRouter"
        private const val OUTBOX_TICK_MS = AppConstants.Router.OUTBOX_TICK_MS
        private const val OUTBOX_MESSAGE_TTL_MS = AppConstants.Router.OUTBOX_MESSAGE_TTL_MS
        private const val OUTBOX_MAX_PER_PEER = AppConstants.Router.OUTBOX_MAX_PER_PEER
        private val HANDSHAKE_RETRY_BACKOFF_MS = AppConstants.Router.HANDSHAKE_RETRY_BACKOFF_MS

        /**
         * How old an in-flight message must be before it is assumed abandoned by a dead router.
         * Comfortably longer than any real send attempt so a slow handshake is never stolen.
         */
        private const val STUCK_MESSAGE_AGE_MS = 120_000L

        /** Throttle for the stuck-sending scan; it runs on the 2s tick but need not. */
        private const val STUCK_SCAN_INTERVAL_MS = 30_000L

        @Volatile private var INSTANCE: MessageRouter? = null
        internal var disableSchedulerForTesting = false
        fun tryGetInstance(): MessageRouter? = INSTANCE
        fun getInstance(context: Context, mesh: MeshService): MessageRouter {
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val nostr = NostrTransport.getInstance(context)
                    MessageRouter(context.applicationContext, mesh, nostr).also { instance ->
                        // Register for favorites changes to flush outbox
                        try {
                            com.signalless.app.favorites.FavoritesPersistenceService.shared.addListener(instance.favoriteListener)
                        } catch (_: Exception) {}
                        INSTANCE = instance
                    }
                }
            }
            // Always update mesh reference and sync peer ID, and make sure the retry
            // scheduler is running (it is stopped together with MeshForegroundService).
            instance.mesh = mesh
            instance.nostr.senderPeerID = mesh.myPeerID
            instance.startOutboxScheduler()
            return instance
        }

        internal fun resetForTesting() {
            INSTANCE?.schedulerScope?.cancel()
            INSTANCE = null
        }
    }

    // Outbox: conversationID -> queued messages, oldest first
    private val outbox = ConcurrentHashMap<String, MutableList<QueuedMessage>>()

    // Per-conversation handshake retry state for queued messages
    private val retryState = ConcurrentHashMap<String, ConversationRetry>()

    private val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var schedulerJob: kotlinx.coroutines.Job? = null

    @Volatile private var lastStuckScanAtMs = Long.MIN_VALUE

    // Injectable clock for tests
    internal var clock: () -> Long = { System.currentTimeMillis() }

    // Called with the messageID of queued messages that expired or were evicted
    var onMessageExpired: ((String) -> Unit)? = null

    // Called with (messageID, reason) when a queued message can no longer be retried
    var onMessageFailed: ((String, String) -> Unit)? = null

    // Set once the outbox has been rehydrated from disk. Rehydration must not run again for
    // the lifetime of the process, or a restart would duplicate the in-memory queue.
    private val rehydrateLock = Any()
    @Volatile private var rehydrated = false

    private val outboxRepository: ConversationRepository?
        get() = ConversationRepository.tryGetInstance()

    init {
        startOutboxScheduler()
    }

    fun clearAll() {
        outbox.clear()
        retryState.clear()
        Log.d(TAG, "Cleared all MessageRouter outbox messages and retry state")
    }

    // Listener for favorites changes to flush outbox when npub mapping appears/changes
    private val favoriteListener = object: com.signalless.app.favorites.FavoritesChangeListener {

        override fun onFavoriteChanged(noiseKeyHex: String) {
            flushOutboxFor(noiseKeyHex)
            ContactIdentityResolver.peerIdForNoiseKeyHex(noiseKeyHex)?.let { flushOutboxFor(it) }
        }
        override fun onAllCleared() {
        }
    }

    fun sendPrivate(content: String, toPeerID: String, recipientNickname: String, messageID: String): RouteResult {
        val resolution = ContactDirectory.resolve(toPeerID)
        val conversationID = resolution.conversationID
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        val nostrTarget = resolution.noiseKeyHex ?: toPeerID

        if (com.signalless.app.nostr.GeohashAliasRegistry.contains(toPeerID)) {
            Log.d(TAG, "Routing PM via Nostr (geohash) to alias ${toPeerID.take(12)}… id=${messageID.take(8)}…")
            val recipientHex = com.signalless.app.nostr.GeohashAliasRegistry.get(toPeerID)
            if (recipientHex != null) {
                val sourceGeohash = com.signalless.app.nostr.GeohashConversationRegistry.get(toPeerID)
                nostr.sendPrivateMessageGeohash(content, recipientHex, messageID, sourceGeohash)
                return RouteResult.NOSTR
            }
            return RouteResult.DROPPED
        }

        val hasMesh = meshTarget?.let { isConnected(mesh, it) } == true
        if (meshTarget != null && isReady(mesh, meshTarget)) {
            Log.d(TAG, "Routing PM via mesh to ${meshTarget} msg_id=${messageID.take(8)}…")
            mesh.sendPrivateMessage(content, meshTarget, recipientNickname, messageID)
            return RouteResult.MESH
        } else if (canSendViaNostr(nostrTarget)) {
            Log.d(TAG, "Routing PM via Nostr to ${conversationID.take(32)}… msg_id=${messageID.take(8)}…")
            nostr.sendPrivateMessage(content, nostrTarget, recipientNickname, messageID)
            return RouteResult.NOSTR
        } else {
            Log.d(TAG, "Queued PM for ${conversationID} (no mesh, no Nostr mapping) msg_id=${messageID.take(8)}…")
            enqueue(conversationID, QueuedMessage(content, recipientNickname, messageID, clock()))
            Log.d(TAG, "Initiating noise handshake after queueing PM for ${conversationID.take(16)}…")
            if (hasMesh) meshTarget?.let { kickHandshake(conversationID, it, immediate = true) }
            return RouteResult.QUEUED
        }
    }

    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String) {
        val resolution = ContactDirectory.resolve(toPeerID)
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        val nostrTarget = resolution.noiseKeyHex ?: toPeerID
        if (meshTarget != null && isReady(mesh, meshTarget)) {
            Log.d(TAG, "Routing READ via mesh to ${meshTarget.take(8)}… id=${receipt.originalMessageID.take(8)}…")
            mesh.sendReadReceipt(receipt.originalMessageID, meshTarget, mesh.getPeerNicknames()[meshTarget] ?: mesh.myPeerID)
        } else {
            Log.d(TAG, "Routing READ via Nostr to ${toPeerID.take(8)}… id=${receipt.originalMessageID.take(8)}…")
            nostr.sendReadReceipt(receipt, nostrTarget)
        }
    }

    fun sendDeliveryAck(messageID: String, toPeerID: String) {
        // Mesh delivery ACKs are sent by the receiver automatically.
        // Only route via Nostr when mesh path isn't available or when this is a geohash alias
        if (com.signalless.app.nostr.GeohashAliasRegistry.contains(toPeerID)) {
            val recipientHex = com.signalless.app.nostr.GeohashAliasRegistry.get(toPeerID)
            if (recipientHex != null) {
                nostr.sendDeliveryAckGeohash(messageID, recipientHex, try { com.signalless.app.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)!! } catch (_: Exception) { return })
                return
            }
        }
        val resolution = ContactDirectory.resolve(toPeerID)
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        if (!(meshTarget != null && (mesh.getPeerInfo(meshTarget)?.isConnected == true) && mesh.hasEstablishedSession(meshTarget))) {
            nostr.sendDeliveryAck(messageID, resolution.noiseKeyHex ?: toPeerID)
        }
    }

    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean) {
        val resolution = ContactDirectory.resolve(toPeerID)
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        if (meshTarget != null && mesh.getPeerInfo(meshTarget)?.isConnected == true && mesh.hasEstablishedSession(meshTarget)) {
            val myNpub = try { com.signalless.app.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub } catch (_: Exception) { null }
            val content = FavoriteControlMessage.encode(isFavorite, myNpub)
            val nickname = mesh.getPeerNicknames()[meshTarget] ?: meshTarget
            mesh.sendPrivateMessage(content, meshTarget, nickname, null)
        } else {
            nostr.sendFavoriteNotification(resolution.noiseKeyHex ?: toPeerID, isFavorite)
        }
    }

    // Flush any queued messages for a specific peerID.
    // All outbox mutations happen under the router monitor so a concurrent enqueue cannot
    // be lost between the empty check and the map removal.
    @Synchronized
    fun flushOutboxFor(peerID: String) {
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        val queued = outbox[conversationID] ?: outbox[peerID] ?: return
        if (queued.isEmpty()) return
        Log.d(TAG, "Flushing outbox for ${conversationID.take(16)}… count=${queued.size}")
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val resolution = ContactDirectory.resolve(conversationID)
            val meshTarget = resolution.meshPeerID
            val nostrTarget = resolution.noiseKeyHex ?: conversationID
            if (meshTarget != null && isReady(mesh, meshTarget)) {
                mesh.sendPrivateMessage(entry.content, meshTarget, entry.nickname, entry.messageID)
                iterator.remove()
                deleteOutboxRow(entry.messageID)
            } else if (canSendViaNostr(nostrTarget)) {
                nostr.sendPrivateMessage(entry.content, nostrTarget, entry.nickname, entry.messageID)
                iterator.remove()
                deleteOutboxRow(entry.messageID)
            }
        }
        if (queued.isEmpty()) {
            outbox.remove(conversationID, queued)
            outbox.remove(peerID, queued)
            retryState.remove(conversationID)
            retryState.remove(peerID)
        }
    }

    // Flush everything (rarely used)
    fun flushAllOutbox() {
        outbox.keys.toList().forEach { flushOutboxFor(it) }
    }

    /**
     * Queue a message that has no reachable transport.
     *
     * The row is written to the outbox table before the in-memory list is touched, so a crash
     * between the two leaves a recoverable row rather than a lost message. If the write fails
     * the message is still queued in memory: a transient database error should not drop a send
     * that the current process can still retry.
     */
    @Synchronized
    private fun enqueue(conversationID: String, entry: QueuedMessage) {
        val repository = outboxRepository
        if (repository != null) {
            try {
                repository.enqueueOutbox(
                    messageId = entry.messageID,
                    conversationId = conversationID,
                    attempts = entry.attempts,
                    handshakeAttempts = retryState[conversationID]?.handshakeAttempts ?: 0,
                    nextAttemptAt = clock(),
                    enqueuedAt = entry.enqueuedAtMs,
                    expiresAt = entry.enqueuedAtMs + OUTBOX_MESSAGE_TTL_MS
                )
            } catch (e: Exception) {
                Log.w(TAG, "Outbox write failed for $conversationID; queueing in memory only: ${e.message}")
            }
        }
        val queue = outbox.getOrPut(conversationID) { mutableListOf() }
        queue.add(entry)
        while (queue.size > OUTBOX_MAX_PER_PEER) {
            val evicted = queue.removeAt(0)
            Log.w(TAG, "Outbox full for ${conversationID.take(16)}…; evicting oldest msg_id=${evicted.messageID.take(8)}…")
            failOutboxEntry(evicted, "outbox full")
        }
    }

    /**
     * Retire a message that will not be retried: drop its outbox row, tell the UI it expired,
     * and persist the failure so the sender never keeps showing it as in flight.
     */
    private fun failOutboxEntry(entry: QueuedMessage, reason: String) {
        try { outboxRepository?.deleteOutbox(entry.messageID) } catch (_: Exception) { }
        notifyExpired(entry.messageID)
        try { onMessageFailed?.invoke(entry.messageID, reason) } catch (_: Exception) { }
    }

    private fun notifyExpired(messageID: String) {
        try { onMessageExpired?.invoke(messageID) } catch (_: Exception) { }
    }

    private fun deleteOutboxRow(messageID: String) {
        try { outboxRepository?.deleteOutbox(messageID) } catch (_: Exception) { }
    }

    /**
     * Reload queued messages from disk after process death and abandon anything past retention.
     *
     * Runs once per process. Every restored row is matched to its persisted message so the body
     * comes from the encrypted history table rather than a second plaintext copy.
     */
    @Synchronized
    private fun rehydrateOutbox() {
        if (rehydrated) return
        synchronized(rehydrateLock) {
            if (rehydrated) return
            rehydrated = true
        }
        val repository = outboxRepository ?: run {
            // Database not up yet; a later tick will retry because the flag stays set only on success.
            synchronized(rehydrateLock) { rehydrated = false }
            return
        }
        val (live, expired) = try {
            repository.loadOutbox(clock())
        } catch (e: Exception) {
            Log.w(TAG, "Outbox rehydrate failed: ${e.message}")
            synchronized(rehydrateLock) { rehydrated = false }
            return
        }

        expired.forEach { row ->
            try { repository.deleteOutbox(row.messageId) } catch (_: Exception) { }
            notifyExpired(row.messageId)
            try { onMessageFailed?.invoke(row.messageId, "expired") } catch (_: Exception) { }
        }
        if (expired.isNotEmpty()) {
            Log.i(TAG, "Dropped ${expired.size} expired outbox row(s) on startup")
        }

        var restored = 0
        live.forEach { row ->
            val content = try {
                repository.loadMessageContent(row.messageId)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to read outbox payload for ${row.messageId.take(8)}…: ${e.message}")
                null
            }
            if (content == null) {
                // The message was pruned or never committed; drop the orphan row.
                try { repository.deleteOutbox(row.messageId) } catch (_: Exception) { }
                return@forEach
            }
            val queue = outbox.getOrPut(row.conversationId) { mutableListOf() }
            if (queue.none { it.messageID == row.messageId }) {
                queue.add(
                    QueuedMessage(
                        content = content,
                        nickname = "",
                        messageID = row.messageId,
                        enqueuedAtMs = row.enqueuedAt,
                        attempts = row.attempts
                    )
                )
                restored += 1
            }
            row.handshakeAttempts.takeIf { it > 0 }?.let {
                retryState[row.conversationId] = ConversationRetry(
                    handshakeAttempts = it,
                    nextHandshakeAttemptAtMs = 0L
                )
            }
        }
        if (restored > 0) {
            Log.i(TAG, "Rehydrated $restored queued message(s) from disk across ${outbox.size} conversation(s)")
        }
    }

    /**
     * Initiate a Noise handshake for a conversation with queued messages, applying
     * exponential backoff between attempts. [immediate] resets the backoff (peer just
     * appeared or a new message was queued). Kicks are suppressed while a previous
     * attempt is still inside its backoff window, so alias duplicates and frequent
     * peer-list updates cannot spam handshakes.
     */
    @Synchronized
    private fun kickHandshake(conversationID: String, meshTarget: String, immediate: Boolean) {
        val now = clock()
        val current = retryState[conversationID]
        if (current != null && now < current.nextHandshakeAttemptAtMs) return
        val attempts = if (immediate) 0 else (current?.handshakeAttempts ?: 0)
        try { mesh.initiateNoiseHandshake(meshTarget) } catch (_: Exception) { }
        val backoff = HANDSHAKE_RETRY_BACKOFF_MS[attempts.coerceAtMost(HANDSHAKE_RETRY_BACKOFF_MS.size - 1)]
        retryState[conversationID] = ConversationRetry(
            handshakeAttempts = attempts + 1,
            nextHandshakeAttemptAtMs = now + backoff
        )
        Log.d(TAG, "Handshake attempt ${attempts + 1} for ${conversationID.take(16)}…, next retry in ${backoff}ms")
    }

    @Synchronized
    private fun startOutboxScheduler() {
        if (disableSchedulerForTesting) return
        if (schedulerJob?.isActive == true) return
        schedulerJob = schedulerScope.launch {
            while (isActive) {
                delay(OUTBOX_TICK_MS)
                try { tickOutbox() } catch (e: Exception) {
                    Log.w(TAG, "Outbox scheduler tick failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Stop retrying while the mesh transports are down. Persistent network work must
     * follow the MeshForegroundService lifecycle; getInstance restarts the scheduler
     * and rebinds the mesh reference when the service comes back.
     */
    fun stopOutboxScheduler() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    internal val isSchedulerRunning: Boolean get() = schedulerJob?.isActive == true

    /**
     * One scheduler pass over the outbox: expire old entries, flush what can be sent,
     * and re-initiate handshakes (with backoff) for peers that are connected but have
     * no established session yet.
     */
    @Synchronized
    internal fun tickOutbox(nowMs: Long = clock()) {
        rehydrateOutbox()
        adoptStuckSendingMessages(nowMs)
        outbox.keys.toList().forEach { conversationID ->
            expireOldEntries(conversationID, nowMs)
            val queued = outbox[conversationID] ?: return@forEach
            if (queued.isEmpty()) return@forEach

            val resolution = ContactDirectory.resolve(conversationID)
            val meshTarget = resolution.meshPeerID

            if (meshTarget != null && isReady(mesh, meshTarget)) {
                flushOutboxFor(conversationID)
                return@forEach
            }
            if (canSendViaNostr(resolution.noiseKeyHex ?: conversationID)) {
                flushOutboxFor(conversationID)
                return@forEach
            }
            // Peer visible but no session: retry the handshake with backoff.
            if (meshTarget != null && isConnected(mesh, meshTarget)) {
                kickHandshake(conversationID, meshTarget, immediate = false)
            }
        }
    }

    private fun expireOldEntries(conversationID: String, nowMs: Long) {
        val queued = outbox[conversationID] ?: return
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.enqueuedAtMs > OUTBOX_MESSAGE_TTL_MS) {
                Log.w(TAG, "Expiring queued PM for ${conversationID.take(16)}… msg_id=${entry.messageID.take(8)}…")
                iterator.remove()
                failOutboxEntry(entry, "expired")
            }
        }
        if (queued.isEmpty()) {
            outbox.remove(conversationID, queued)
            retryState.remove(conversationID)
        }
    }

    /**
     * Rescue messages that a dead process left marked in-flight with no outbox row to retry them.
     *
     * A message written as Sending whose outbox row did not survive (or was never created) would
     * otherwise stay in flight forever: the UI renders a spinner that no code ever resolves. Each
     * such message is either re-queued for another attempt or failed outright, so the sender sees
     * a truthful state either way.
     */
    @Synchronized
    private fun adoptStuckSendingMessages(nowMs: Long) {
        val repository = outboxRepository ?: return
        if (nowMs - lastStuckScanAtMs < STUCK_SCAN_INTERVAL_MS) return
        lastStuckScanAtMs = nowMs
        val stuck = try {
            repository.findStuckSendingMessages(STUCK_MESSAGE_AGE_MS, nowMs)
        } catch (e: Exception) {
            Log.w(TAG, "Stuck-sending scan failed: ${e.message}")
            return
        }
        if (stuck.isEmpty()) return
        var requeued = 0
        var abandoned = 0
        stuck.forEach { messageId ->
            // Already tracked by the live queue: leave it to the normal retry path.
            if (isQueued(messageId)) return@forEach
            val conversationId = conversationIdFor(messageId)
            val content = try {
                repository.loadMessageContent(messageId)
            } catch (_: Exception) {
                null
            }
            if (content == null) return@forEach
            val resolution = ContactDirectory.resolve(conversationId)
            val meshTarget = resolution.meshPeerID
            val reachable = (meshTarget != null && isReady(mesh, meshTarget)) ||
                canSendViaNostr(resolution.noiseKeyHex ?: conversationId)
            if (reachable) {
                enqueue(conversationId, QueuedMessage(content, "", messageId, nowMs))
                requeued += 1
            } else {
                try { repository.deleteOutbox(messageId) } catch (_: Exception) { }
                try { onMessageFailed?.invoke(messageId, "interrupted before delivery") } catch (_: Exception) { }
                abandoned += 1
            }
        }
        if (requeued > 0 || abandoned > 0) {
            Log.i(TAG, "Recovered stuck sends: $requeued re-queued, $abandoned marked failed")
        }
    }

    private fun isQueued(messageID: String): Boolean {
        return outbox.values.any { queue -> queue.any { it.messageID == messageID } }
    }

    /** Best-effort conversation lookup for a message id; falls back to the id itself. */
    private fun conversationIdFor(messageID: String): String {
        return try {
            outboxRepository?.findConversationIdForMessage(messageID)
                ?: messageID
        } catch (_: Exception) {
            messageID
        }
    }

    private fun canSendViaNostr(peerID: String): Boolean {
        return try {
            val resolution = ContactDirectory.resolve(peerID)
            if (resolution.isMutualFavorite && resolution.nostrPubkey != null) return true
            val target = resolution.noiseKeyHex ?: peerID
            if (ContactIdentityResolver.isNoiseKeyHex(target)) {
                val noiseKey = ContactIdentityResolver.bytesFromHex(target) ?: return false
                val fav = com.signalless.app.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else if (ContactIdentityResolver.isMeshPeerId(target)) {
                val fav = com.signalless.app.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(target)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else {
                false
            }
        } catch (_: Exception) { false }
    }

    private fun isConnected(service: MeshService, peerID: String): Boolean {
        return try {
            service.getPeerInfo(peerID)?.isConnected == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isReady(service: MeshService, peerID: String): Boolean {
        return try {
            service.getPeerInfo(peerID)?.isConnected == true &&
                service.hasEstablishedSession(peerID)
        } catch (_: Exception) {
            false
        }
    }

    // Called when mesh peer list changes; attempt to flush any matching outbox entries
    fun onPeersUpdated(peers: List<String>) {
        peers.forEach { pid ->
            kickHandshakeIfPending(pid)
            flushOutboxFor(pid)
            val noiseHex = try {
                mesh.getPeerInfo(pid)?.noisePublicKey?.let { ContactIdentityResolver.noiseKeyHex(it) }
            } catch (_: Exception) { null }
            noiseHex?.let {
                kickHandshakeIfPending(it)
                flushOutboxFor(it)
            }
        }
    }

    // Called when a Noise session becomes established; flush both the mesh peerID and its noiseHex alias
    fun onSessionEstablished(peerID: String) {
        resetRetry(peerID)
        flushOutboxFor(peerID)
        val noiseHex = try {
            mesh.getPeerInfo(peerID)?.noisePublicKey?.let { ContactIdentityResolver.noiseKeyHex(it) }
        } catch (_: Exception) { null }
        noiseHex?.let {
            resetRetry(it)
            flushOutboxFor(it)
        }
    }

    /** Reset handshake backoff for a conversation whose session just came up. */
    private fun resetRetry(peerID: String) {
        retryState.remove(ContactDirectory.canonicalConversationId(peerID))
        retryState.remove(peerID)
    }

    /**
     * A peer (re)appeared: if we still owe them queued messages and there is no working
     * session yet, restart the handshake immediately instead of waiting for the backoff.
     */
    @Synchronized
    private fun kickHandshakeIfPending(peerID: String) {
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        val queued = outbox[conversationID] ?: outbox[peerID] ?: return
        if (queued.isEmpty()) return
        val resolution = ContactDirectory.resolve(conversationID)
        val meshTarget = resolution.meshPeerID ?: return
        if (isReady(mesh, meshTarget)) return
        if (!isConnected(mesh, meshTarget)) return
        Log.d(TAG, "Peer ${meshTarget.take(8)}… reappeared with ${queued.size} queued PM(s); re-initiating handshake")
        kickHandshake(conversationID, meshTarget, immediate = true)
    }
}
