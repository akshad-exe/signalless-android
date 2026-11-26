package com.signalless.app.features.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {
    private val buffer = JitterBuffer(frameMs = 20)

    /** Offers a packet of frames whose bytes are their own sequence numbers, on time. */
    private fun offer(seq: Int, count: Int = 1, lateMs: Long = 0) {
        val frames = (0 until count).map { byteArrayOf((seq + it).toByte()) }
        buffer.offer(seq and 0xFFFF, frames, seq * 20L + lateMs)
    }

    private fun play(): String = when (val out = buffer.poll()) {
        is JitterBuffer.Playout.Frame -> (out.data[0].toInt() and 0xFF).toString()
        JitterBuffer.Playout.Conceal -> "conceal"
        JitterBuffer.Playout.Silence -> "silence"
    }

    private fun play(count: Int) = List(count) { play() }

    @Test
    fun `waits for the target delay before playing`() {
        offer(0)
        offer(1)
        assertEquals("silence", play())
        offer(2)
        assertEquals(listOf("0", "1", "2"), play(3))
    }

    @Test
    fun `reordered frames play in sequence`() {
        listOf(0, 2, 1, 3).forEach { offer(it) }
        assertEquals(listOf("0", "1", "2", "3"), play(4))
    }

    @Test
    fun `a lost frame is concealed and playback moves on`() {
        offer(0, 2)
        offer(4, 2)
        assertEquals(listOf("0", "1", "conceal", "silence", "4", "5"), play(6))
    }

    @Test
    fun `a frame arriving after its slot is dropped`() {
        offer(0, 1)
        offer(2, 2)
        assertEquals(listOf("0", "conceal", "2"), play(3))
        offer(1)
        assertEquals("3", play())
        assertEquals("conceal", play())
    }

    @Test
    fun `an underrun waits for the delayed frame`() {
        offer(0, 3)
        assertEquals(listOf("0", "1", "2", "conceal", "silence"), play(5))
        offer(3)
        assertEquals("3", play())
    }

    @Test
    fun `sequence numbers wrap`() {
        offer(0xFFFE, 3)
        offer(0x10001, 1)
        assertEquals(listOf("254", "255", "0", "1"), play(4))
    }

    @Test
    fun `a burst is trimmed to bound latency`() {
        offer(0, 20)
        val first = play().toInt()
        assertTrue("played $first", first >= 15)
    }

    @Test
    fun `delay follows jitter within its bounds`() {
        assertEquals(JitterBuffer.START_DELAY_MS, buffer.targetDelayMs)
        for (seq in 0 until 200) offer(seq, lateMs = if (seq % 2 == 0) 0L else 100L)
        assertEquals(JitterBuffer.MAX_DELAY_MS, buffer.targetDelayMs)
        for (seq in 200 until 400) offer(seq)
        assertEquals(JitterBuffer.MIN_DELAY_MS, buffer.targetDelayMs)
    }
}
