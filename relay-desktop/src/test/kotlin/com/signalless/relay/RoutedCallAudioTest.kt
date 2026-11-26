package com.signalless.relay

import com.signalless.app.protocol.MessageType
import com.signalless.app.protocol.SignalLessPacket
import com.signalless.app.services.meshgraph.GossipTLV
import com.signalless.app.util.SLog
import com.signalless.relay.identity.RelayIdentity
import com.signalless.relay.transport.FakeRelayTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Call audio is an encrypted packet with a source route and a TTL that only covers it. */
class RoutedCallAudioTest {
    private val relay = RelayIdentity.generate()
    private val a = RelayIdentity.generate()
    private val b = RelayIdentity.generate()
    private val far = "eeeeeeeeeeeeeeee"

    @Before
    fun silenceLogs() {
        SLog.sink = { _, _, _, _ -> }
    }

    @After
    fun restoreLogs() {
        SLog.sink = null
    }

    @Test
    fun `last hop sends only to the recipient`() = withLinkedPhones { fake, engine ->
        fake.receive("a", audio(recipient = b.peerId, route = listOf(relay.peerId)))

        awaitUntil { engine.stats().forwarded == 5L }
        val sent = fake.sentFrames().single()
        assertEquals("b", sent.linkId)
        assertEquals(0, SignalLessPacket.fromBinaryData(sent.data)!!.ttl.toInt())
    }

    @Test
    fun `middle hop sends only to the next hop`() = withLinkedPhones { fake, engine ->
        fake.receive("a", audio(recipient = far, route = listOf(relay.peerId, b.peerId)))

        awaitUntil { engine.stats().forwarded == 5L }
        assertEquals("b", fake.sentFrames().single().linkId)
    }

    @Test
    fun `a lost next hop is neither flooded nor stored`() = withLinkedPhones { fake, engine ->
        fake.receive("a", audio(recipient = far, route = listOf(relay.peerId)))

        awaitUntil { engine.stats().noRoute == 1L }
        assertEquals(0, fake.sentFrames().size)
        assertEquals(0L, engine.stats().stored)
    }

    @Test
    fun `a full size audio packet is not fragmented on a small link`() =
        withLinkedPhones(maxNotification = 509) { fake, engine ->
            fake.receive(
                "a",
                audio(recipient = b.peerId, route = listOf(relay.peerId), size = 381),
            )

            awaitUntil { engine.stats().forwarded == 5L }
            assertEquals(1, fake.sentFrames().size)
        }

    @Test
    fun `relay announces its neighbours so phones can route through it`() =
        withLinkedPhones(clear = false) { fake, _ ->
            val latest = fake.sentFrames()
                .mapNotNull { SignalLessPacket.fromBinaryData(it.data) }
                .last { it.senderID.contentEquals(relay.peerIdBytes()) }
            assertEquals(
                setOf(a.peerId, b.peerId),
                GossipTLV.decodeNeighborsFromAnnouncementPayload(latest.payload)?.toSet(),
            )
        }

    private fun audio(recipient: String, route: List<String>, size: Int = 64): ByteArray {
        val packet = SignalLessPacket(
            version = 2u,
            type = MessageType.NOISE_ENCRYPTED.value,
            ttl = route.size.toUByte(),
            senderID = a.peerIdBytes(),
            recipientID = bytes(recipient),
            timestamp = 1_700_000_000_000u,
            payload = ByteArray(size) { (it * 31).toByte() },
            signature = ByteArray(64) { 1 },
            route = route.map(::bytes),
        )
        return requireNotNull(packet.toBinaryData())
    }

    private fun bytes(hex: String) =
        ByteArray(8) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Runs the relay with phones [a] and [b] bound, plus an unrelated third link. */
    private fun withLinkedPhones(
        maxNotification: Int? = null,
        clear: Boolean = true,
        block: suspend (FakeRelayTransport, RelayEngine) -> Unit,
    ) = runBlocking {
        val fake = FakeRelayTransport()
        val engine = RelayEngine(relay.peerId, fake, this, identity = relay)
        engine.start()
        try {
            for (link in listOf("a", "b", "other")) {
                if (maxNotification == null) {
                    fake.linkUp(link)
                } else {
                    fake.linkUp(link, mtu = maxNotification + 3, maxNotificationSize = maxNotification)
                }
            }
            for ((link, phone) in listOf("a" to a, "b" to b)) {
                val announce = phone.signedAnnouncement("phone", 7u)
                fake.receive(link, requireNotNull(announce.toBinaryData()))
            }
            // Each announcement is relayed to the two other links.
            awaitUntil { engine.stats().forwarded == 4L }
            if (clear) fake.clearSentFrames()
            block(fake, engine)
        } finally {
            engine.stop()
        }
    }

    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(2_000L) {
        while (!condition()) delay(10L)
    }
}
