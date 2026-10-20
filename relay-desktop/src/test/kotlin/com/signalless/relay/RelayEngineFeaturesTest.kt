package com.signalless.relay

import com.signalless.app.mesh.AnnouncementIdentityValidator
import com.signalless.app.protocol.MessageType
import com.signalless.app.protocol.SignalLessPacket
import com.signalless.app.util.SLog
import com.signalless.relay.identity.RelayIdentity
import com.signalless.relay.transport.FakeRelayTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RelayEngineFeaturesTest {
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        SLog.sink = { _, _, _, _ -> }
    }

    @After
    fun tearDown() {
        scope.cancel()
        SLog.sink = null
    }

    @Test
    fun `relay announces its signed identity on link up`() = runBlocking {
        val identity = RelayIdentity.generate()
        val fake = FakeRelayTransport()
        val engine = RelayEngine(
            myPeerId = identity.peerId,
            transport = fake,
            scope = scope,
            identity = identity,
            announceIntervalMs = 3_600_000,
            fragmentSendDelayMs = 0,
        )

        engine.start()
        try {
            fake.linkUp("l1")
            awaitUntil("relay announcement") { fake.sentFrames().isNotEmpty() }

            val frame = fake.sentFrames().first()
            assertEquals("l1", frame.linkId)
            val packet = SignalLessPacket.fromBinaryData(frame.data)
            assertNotNull(packet)
            assertEquals(MessageType.ANNOUNCE.value, packet!!.type)
            assertEquals(identity.peerId, packet.senderID.joinToString("") { "%02x".format(it) })

            val verified = AnnouncementIdentityValidator.verify(
                packet = packet,
                claimedPeerID = identity.peerId,
                nowMs = System.currentTimeMillis(),
            ) { signature, data, publicKey -> identity.verify(signature, data, publicKey) }
            assertNotNull(verified)
            assertEquals(1L, engine.stats().announcementsSent)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `fragments are reassembled and re-fragmented for the destination link`() = runBlocking {
        val peerA = "aaaaaaaaaaaaaaaa"
        val peerB = "bbbbbbbbbbbbbbbb"
        val fake = FakeRelayTransport()
        val engine = RelayEngine(
            myPeerId = "cccccccccccccccc",
            transport = fake,
            scope = scope,
            fragmentSendDelayMs = 0,
        )

        engine.start()
        try {
            fake.linkUp("l1")
            fake.linkUp("l2", maxNotificationSize = 514)

            // Bind link l2 to peer B with a small broadcast.
            val bind = packet(
                type = MessageType.MESSAGE,
                sender = peerB,
                recipient = null,
                ttl = 5u,
                payload = byteArrayOf(1),
            )
            fake.receive("l2", bind.toBinaryData()!!)
            awaitUntil("binding broadcast") { fake.sentFrames().isNotEmpty() }
            fake.clearSentFrames()
            val forwardedBeforeFragments = engine.stats().forwarded

            val original = packet(
                type = MessageType.MESSAGE,
                sender = peerA,
                recipient = peerB,
                ttl = 5u,
                payload = randomBytes(1_400),
            )
            val fragments = RelayFragmenter().fragment(original, 180)
            assertNotNull(fragments)
            requireNotNull(fragments)
            fragments.forEach { fragment ->
                fake.receive("l1", fragment.toBinaryData(padding = false)!!)
            }

            awaitUntil("relayed fragments") { fake.sentFrames().isNotEmpty() }
            val relayed = fake.sentFrames()
            assertEquals(1, engine.stats().fragmentsAssembled)
            assertEquals(forwardedBeforeFragments + 1, engine.stats().forwarded)
            assertTrue(relayed.size > 1)
            assertTrue(relayed.all { it.linkId == "l2" })

            val assembler = RelayFragmentAssembler()
            try {
                var reassembled: SignalLessPacket? = null
                relayed.forEach { frame ->
                    val fragment = SignalLessPacket.fromBinaryData(frame.data)
                    assertNotNull(fragment)
                    reassembled = assembler.accept(fragment!!) ?: reassembled
                }
                assertNotNull(reassembled)
                assertEquals(original.type, reassembled!!.type)
                assertArrayEquals(original.payload, reassembled!!.payload)
            } finally {
                assembler.close()
            }
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `unicast for an absent peer is stored and delivered when it connects`() = runBlocking {
        val peerB = "bbbbbbbbbbbbbbbb"
        val peerC = "dddddddddddddddd"
        val fake = FakeRelayTransport()
        val engine = RelayEngine(
            myPeerId = "cccccccccccccccc",
            transport = fake,
            scope = scope,
            fragmentSendDelayMs = 0,
        )

        engine.start()
        try {
            fake.linkUp("l1")

            val forC = packet(
                type = MessageType.MESSAGE,
                sender = peerB,
                recipient = peerC,
                ttl = 6u,
                payload = byteArrayOf(4, 5, 6),
            )
            fake.receive("l1", forC.toBinaryData()!!)
            awaitUntil("stored packet") { engine.stats().stored == 1L }
            assertEquals(0, fake.sentFrames().size)

            fake.linkUp("l2")
            val bind = packet(
                type = MessageType.MESSAGE,
                sender = peerC,
                recipient = null,
                ttl = 5u,
                payload = byteArrayOf(7),
            )
            fake.receive("l2", bind.toBinaryData()!!)
            awaitUntil("store-and-forward delivery") { engine.stats().storeFlushed == 1L }
            assertTrue(fake.sentFrames().any { it.linkId == "l2" })
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `seven links fan out a broadcast to six peers`() = runBlocking {
        val fake = FakeRelayTransport()
        val engine = RelayEngine(
            myPeerId = "cccccccccccccccc",
            transport = fake,
            scope = scope,
        )
        engine.start()
        try {
            (1..7).forEach { fake.linkUp("l$it") }
            awaitUntil("seven links") { engine.linkCount() == 7 }

            val broadcast = packet(
                type = MessageType.MESSAGE,
                sender = "aaaaaaaaaaaaaaaa",
                recipient = null,
                ttl = 7u,
                payload = byteArrayOf(1, 2, 3),
            )
            fake.receive("l1", broadcast.toBinaryData()!!)
            awaitUntil("seven-link fanout") { fake.sentFrames().size == 6 }
            assertEquals(6, fake.sentFrames().size)
            assertEquals(6L, engine.stats().forwarded)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `the configured ttl ceiling lowers an oversized inbound ttl`() = runBlocking {
        val fake = FakeRelayTransport()
        val engine = RelayEngine(
            myPeerId = "cccccccccccccccc",
            transport = fake,
            scope = scope,
            maxTtl = 2u,
        )
        engine.start()
        try {
            fake.linkUp("l1")
            fake.linkUp("l2")
            awaitUntil("two links") { engine.linkCount() == 2 }

            val broadcast = packet(
                type = MessageType.MESSAGE,
                sender = "aaaaaaaaaaaaaaaa",
                recipient = null,
                ttl = 7u,
                payload = byteArrayOf(9),
            )
            fake.receive("l1", broadcast.toBinaryData()!!)
            awaitUntil("ttl capped relay") { fake.sentFrames().size == 1 }
            val relayed = SignalLessPacket.fromBinaryData(fake.sentFrames().first().data)
            assertNotNull(relayed)
            assertEquals(1u.toUByte(), relayed!!.ttl)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `link admission stops at the configured cap`() = runBlocking {
        val fake = FakeRelayTransport()
        val engine = RelayEngine(
            myPeerId = "cccccccccccccccc",
            transport = fake,
            scope = scope,
            maxLinks = 2,
        )

        engine.start()
        try {
            fake.linkUp("l1")
            fake.linkUp("l2")
            fake.linkUp("l3")
            awaitUntil("over-capacity link") { engine.stats().overCapacityLinks == 1L }
            assertEquals(2, engine.linkCount())
        } finally {
            engine.stop()
        }
    }

    private suspend fun awaitUntil(label: String, condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) {
                delay(10)
            }
        }
        assertTrue(label, condition())
    }

    private fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        java.util.Random(7).nextBytes(bytes)
        return bytes
    }

    private fun packet(
        type: com.signalless.app.protocol.MessageType,
        sender: String,
        recipient: String?,
        ttl: UByte,
        payload: ByteArray,
    ): SignalLessPacket = SignalLessPacket(
        version = 1u,
        type = type.value,
        ttl = ttl,
        senderID = sender.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        recipientID = recipient?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray(),
        timestamp = 1_700_000_000_000uL,
        payload = payload,
    )
}
