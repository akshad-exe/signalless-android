package com.signalless.relay

import com.signalless.app.model.FragmentPayload
import com.signalless.app.protocol.MessageType
import com.signalless.app.protocol.SignalLessPacket
import com.signalless.app.util.AppConstants

/**
 * Fragments an outbound packet to a concrete BLE notification limit.
 *
 * The phone-side protocol uses 469-byte chunks and a 512-byte threshold, which
 * is correct for a 517-byte ATT MTU. A relay may face a smaller negotiated
 * notification size, so it computes the chunk size from the actual limit and
 * verifies every encoded fragment before returning it.
 */
class RelayFragmenter {
    fun fragment(packet: SignalLessPacket, maxNotificationSize: Int): List<SignalLessPacket>? {
        if (maxNotificationSize <= 0) return null
        val fullData = packet.toBinaryData(padding = false) ?: return null
        if (fullData.size <= maxNotificationSize) return listOf(packet)

        val fragmentId = FragmentPayload.generateFragmentID()
        var chunkSize = estimateChunkSize(packet, fragmentId, maxNotificationSize)
        if (chunkSize <= 0) return null

        repeat(MAX_SIZE_RETRIES) {
            val total = (fullData.size + chunkSize - 1) / chunkSize
            if (total <= 0 || total > AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID) {
                return null
            }

            val fragments = ArrayList<SignalLessPacket>(total)
            var offset = 0
            var index = 0
            while (offset < fullData.size) {
                val end = minOf(offset + chunkSize, fullData.size)
                val payload = FragmentPayload(
                    fragmentID = fragmentId,
                    index = index,
                    total = total,
                    originalType = packet.type,
                    data = fullData.copyOfRange(offset, end),
                ).encode()
                val fragment = SignalLessPacket(
                    version = packet.version,
                    type = MessageType.FRAGMENT.value,
                    ttl = packet.ttl,
                    senderID = packet.senderID.copyOf(),
                    recipientID = packet.recipientID?.copyOf(),
                    timestamp = packet.timestamp,
                    payload = payload,
                    signature = null,
                    route = packet.route?.map { it.copyOf() },
                )
                val encoded = fragment.toBinaryData(padding = false) ?: return null
                if (encoded.size > maxNotificationSize) {
                    val reduction = encoded.size - maxNotificationSize
                    chunkSize -= reduction.coerceAtLeast(1)
                    if (chunkSize <= 0) return null
                    return@repeat
                }
                fragments += fragment
                offset = end
                index += 1
            }

            if (fragments.size == total && fragments.all { fragment ->
                    (fragment.toBinaryData(padding = false)?.size ?: Int.MAX_VALUE) <= maxNotificationSize
                }
            ) {
                return fragments
            }
            chunkSize -= 1
            if (chunkSize <= 0) return null
        }
        return null
    }

    private fun estimateChunkSize(
        packet: SignalLessPacket,
        fragmentId: ByteArray,
        maxNotificationSize: Int,
    ): Int {
        val probe = SignalLessPacket(
            version = packet.version,
            type = MessageType.FRAGMENT.value,
            ttl = packet.ttl,
            senderID = packet.senderID,
            recipientID = packet.recipientID,
            timestamp = packet.timestamp,
            payload = FragmentPayload(
                fragmentID = fragmentId,
                index = 0,
                total = 1,
                originalType = packet.type,
                data = ByteArray(0),
            ).encode(),
            signature = null,
            route = packet.route,
        )
        val overhead = (probe.toBinaryData(padding = false)?.size ?: return 0) -
            FragmentPayload.HEADER_SIZE
        return maxNotificationSize - overhead
    }

    companion object {
        private const val MAX_SIZE_RETRIES = 24
    }
}
