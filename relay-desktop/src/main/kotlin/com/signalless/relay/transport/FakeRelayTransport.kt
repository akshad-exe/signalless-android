package com.signalless.relay.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Deterministic in-memory transport for tests and local protocol work.
 *
 * It deliberately has no BLE dependency. Tests can open/close links, inject
 * bytes, inspect sent frames, and force start/send failures.
 */
class FakeRelayTransport(
    private val platform: String = "fake",
    private val maxLinks: Int = 64,
    private val serviceDataSupported: Boolean = false,
) : RelayTransport {
    data class SentFrame(
        val linkId: String,
        val data: ByteArray,
    )

    private val eventChannel = Channel<RelayTransportEvent>(Channel.UNLIMITED)
    private val sentLock = Any()
    private val sentFrames = mutableListOf<SentFrame>()

    @Volatile
    private var started: Boolean = false

    @Volatile
    var startFailure: Throwable? = null

    @Volatile
    var sendFailure: Throwable? = null

    override val events: Flow<RelayTransportEvent> = eventChannel.receiveAsFlow()

    override suspend fun start(): Result<Unit> {
        if (started) return Result.success(Unit)
        val failure = startFailure
        if (failure != null) return Result.failure(failure)

        started = true
        emit(
            RelayTransportEvent.Ready(
                platform = platform,
                maxLinks = maxLinks,
                serviceDataSupported = serviceDataSupported,
            ),
        )
        return Result.success(Unit)
    }

    override suspend fun stop() {
        started = false
        eventChannel.close()
    }

    override suspend fun send(linkId: String, data: ByteArray): Result<Unit> {
        if (!started) {
            return Result.failure(IllegalStateException("transport is not started"))
        }
        val failure = sendFailure
        if (failure != null) return Result.failure(failure)

        synchronized(sentLock) {
            sentFrames += SentFrame(linkId = linkId, data = data.copyOf())
        }
        return Result.success(Unit)
    }

    fun linkUp(linkId: String, mtu: Int = 517, maxNotificationSize: Int = 514): Boolean =
        emit(
            RelayTransportEvent.LinkUp(
                linkId = linkId,
                mtu = mtu,
                maxNotificationSize = maxNotificationSize,
            ),
        )

    fun linkDown(linkId: String, reason: String = "test"): Boolean =
        emit(RelayTransportEvent.LinkDown(linkId = linkId, reason = reason))

    fun receive(linkId: String, data: ByteArray): Boolean =
        emit(RelayTransportEvent.Bytes(linkId = linkId, data = data.copyOf()))

    fun sentFrames(): List<SentFrame> = synchronized(sentLock) { sentFrames.toList() }

    fun clearSentFrames() {
        synchronized(sentLock) { sentFrames.clear() }
    }

    private fun emit(event: RelayTransportEvent): Boolean =
        eventChannel.trySend(event).isSuccess
}
