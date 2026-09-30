package com.signalless.app.nostr

import android.app.Application
import android.util.Log
import com.signalless.app.favorites.FavoriteControlMessage
import com.signalless.app.favorites.FavoritesPersistenceService
import com.signalless.app.model.SignalLessFilePacket
import com.signalless.app.model.SignalLessMessage
import com.signalless.app.model.DeliveryStatus
import com.signalless.app.model.NoisePayload
import com.signalless.app.model.NoisePayloadType
import com.signalless.app.model.PrivateMessagePacket
import com.signalless.app.protocol.SignalLessPacket
import com.signalless.app.services.ContactDirectory
import com.signalless.app.services.ContactIdentityResolver
import com.signalless.app.services.SeenMessageStore
import com.signalless.app.ui.ChatState
import com.signalless.app.ui.PrivateChatManager
import com.signalless.app.ui.PrivateMessageOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class NostrDirectMessageHandler(
    private val application: Application,
    private val state: ChatState,
    private val privateChatManager: PrivateChatManager,
    private val updateDeliveryStatus: (String, DeliveryStatus) -> Unit,
    private val scope: CoroutineScope,
    private val repo: GeohashRepository,
    private val dataManager: com.signalless.app.ui.DataManager,
    private val seenStoreProvider: () -> SeenMessageStore = {
        SeenMessageStore.getInstance(application)
    }
) {
    companion object { private const val TAG = "NostrDirectMessageHandler" }

    private val seenStore by lazy(seenStoreProvider)

    // Simple event deduplication
    private val processedIds = ArrayDeque<String>()
    private val seen = HashSet<String>()
    private val max = 2000

    private fun dedupe(id: String): Boolean {
        if (seen.contains(id)) return true
        seen.add(id)
        processedIds.addLast(id)
        if (processedIds.size > max) {
            val old = processedIds.removeFirst()
            seen.remove(old)
        }
        return false
    }

    fun onGiftWrap(giftWrap: NostrEvent, geohash: String, identity: NostrIdentity) {
        scope.launch {
            try {
                if (dedupe(giftWrap.id)) return@launch

                val messageAge = System.currentTimeMillis() / 1000 - giftWrap.createdAt
                if (messageAge > 173700) return@launch // 48 hours + 15 mins

                val decryptResult = NostrProtocol.decryptPrivateMessage(giftWrap, identity)
                if (decryptResult == null) {
                    Log.w(TAG, "Failed to decrypt Nostr message")
                    return@launch
                }

                val (content, rawSenderPubkey, rumorTimestamp) = decryptResult
                val senderPubkey = rawSenderPubkey.lowercase()

                // If sender is blocked for geohash contexts, drop any events from this pubkey
                // Applies to both geohash DMs (geohash != "") and account DMs (geohash == "")
                if (dataManager.isGeohashUserBlocked(senderPubkey)) return@launch
                if (!content.startsWith("SignalLess1:")) return@launch

                val base64Content = content.removePrefix("SignalLess1:")
                val packetData = base64URLDecode(base64Content) ?: return@launch
                val packet = SignalLessPacket.fromBinaryData(packetData) ?: return@launch

                if (packet.type != com.signalless.app.protocol.MessageType.NOISE_ENCRYPTED.value) return@launch

                val noisePayload = NoisePayload.decode(packet.payload) ?: return@launch
                val messageTimestamp = Date(rumorTimestamp * 1000L)
                val convKey = "nostr_${senderPubkey.take(16)}"
                repo.putNostrKeyMapping(convKey, senderPubkey)
                com.signalless.app.nostr.GeohashAliasRegistry.put(convKey, senderPubkey)
                if (geohash.isNotEmpty()) {
                    // Remember which geohash this conversation belongs to so we can subscribe on-demand
                    repo.setConversationGeohash(convKey, geohash)
                    GeohashConversationRegistry.set(convKey, geohash)
                }

                // Ensure sender appears in geohash people list even if they haven't posted publicly yet
                if (geohash.isNotEmpty()) {
                    // Cache a best-effort nickname and mark as participant
                    val cached = repo.getCachedNickname(senderPubkey)
                    if (cached == null) {
                        val base = repo.displayNameForNostrPubkeyUI(senderPubkey).substringBefore("#")
                        repo.cacheNickname(senderPubkey, base)
                    }
                    repo.updateParticipant(geohash, senderPubkey, messageTimestamp)
                }

                val senderNickname = repo.displayNameForNostrPubkeyUI(senderPubkey)
                val conversationID = ContactDirectory.canonicalConversationId(convKey)

                processNoisePayload(noisePayload, conversationID, senderNickname, messageTimestamp, senderPubkey, identity)

            } catch (e: Exception) {
                Log.e(TAG, "onGiftWrap error: ${e.message}")
            }
        }
    }

    private suspend fun processNoisePayload(
        payload: NoisePayload,
        conversationID: String,
        senderNickname: String,
        timestamp: Date,
        senderPubkey: String,
        recipientIdentity: NostrIdentity
    ) {
        when (payload.type) {
            NoisePayloadType.PRIVATE_MESSAGE -> {
                val pm = PrivateMessagePacket.decode(payload.data) ?: return
                val existingMessages = state.getPrivateChatsValue()[conversationID] ?: emptyList()
                if (existingMessages.any { it.id == pm.messageID }) return

                val favoriteControl = FavoriteControlMessage.parse(pm.content)
                if (favoriteControl != null) {
                    val admitted = handleFavoriteControl(
                        favoriteControl,
                        conversationID,
                        senderNickname,
                        timestamp,
                        senderPubkey
                    )
                    if (!admitted) return
                    if (!seenStore.hasDelivered(pm.messageID)) {
                        val nostrTransport = NostrTransport.getInstance(application)
                        nostrTransport.sendDeliveryAckGeohash(pm.messageID, senderPubkey, recipientIdentity)
                        seenStore.markDelivered(pm.messageID)
                    }
                    return
                }

                val message = SignalLessMessage(
                    id = pm.messageID,
                    sender = senderNickname,
                    content = pm.content,
                    timestamp = timestamp,
                    isRelay = false,
                    isPrivate = true,
                    recipientNickname = state.getNicknameValue(),
                    senderPeerID = conversationID,
                    senderNostrPubkey = senderPubkey,
                    deliveryStatus = DeliveryStatus.Delivered(to = state.getNicknameValue() ?: "Unknown", at = Date())
                )

                val isViewing = state.getSelectedPrivateChatPeerValue() == conversationID
                val suppressUnread = seenStore.hasBeenReadLocally(pm.messageID)

                val admitted = withContext(Dispatchers.Main) {
                    privateChatManager.handleIncomingPrivateMessageDurably(
                        message = message,
                        suppressUnread = suppressUnread,
                        origin = PrivateMessageOrigin.NOSTR
                    )
                }
                if (!admitted) return

                if (!seenStore.hasDelivered(pm.messageID)) {
                    val nostrTransport = NostrTransport.getInstance(application)
                    nostrTransport.sendDeliveryAckGeohash(pm.messageID, senderPubkey, recipientIdentity)
                    seenStore.markDelivered(pm.messageID)
                }

                if (isViewing && !suppressUnread) {
                    val nostrTransport = NostrTransport.getInstance(application)
                    nostrTransport.sendReadReceiptGeohash(pm.messageID, senderPubkey, recipientIdentity)
                    seenStore.markReadLocally(pm.messageID)
                    seenStore.markReadReceiptSent(pm.messageID)
                }
            }
            NoisePayloadType.DELIVERED -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                withContext(Dispatchers.Main) {
                    updateDeliveryStatus(
                        messageId,
                        DeliveryStatus.Delivered(conversationID, Date())
                    )
                }
            }
            NoisePayloadType.READ_RECEIPT -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                withContext(Dispatchers.Main) {
                    updateDeliveryStatus(
                        messageId,
                        DeliveryStatus.Read(conversationID, Date())
                    )
                }
            }
            NoisePayloadType.FILE_TRANSFER -> {
                // Properly handle encrypted file transfer
                val file = SignalLessFilePacket.decode(payload.data)
                if (file != null) {
                    val uniqueMsgId = java.util.UUID.randomUUID().toString().uppercase()
                    val savedPath = com.signalless.app.features.file.FileUtils.saveIncomingFile(application, file)
                    val message = SignalLessMessage(
                        id = uniqueMsgId,
                        sender = senderNickname,
                        content = savedPath,
                        type = com.signalless.app.features.file.FileUtils.messageTypeForMime(file.mimeType),
                        timestamp = timestamp,
                        isRelay = false,
                        isPrivate = true,
                        recipientNickname = state.getNicknameValue(),
                        senderPeerID = conversationID,
                        senderNostrPubkey = senderPubkey
                    )
                    Log.d(TAG, "📄 Saved Nostr encrypted incoming file to $savedPath (msgId=$uniqueMsgId)")
                    val admitted = withContext(Dispatchers.Main) {
                        privateChatManager.handleIncomingPrivateMessageDurably(
                            message = message,
                            suppressUnread = false,
                            origin = PrivateMessageOrigin.NOSTR
                        )
                    }
                    if (!admitted) {
                        com.signalless.app.features.file.FileUtils.deleteStoredMediaPaths(
                            application,
                            listOf(savedPath)
                        )
                    }
                } else {
                    Log.w(TAG, "Failed to decode Nostr file transfer from $conversationID")
                }
            }
            NoisePayloadType.VERIFY_CHALLENGE,
            NoisePayloadType.VERIFY_RESPONSE,
            NoisePayloadType.VOICE_FRAME,
            NoisePayloadType.PEER_STATE -> Unit // Peer state is bound to a live mesh Noise generation.
        }
    }

    private suspend fun handleFavoriteControl(
        control: FavoriteControlMessage,
        conversationID: String,
        senderNickname: String,
        timestamp: Date,
        senderPubkey: String
    ): Boolean {
        return try {
            val senderNpub = control.npub ?: ContactIdentityResolver.npubFromHex(senderPubkey)
            val noiseKey = senderNpub?.let { FavoritesPersistenceService.shared.findNoiseKey(it) }
                ?: FavoritesPersistenceService.shared.findNoiseKey(senderPubkey)

            if (noiseKey == null) {
                Log.w(TAG, "Favorite notification from Nostr sender without known Noise key: ${senderPubkey.take(16)}...")
                return false
            }

            FavoritesPersistenceService.shared.updatePeerFavoritedUs(noiseKey, control.isFavorite)
            senderNpub?.let { FavoritesPersistenceService.shared.updateNostrPublicKey(noiseKey, it) }
            val targetConversationID = ContactDirectory.canonicalConversationId(conversationID)

            val relationship = FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
            val displayName = relationship
                ?.peerNickname
                ?.takeUnless { it.equals("Unknown", ignoreCase = true) }
                ?: senderNickname
            val guidance = if (control.isFavorite) {
                if (relationship?.isFavorite == true) {
                    " - mutual! You can continue DMs via Nostr when out of mesh."
                } else {
                    " - favorite back to continue DMs later."
                }
            } else {
                ". DMs over Nostr will pause unless you both favorite again."
            }
            val action = if (control.isFavorite) "favorited" else "unfavorited"
            val systemMessage = SignalLessMessage(
                sender = "system",
                content = "$displayName $action you$guidance",
                timestamp = timestamp,
                isRelay = false,
                isPrivate = true,
                senderPeerID = targetConversationID
            )

            withContext(Dispatchers.Main) {
                privateChatManager.handleIncomingPrivateMessageDurably(
                    message = systemMessage,
                    suppressUnread = true,
                    origin = PrivateMessageOrigin.NOSTR
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle Nostr favorite notification: ${e.message}")
            false
        }
    }

    private fun base64URLDecode(input: String): ByteArray? {
        return try {
            val padded = input.replace("-", "+")
                .replace("_", "/")
                .let { str ->
                    val padding = (4 - str.length % 4) % 4
                    str + "=".repeat(padding)
                }
            android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64url: ${e.message}")
            null
        }
    }
}
