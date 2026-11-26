package com.signalless.relay.identity

import com.signalless.app.mesh.AnnouncementIdentityValidator
import com.signalless.app.protocol.MessageType
import com.signalless.app.util.SLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class RelayIdentityTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun silenceLogs() {
        SLog.sink = { _, _, _, _ -> }
    }

    @After
    fun restoreLogs() {
        SLog.sink = null
    }

    @Test
    fun `generated identity has a mesh peer id and a verifiable announcement`() {
        val identity = RelayIdentity.generate()
        assertEquals(RelayIdentity.KEY_SIZE, identity.noisePublicKey.size)
        assertEquals(RelayIdentity.KEY_SIZE, identity.signingPublicKey.size)
        assertEquals(16, identity.peerId.length)

        val packet = identity.signedAnnouncement("Relay", 7u, timestampMs = 1_700_000_000_000L)
        assertEquals(MessageType.ANNOUNCE.value, packet.type)
        assertEquals(identity.peerId, packet.senderID.joinToString("") { "%02x".format(it) })
        assertNotNull(packet.signature)

        val decoded = AnnouncementIdentityValidator.verify(
            packet = packet,
            claimedPeerID = identity.peerId,
            nowMs = 1_700_000_000_000L,
        ) { signature, data, publicKey -> identity.verify(signature, data, publicKey) }
        assertNotNull(decoded)
    }

    @Test
    fun `identity round trips through the on disk store`() {
        val store = RelayIdentityStore(folder.root.toPath())
        val first = store.loadOrCreate()
        val second = store.loadOrCreate()

        assertEquals(first.peerId, second.peerId)
        assertEquals(first.noisePrivateKey.toList(), second.noisePrivateKey.toList())
        assertEquals(first.noisePublicKey.toList(), second.noisePublicKey.toList())
        assertEquals(first.signingPrivateKey.toList(), second.signingPrivateKey.toList())
        assertEquals(first.signingPublicKey.toList(), second.signingPublicKey.toList())
    }

    @Test
    fun `reset identity creates a new mesh identity`() {
        val store = RelayIdentityStore(folder.root.toPath())
        val first = store.loadOrCreate()
        val reset = store.loadOrCreate(reset = true)

        assertNotEquals(first.peerId, reset.peerId)
    }

    @Test
    fun `partial identity is rejected instead of silently rotating keys`() {
        val directory = folder.root.toPath()
        val store = RelayIdentityStore(directory)
        store.loadOrCreate()
        Files.delete(directory.resolve("ed25519_public.bin"))

        val error = runCatching { store.loadOrCreate() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("incomplete"))
    }

    @Test
    fun `identity files are not world readable on posix`() {
        org.junit.Assume.assumeTrue(
            System.getProperty("os.name").lowercase().contains("linux"),
        )
        val directory = folder.root.toPath()
        RelayIdentityStore(directory).loadOrCreate()
        val permissions = Files.getPosixFilePermissions(directory.resolve("noise_private.bin"))
        assertTrue(permissions.none { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") })
    }
}
