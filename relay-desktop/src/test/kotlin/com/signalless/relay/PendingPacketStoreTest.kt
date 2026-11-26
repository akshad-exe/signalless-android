package com.signalless.relay

import com.signalless.app.protocol.MessageType
import com.signalless.app.protocol.SignalLessPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPacketStoreTest {
    private var now = 1_000L

    @Test
    fun `capacity evicts the oldest packet`() {
        val store = PendingPacketStore(capacity = 2, clock = { now })
        assertTrue(store.store("aaaaaaaaaaaaaaaa", packet(1)))
        assertTrue(store.store("aaaaaaaaaaaaaaaa", packet(2)))
        assertTrue(store.store("aaaaaaaaaaaaaaaa", packet(3)))

        assertEquals(2, store.size())
        val remaining = store.take("aaaaaaaaaaaaaaaa")
        assertEquals(2, remaining.size)
        assertEquals(2, remaining.first().payload[0].toInt())
    }

    @Test
    fun `expired packets are pruned before delivery`() {
        val store = PendingPacketStore(retentionMs = 1_000L, clock = { now })
        assertTrue(store.store("aaaaaaaaaaaaaaaa", packet(1)))
        now += 2_000L
        assertEquals(0, store.size())
        assertTrue(store.take("aaaaaaaaaaaaaaaa").isEmpty())
    }

    @Test
    fun `a packet larger than the byte budget is rejected`() {
        val store = PendingPacketStore(maxBytes = 512, clock = { now })
        assertFalse(store.store("aaaaaaaaaaaaaaaa", packet(2_000)))
        assertEquals(0, store.size())
    }

    private fun packet(payloadSize: Int): SignalLessPacket = SignalLessPacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        ttl = 5u,
        senderID = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        recipientID = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9),
        timestamp = 1_700_000_000_000uL,
        payload = ByteArray(payloadSize) { payloadSize.toByte() },
    )
}
