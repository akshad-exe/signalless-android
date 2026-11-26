package com.signalless.app.features.call

import java.nio.ByteBuffer

/** Audio codec of a call. [mask] is the wire id and the bit in `CallSignal.codecMask`. */
enum class CallCodec(val mask: Int, val frameMs: Int) {
    OPUS(0x01, 20),
    AAC_LC(0x02, 64);

    companion object {
        fun fromWire(value: Int): CallCodec? = entries.firstOrNull { it.mask == value }
    }
}

/**
 * Live call audio carried inside the Noise payload `CALL_AUDIO`. Never stored.
 *
 * `[version: u8 = 1][callId: 4][seq: u16][tsMs: u32][codec: u8][nFrames: u8]`
 * then `nFrames` times `[len: u16][frame]`, big-endian. `callId` is the first
 * four bytes of the call id. `seq` numbers frames, not packets: it belongs to the
 * first frame and the following frames take the next values.
 */
class CallAudioPacket(
    val callId: ByteArray,
    val seq: Int,
    val timestampMs: Long,
    val codec: CallCodec,
    val frames: List<ByteArray>
) {
    init {
        require(callId.size == CALL_ID_PREFIX_SIZE)
        require(seq in 0..0xFFFF)
        require(timestampMs in 0..0xFFFF_FFFFL)
        require(frames.size in 1..MAX_FRAMES)
        require(frames.all { it.isNotEmpty() })
        require(encodedSize(frames) <= MAX_SIZE)
    }

    fun encode(): ByteArray {
        val out = ByteBuffer.allocate(encodedSize(frames))
        out.put(VERSION.toByte()).put(callId).putShort(seq.toShort()).putInt(timestampMs.toInt())
        out.put(codec.mask.toByte()).put(frames.size.toByte())
        frames.forEach { out.putShort(it.size.toShort()).put(it) }
        return out.array()
    }

    companion object {
        const val VERSION = 1
        const val CALL_ID_PREFIX_SIZE = 4
        const val HEADER_SIZE = 13
        const val MAX_FRAMES = 4

        /** Keeps the encrypted packet, with a 3-hop source route, under the 512-byte fragment threshold. */
        const val MAX_SIZE = 360

        fun encodedSize(frames: List<ByteArray>): Int = HEADER_SIZE + frames.sumOf { 2 + it.size }

        fun decode(bytes: ByteArray): CallAudioPacket? {
            if (bytes.size !in HEADER_SIZE..MAX_SIZE) return null
            val input = ByteBuffer.wrap(bytes)
            if (input.get().toInt() != VERSION) return null
            val callId = ByteArray(CALL_ID_PREFIX_SIZE).also { input.get(it) }
            val seq = input.short.toInt() and 0xFFFF
            val timestampMs = input.int.toLong() and 0xFFFF_FFFFL
            val codec = CallCodec.fromWire(input.get().toInt() and 0xFF) ?: return null
            val count = input.get().toInt() and 0xFF
            if (count !in 1..MAX_FRAMES) return null
            val frames = ArrayList<ByteArray>(count)
            repeat(count) {
                if (input.remaining() < 2) return null
                val length = input.short.toInt() and 0xFFFF
                if (length == 0 || length > input.remaining()) return null
                frames.add(ByteArray(length).also { input.get(it) })
            }
            if (input.hasRemaining()) return null
            return CallAudioPacket(callId, seq, timestampMs, codec, frames)
        }
    }
}
