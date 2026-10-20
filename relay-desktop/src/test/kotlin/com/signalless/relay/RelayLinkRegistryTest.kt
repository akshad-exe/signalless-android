package com.signalless.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayLinkRegistryTest {
    private val peerA = "aaaaaaaaaaaaaaaa"
    private val peerB = "bbbbbbbbbbbbbbbb"

    @Test
    fun `adds and removes links`() {
        val registry = RelayLinkRegistry()

        assertTrue(registry.add("link-1", 517, 514))
        assertEquals(1, registry.size())
        assertEquals(517, registry.link("link-1")?.mtu)

        assertEquals("link-1", registry.remove("link-1")?.id)
        assertEquals(0, registry.size())
        assertNull(registry.link("link-1"))
    }

    @Test
    fun `rejects invalid link parameters`() {
        val registry = RelayLinkRegistry()

        assertFalse(registry.add("", 517, 514))
        assertFalse(registry.add("link-1", 22, 20))
        assertFalse(registry.add("link-1", 517, 0))
        assertFalse(registry.add("link-1", 517, 518))
    }

    @Test
    fun `binds a normalized peer id to a link`() {
        val registry = RelayLinkRegistry()
        registry.add("link-1", 517, 514)

        assertTrue(registry.bindPeer("link-1", peerA.uppercase()))
        assertEquals(peerA, registry.peerFor("link-1"))
    }

    @Test
    fun `rejects malformed peer ids and unknown links`() {
        val registry = RelayLinkRegistry()
        registry.add("link-1", 517, 514)

        assertFalse(registry.bindPeer("link-1", "not-a-peer"))
        assertFalse(registry.bindPeer("missing", peerA))
        assertNull(registry.peerFor("link-1"))
    }

    @Test
    fun `replacing a link preserves its learned peer id`() {
        val registry = RelayLinkRegistry()
        registry.add("link-1", 517, 514)
        registry.bindPeer("link-1", peerA)

        assertTrue(registry.add("link-1", 185, 182))

        assertEquals(peerA, registry.peerFor("link-1"))
        assertEquals(185, registry.link("link-1")?.mtu)
    }

    @Test
    fun `finds links by peer and excludes the ingress link`() {
        val registry = RelayLinkRegistry()
        registry.add("link-1", 517, 514)
        registry.add("link-2", 517, 514)
        registry.add("link-3", 517, 514)
        registry.bindPeer("link-1", peerA)
        registry.bindPeer("link-2", peerB)
        registry.bindPeer("link-3", peerB)

        assertEquals(listOf("link-2", "link-3"), registry.linksForPeer(peerB).map { it.id })
        assertEquals(listOf("link-2", "link-3"), registry.allExcept("link-1").map { it.id })
    }

    @Test
    fun `normalizes only sixteen hex characters`() {
        assertEquals(peerA, RelayLinkRegistry.normalizePeerId(peerA.uppercase()))
        assertNull(RelayLinkRegistry.normalizePeerId("aaaaaaaaaaaaaaa"))
        assertNull(RelayLinkRegistry.normalizePeerId("zzzzzzzzzzzzzzzz"))
    }
}
