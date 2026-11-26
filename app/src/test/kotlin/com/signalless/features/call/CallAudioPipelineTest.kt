package com.signalless.app.features.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAudioPipelineTest {
    private val callId = ByteArray(CallSignal.CALL_ID_SIZE) { it.toByte() }
    private var now = 1_000L

    private fun packetizer(codec: CallCodec) = CallAudioPacketizer(callId, codec) { now }

    private fun List<ByteArray>.single() = CallAudioPacket.decode(this[0])!!.also { assertEquals(1, size) }

    @Test
    fun `three opus frames make a packet numbered by frame`() {
        val packetizer = packetizer(CallCodec.OPUS)
        assertTrue(packetizer.add(ByteArray(30)).isEmpty())
        assertTrue(packetizer.add(ByteArray(30)).isEmpty())
        now = 1_060L
        val first = packetizer.add(ByteArray(30)).single()
        assertEquals(0, first.seq)
        assertEquals(60L, first.timestampMs)
        assertEquals(3, first.frames.size)
        repeat(2) { packetizer.add(ByteArray(30)) }
        assertEquals(3, packetizer.add(ByteArray(30)).single().seq)
    }

    @Test
    fun `every aac frame is its own packet`() {
        val packetizer = packetizer(CallCodec.AAC_LC)
        assertEquals(0, packetizer.add(ByteArray(128)).single().seq)
        assertEquals(1, packetizer.add(ByteArray(128)).single().seq)
    }

    @Test
    fun `large frames never exceed the unfragmented size`() {
        val packetizer = packetizer(CallCodec.OPUS)
        packetizer.add(ByteArray(200))
        val early = packetizer.add(ByteArray(200)).single()
        assertEquals(1, early.frames.size)
        // A frame that can never fit flushes what is pending and leaves a gap.
        assertEquals(1, packetizer.add(ByteArray(CallAudioPacket.MAX_SIZE)).single().seq)
        repeat(2) { packetizer.add(ByteArray(10)) }
        assertEquals(3, packetizer.add(ByteArray(10)).single().seq)
    }

    @Test
    fun `a full send queue drops the oldest packet`() {
        val queue = CallSendQueue(capacity = 2)
        (1..3).forEach { queue.offer(byteArrayOf(it.toByte())) }
        assertEquals(2, queue.poll(0)!![0].toInt())
        assertEquals(3, queue.poll(0)!![0].toInt())
        assertNull(queue.poll(0))
    }

    @Test
    fun `engine accepts only this call and codec`() {
        val codec = object : VoiceCodec {
            override val type = CallCodec.OPUS
            override val playbackRate = 48_000
            override fun encode(pcm: ShortArray) = emptyList<ByteArray>()
            override fun decode(frame: ByteArray) = ShortArray(0)
            override fun close() = Unit
        }
        val engine = CallAudioEngine(callId, codec, send = {}, onFailure = {}, clockMs = { now })
        fun packet(id: ByteArray, type: CallCodec) =
            CallAudioPacket(id.copyOf(4), 0, 0, type, listOf(byteArrayOf(1))).encode()
        assertTrue(engine.onPacket(packet(callId, CallCodec.OPUS)))
        assertFalse(engine.onPacket(packet(ByteArray(16) { 9 }, CallCodec.OPUS)))
        assertFalse(engine.onPacket(packet(callId, CallCodec.AAC_LC)))
        assertFalse(engine.onPacket(byteArrayOf(1, 2, 3)))
    }
}
