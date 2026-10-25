package com.signalless.relay.transport

import kotlinx.coroutines.flow.Flow

/**
 * Events emitted by a concrete relay transport.
 *
 * Link IDs are opaque, process-local identifiers. They must never be MAC
 * addresses, device names, or any other value that leaks a peer's identity.
 */
sealed interface RelayTransportEvent {
    data class Ready(
        val platform: String,
        val maxLinks: Int,
        val serviceDataSupported: Boolean,
    ) : RelayTransportEvent

    data class LinkUp(
        val linkId: String,
        val mtu: Int,
        val maxNotificationSize: Int,
    ) : RelayTransportEvent

    data class LinkDown(
        val linkId: String,
        val reason: String,
    ) : RelayTransportEvent

    class Bytes(
        val linkId: String,
        val data: ByteArray,
    ) : RelayTransportEvent

    data class Error(
        val code: String,
        val message: String,
    ) : RelayTransportEvent
}

/**
 * Transport boundary between the relay protocol engine and BLE/TCP/fake links.
 *
 * The engine owns protocol decisions. A transport only moves bytes and reports
 * link lifecycle; it must not parse SignalLess packets or know peer identities.
 */
interface RelayTransport {
    val events: Flow<RelayTransportEvent>

    suspend fun start(): Result<Unit>

    suspend fun stop()

    /**
     * Sends exactly one already-fragmented notification payload to [linkId].
     * The caller is responsible for keeping [data] within the link's
     * `maxNotificationSize`.
     */
    suspend fun send(linkId: String, data: ByteArray): Result<Unit>
}
