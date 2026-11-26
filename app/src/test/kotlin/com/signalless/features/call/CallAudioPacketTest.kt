package com.signalless.app.features.call

import com.signalless.app.model.NoisePayload
import com.signalless.app.model.NoisePayloadType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallAudioPacketTest {
    private val callId = byteArrayOf(0x11, 0x22, 0x33, 0x44)

    private val golden = byteArrayOf(
        0x01,
        0x11, 0x22, 0x33, 0x44,
        0xff.toByte(), 0xfe.toByte(),
        0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(),
        0x01,
        0x02,
        0x00, 0x02, 0x0a, 0x0b,
        0x00, 0x01, 0x0c
    )

    @Test
    fun `golden vector pins the wire format`() {
        val packet = CallAudioPacket(
            callId, 0xFFFE, 0x89ABCDEFL, CallCodec.OPUS,
            listOf(byteArrayOf(0x0a, 0x0b), byteArrayOf(0x0c))
        )
        assertArrayEquals(golden, packet.encode())

        val decoded = CallAudioPacket.decode(golden)!!
        assertArrayEquals(callId, decoded.callId)
        assertEquals(0xFFFE, decoded.seq)
        assertEquals(0x89ABCDEFL, decoded.timestampMs)
        assertEquals(CallCodec.OPUS, decoded.codec)
        assertEquals(2, decoded.frames.size)
        assertArrayEquals(byteArrayOf(0x0c), decoded.frames[1])
    }

    @Test
    fun `malformed packets are rejected`() {
        fun mutated(index: Int, value: Int) = golden.copyOf().also { it[index] = value.toByte() }
        assertNull(CallAudioPacket.decode(ByteArray(0)))
        assertNull(CallAudioPacket.decode(golden.copyOf(golden.size - 1)))
        assertNull(CallAudioPacket.decode(golden.copyOf(golden.size + 1)))
        assertNull(CallAudioPacket.decode(mutated(0, 2)))
        assertNull(CallAudioPacket.decode(mutated(11, 3)))
        assertNull(CallAudioPacket.decode(mutated(12, 0)))
        assertNull(CallAudioPacket.decode(mutated(12, 3)))
        assertNull(CallAudioPacket.decode(mutated(14, 0)))
        assertNull(CallAudioPacket.decode(ByteArray(CallAudioPacket.MAX_SIZE + 1)))
    }

    @Test
    fun `call audio uses its own Noise payload type`() {
        val encoded = NoisePayload(NoisePayloadType.CALL_AUDIO, golden).encode()
        assertEquals(0x31, encoded[0].toInt() and 0xFF)
        assertEquals(NoisePayloadType.CALL_AUDIO, NoisePayload.decode(encoded)?.type)
    }
}
