package com.signalless.relay

import com.signalless.app.protocol.MessageType
import com.signalless.app.protocol.SignalLessPacket
import com.signalless.app.protocol.SpecialRecipients
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayFragmenterTest {
    private val sender = "aaaaaaaaaaaaaaaa"
    private val recipient = "bbbbbbbbbbbbbbbb"

    @Test
    fun `fragment sizes fit the negotiated notification limit and reassemble`() {
        val original = packet(payload = randomBytes(1_500))
        val fragmenter = RelayFragmenter()
        val assembler = RelayFragmentAssembler()
        try {
            val fragments = fragmenter.fragment(original, maxNotificationSize = 180)
            assertNotNull(fragments)
            requireNotNull(fragments)
            assertTrue(fragments.size > 1)
            fragments.forEach { fragment ->
                assertTrue(
                    fragment.toBinaryData(padding = false)!!.size <= 180,
                )
            }

            var reassembled: SignalLessPacket? = null
            fragments.forEach { fragment ->
                reassembled = assembler.accept(fragment) ?: reassembled
            }
            assertNotNull(reassembled)
            assertEquals(original.type, reassembled!!.type)
            assertEquals(original.senderID.toList(), reassembled!!.senderID.toList())
            assertEquals(original.recipientID?.toList(), reassembled!!.recipientID?.toList())
            assertEquals(original.ttl, reassembled!!.ttl)
            assertArrayEquals(original.payload, reassembled!!.payload)
        } finally {
            assembler.close()
        }
    }

    @Test
    fun `returns the original packet when it already fits`() {
        val original = packet(payload = ByteArray(40) { 1 })
        val result = RelayFragmenter().fragment(original, maxNotificationSize = 514)
        assertEquals(1, result?.size)
        assertEquals(original, result?.first())
    }

    @Test
    fun `rejects a limit too small for the fragment header`() {
        val original = packet(payload = ByteArray(1_000) { 7 })
        assertNull(RelayFragmenter().fragment(original, maxNotificationSize = 12))
    }

    @Test
    fun `rejects a fragment set larger than the protocol cap`() {
        val original = packet(payload = randomBytes(100_000))
        assertNull(RelayFragmenter().fragment(original, maxNotificationSize = 128))
    }

    private fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        java.util.Random(42).nextBytes(bytes)
        return bytes
    }

    private fun packet(payload: ByteArray): SignalLessPacket = SignalLessPacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        ttl = 5u,
        senderID = sender.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        recipientID = recipient.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        timestamp = 1_700_000_000_000uL,
        payload = payload,
    )
}
