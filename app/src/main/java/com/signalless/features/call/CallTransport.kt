package com.signalless.app.features.call

import com.signalless.app.model.NoisePayloadType
import com.signalless.app.protocol.MessageType
import com.signalless.app.protocol.SignalLessPacket

/** What the call transport needs from the mesh. Sends never fall back to flooding. */
interface CallMeshLink {
    /** True when the peer has a Noise session over a direct Wi-Fi Aware link. */
    fun isWifiAwareDirect(peerID: String): Boolean

    /** True when the peer is a direct BLE neighbour. */
    fun isBleDirect(peerID: String): Boolean

    /** True when there is a Noise session with the peer over BLE. */
    fun hasBleSession(peerID: String): Boolean

    /** Known shortest mesh path, `[me, ..., peer]`, or null. */
    fun meshPath(peerID: String): List<String>?

    fun sendWifiAwareCall(peerID: String, type: NoisePayloadType, payload: ByteArray): Boolean

    /** Sends to the first of [hops], or to the peer itself when [hops] is empty. */
    fun sendBleCall(
        peerID: String,
        hops: List<String>,
        type: NoisePayloadType,
        payload: ByteArray
    ): Boolean
}

/** [bit] is the path's flag in a call signal's path mask. */
sealed class CallPath(val bit: Int) {
    object WifiAware : CallPath(CallSignal.PATH_WIFI_AWARE)
    object DirectBle : CallPath(CallSignal.PATH_DIRECT_BLE)

    /** [hops] are the intermediate peers, in order. */
    data class RoutedBle(val hops: List<String>) : CallPath(CallSignal.PATH_ROUTED_BLE)
}

/** Usable paths to a peer, best first: Wi-Fi Aware, direct BLE, routed BLE. */
class PathSelector(private val link: CallMeshLink) {
    fun available(peerID: String, remotePathMask: Int): List<CallPath> = buildList {
        if (remotePathMask and CallSignal.PATH_WIFI_AWARE != 0 && link.isWifiAwareDirect(peerID)) {
            add(CallPath.WifiAware)
        }
        if (!link.hasBleSession(peerID)) return@buildList
        if (remotePathMask and CallSignal.PATH_DIRECT_BLE != 0 && link.isBleDirect(peerID)) {
            add(CallPath.DirectBle)
        }
        if (remotePathMask and CallSignal.PATH_ROUTED_BLE != 0) routed(peerID)?.let(::add)
    }

    private fun routed(peerID: String): CallPath.RoutedBle? {
        val path = link.meshPath(peerID) ?: return null
        if (path.size < 3) return null
        val hops = path.subList(1, path.size - 1).toList()
        if (hops.size > MAX_INTERMEDIATES || !link.isBleDirect(hops.first())) return null
        return CallPath.RoutedBle(hops)
    }

    companion object {
        /** Three hops at most; further than that the delay makes a call unusable. */
        const val MAX_INTERMEDIATES = 2
    }
}

/** Sends one call's audio, staying on a path until it stops accepting packets or is replaced. */
class CallTransport(
    private val link: CallMeshLink,
    private val peerID: String,
    private val remotePathMask: Int
) {
    private val selector = PathSelector(link)

    @Volatile
    var path: CallPath? = null
        private set

    fun available(): List<CallPath> = selector.available(peerID, remotePathMask)

    fun find(bit: Int): CallPath? = available().firstOrNull { it.bit == bit }

    fun switchTo(next: CallPath) {
        path = next
    }

    fun send(payload: ByteArray): Boolean {
        path?.let { if (send(it, NoisePayloadType.CALL_AUDIO, payload)) return true }
        path = available().firstOrNull { send(it, NoisePayloadType.CALL_AUDIO, payload) }
        return path != null
    }

    fun sendSignal(path: CallPath, payload: ByteArray): Boolean =
        send(path, NoisePayloadType.CALL_SIGNAL, payload)

    private fun send(path: CallPath, type: NoisePayloadType, payload: ByteArray): Boolean = when (path) {
        CallPath.WifiAware -> link.sendWifiAwareCall(peerID, type, payload)
        CallPath.DirectBle -> link.sendBleCall(peerID, emptyList(), type, payload)
        is CallPath.RoutedBle -> link.sendBleCall(peerID, path.hops, type, payload)
    }
}

/**
 * The unsigned packet carrying encrypted call audio or a path signal. Its TTL only covers the route, so a direct
 * packet is never relayed and a relay that loses the route does not flood it.
 */
fun callPacket(
    senderID: ByteArray,
    recipientID: ByteArray,
    ciphertext: ByteArray,
    hops: List<ByteArray>,
    timestampMs: Long
) = SignalLessPacket(
    version = if (hops.isEmpty()) 1u else 2u,
    type = MessageType.NOISE_ENCRYPTED.value,
    senderID = senderID,
    recipientID = recipientID,
    timestamp = timestampMs.toULong(),
    payload = ciphertext,
    ttl = hops.size.toUByte(),
    route = hops.ifEmpty { null }
)
