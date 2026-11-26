package com.signalless.relay

import com.signalless.app.protocol.MessageType
import com.signalless.app.protocol.SignalLessPacket
import com.signalless.app.util.SLog
import com.signalless.relay.identity.RelayIdentity
import com.signalless.relay.transport.FakeRelayTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RelayEngineTest {
    private val myPeerId = "cccccccccccccccc"
    private val peerA = "aaaaaaaaaaaaaaaa"
    private val peerB = "bbbbbbbbbbbbbbbb"
    private val relayIdentity = RelayIdentity.generate()

    @Before
    fun silenceLogs() {
        SLog.sink = { _, _, _, _ -> }
    }

    @After
    fun restoreLogs() {
        SLog.sink = null
    }

    @Test
    fun `broadcast is forwarded only to the other links with a lower ttl`() = withEngine { fake, engine ->
        fake.linkUp("link-1")
        fake.linkUp("link-2")

        fake.receive("link-1", packetBytes(sender = peerA, ttl = 3u))

        awaitUntil("broadcast forwarded") { engine.stats().forwarded == 1L }
        val frames = fake.sentFrames()
        assertEquals(1, frames.size)
        assertEquals("link-2", frames.single().linkId)

        val relayed = SignalLessPacket.fromBinaryData(frames.single().data)
        assertEquals(2u.toUByte(), relayed?.ttl)
        assertEquals(peerA, relayed?.senderID?.toHex())
    }

    @Test
    fun `ttl zero packets are not forwarded`() = withEngine { fake, engine ->
        fake.linkUp("link-1")
        fake.linkUp("link-2")

        fake.receive("link-1", packetBytes(sender = peerA, ttl = 0u))

        awaitUntil("ttl exhaustion counted") { engine.stats().ttlExpired == 1L }
        assertTrue(fake.sentFrames().isEmpty())
    }

    @Test
    fun `duplicate packet is forwarded once`() = withEngine { fake, engine ->
        fake.linkUp("link-1")
        fake.linkUp("link-2")
        val frame = packetBytes(sender = peerA, ttl = 3u)

        fake.receive("link-1", frame)
        fake.receive("link-1", frame)

        awaitUntil("duplicate counted") { engine.stats().duplicates == 1L }
        assertEquals(1L, engine.stats().forwarded)
        assertEquals(1, fake.sentFrames().size)
    }

    @Test
    fun `unicast uses the peer learned from a verified direct announce`() =
        withEngine(relayIdentity) { fake, engine ->
            val phone = RelayIdentity.generate()
            fake.linkUp("link-1")
            fake.linkUp("link-2")
            fake.linkUp("link-3")

            fake.receive("link-2", announceBytes(phone, ttl = 7u))
            awaitUntil("peer bound") {
                engine.linksSnapshot().any { it.id == "link-2" && it.peerId == phone.peerId }
            }
            awaitUntil("announce relayed") { engine.stats().forwarded == 2L }
            fake.clearSentFrames()

            fake.receive("link-1", packetBytes(sender = peerA, recipient = phone.peerId, ttl = 3u))

            awaitUntil("unicast forwarded") { engine.stats().forwarded == 3L }
            assertEquals("link-2", fake.sentFrames().single().linkId)
        }

    @Test
    fun `unauthenticated sender id neither binds a link nor flushes queued packets`() =
        withEngine(relayIdentity) { fake, engine ->
            val victim = RelayIdentity.generate()
            fake.linkUp("link-1")
            fake.receive("link-1", packetBytes(sender = peerA, recipient = victim.peerId, ttl = 3u))
            awaitUntil("stored") { engine.stats().stored == 1L }

            fake.linkUp("attacker")
            fake.receive("attacker", packetBytes(sender = victim.peerId, ttl = 3u))
            // A relayed (lowered TTL) announce is genuine but says nothing about this link.
            fake.receive("attacker", announceBytes(victim, ttl = 6u))
            awaitUntil("both relayed") { engine.stats().forwarded == 2L }

            assertEquals(0L, engine.stats().storeFlushed)
            assertTrue(engine.linksSnapshot().all { it.peerId == null })
        }

    @Test
    fun `unicast for an unbound peer still reaches other links`() = withEngine { fake, engine ->
        fake.linkUp("link-1")
        fake.linkUp("link-2")

        fake.receive("link-1", packetBytes(sender = peerA, recipient = peerB, ttl = 3u))

        awaitUntil("flooded") { engine.stats().forwarded == 1L }
        assertEquals("link-2", fake.sentFrames().single().linkId)
    }

    @Test
    fun `packet addressed to the relay is not forwarded`() = withEngine { fake, engine ->
        fake.linkUp("link-1")
        fake.linkUp("link-2")

        fake.receive(
            "link-1",
            packetBytes(sender = peerA, recipient = myPeerId, ttl = 3u),
        )

        awaitUntil("addressed to relay counted") { engine.stats().addressedToMe == 1L }
        assertTrue(fake.sentFrames().isEmpty())
    }

    @Test
    fun `malformed input is counted and dropped`() = withEngine { fake, engine ->
        fake.linkUp("link-1")

        fake.receive("link-1", byteArrayOf(0x01, 0x02, 0x03))

        awaitUntil("malformed counted") { engine.stats().malformed == 1L }
        assertTrue(fake.sentFrames().isEmpty())
    }

    @Test
    fun `closed link is removed from routing`() = withEngine { fake, engine ->
        fake.linkUp("link-1")
        fake.linkUp("link-2")
        fake.linkDown("link-2")

        fake.receive("link-1", packetBytes(sender = peerA, ttl = 3u))

        awaitUntil("no route counted") { engine.stats().noRoute == 1L }
        assertTrue(fake.sentFrames().isEmpty())
    }

    @Test
    fun `oversized encoded packet is not sent`() = withEngine { fake, engine ->
        fake.linkUp("link-1")
        fake.linkUp("link-2", mtu = 23, maxNotificationSize = 20)

        fake.receive(
            "link-1",
            packetBytes(sender = peerA, payload = ByteArray(100) { 0x42 }, ttl = 3u),
        )

        awaitUntil("oversized counted") { engine.stats().oversized == 1L }
        assertTrue(fake.sentFrames().isEmpty())
    }

    @Test
    fun `unknown link input is dropped`() = withEngine { fake, engine ->
        fake.receive("missing", packetBytes(sender = peerA, ttl = 3u))

        awaitUntil("unknown link counted") { engine.stats().unknownLinks == 1L }
        assertTrue(fake.sentFrames().isEmpty())
    }

    @Test
    fun `send failure is counted`() = withEngine { fake, engine ->
        fake.linkUp("link-1")
        fake.linkUp("link-2")
        fake.sendFailure = IllegalStateException("notify failed")

        fake.receive("link-1", packetBytes(sender = peerA, ttl = 3u))

        awaitUntil("send failure counted") { engine.stats().sendFailed == 1L }
        assertEquals(0L, engine.stats().forwarded)
        assertTrue(fake.sentFrames().isEmpty())
    }

    @Test
    fun `start failure propagates without marking the engine running`() {
        val fake = FakeRelayTransport()
        fake.startFailure = IllegalStateException("adapter unavailable")

        runBlocking {
            val engine = RelayEngine(myPeerId, fake, this)
            try {
                engine.start()
                fail("expected start to fail")
            } catch (error: IllegalStateException) {
                assertEquals("adapter unavailable", error.message)
            }
        }
    }

    private fun announceBytes(phone: RelayIdentity, ttl: UByte): ByteArray =
        requireNotNull(phone.signedAnnouncement("phone", ttl).toBinaryData())

    private fun withEngine(
        identity: RelayIdentity? = null,
        block: suspend CoroutineScope.(FakeRelayTransport, RelayEngine) -> Unit,
    ) = runBlocking {
        val fake = FakeRelayTransport()
        val engine = RelayEngine(
            myPeerId = identity?.peerId ?: myPeerId,
            transport = fake,
            scope = this,
            identity = identity,
        )
        engine.start()
        try {
            block(fake, engine)
        } finally {
            engine.stop()
        }
    }

    private suspend fun awaitUntil(description: String, condition: () -> Boolean) {
        try {
            withTimeout(2_000L) {
                while (!condition()) {
                    delay(10L)
                }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException &&
                error !is kotlinx.coroutines.TimeoutCancellationException
            ) {
                throw error
            }
            throw AssertionError("timed out waiting for $description", error)
        }
    }

    private fun packetBytes(
        sender: String,
        recipient: String? = null,
        ttl: UByte = 3u,
        type: UByte = MessageType.MESSAGE.value,
        payload: ByteArray = "hello".toByteArray(),
        timestamp: ULong = 1_700_000_000_000u,
    ): ByteArray {
        val packet = SignalLessPacket(
            type = type,
            ttl = ttl,
            senderID = hexBytes(sender),
            recipientID = recipient?.let(::hexBytes),
            timestamp = timestamp,
            payload = payload,
        )
        return requireNotNull(packet.toBinaryData())
    }

    private fun hexBytes(value: String): ByteArray {
        require(value.length == 16 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
