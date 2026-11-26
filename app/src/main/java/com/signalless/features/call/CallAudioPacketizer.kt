package com.signalless.app.features.call

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** Groups encoded frames into `CALL_AUDIO` packets. Used from the capture thread only. */
class CallAudioPacketizer(
    callId: ByteArray,
    private val codec: CallCodec,
    private val clockMs: () -> Long
) {
    private val prefix = callId.copyOf(CallAudioPacket.CALL_ID_PREFIX_SIZE)
    private val framesPerPacket = if (codec == CallCodec.OPUS) 3 else 1
    private val startedAtMs = clockMs()
    private val pending = mutableListOf<ByteArray>()
    private var nextSeq = 0

    /** Returns the packets this frame completed. */
    fun add(frame: ByteArray): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        if (CallAudioPacket.encodedSize(listOf(frame)) > CallAudioPacket.MAX_SIZE || frame.isEmpty()) {
            // Too big to send unfragmented: skip its number so the peer sees a loss.
            flush()?.let(packets::add)
            nextSeq = (nextSeq + 1) and 0xFFFF
            return packets
        }
        if (CallAudioPacket.encodedSize(pending + frame) > CallAudioPacket.MAX_SIZE) {
            flush()?.let(packets::add)
        }
        pending.add(frame)
        if (pending.size >= framesPerPacket) flush()?.let(packets::add)
        return packets
    }

    /** Forgets unsent frames, such as when the microphone is muted. */
    fun reset() = pending.clear()

    private fun flush(): ByteArray? {
        if (pending.isEmpty()) return null
        val packet = CallAudioPacket(
            prefix, nextSeq, (clockMs() - startedAtMs) and 0xFFFF_FFFFL, codec, pending.toList()
        ).encode()
        nextSeq = (nextSeq + pending.size) and 0xFFFF
        pending.clear()
        return packet
    }
}

/** Bounded outbound queue. When full it drops the oldest packet and never blocks the producer. */
class CallSendQueue(capacity: Int = 6) {
    private val packets = LinkedBlockingDeque<ByteArray>(capacity)

    @Synchronized
    fun offer(packet: ByteArray) {
        while (!packets.offerLast(packet)) packets.pollFirst()
    }

    fun poll(timeoutMs: Long): ByteArray? = packets.pollFirst(timeoutMs, TimeUnit.MILLISECONDS)
}
