package com.signalless.app.features.call

import com.signalless.app.mesh.PacketRelayManager
import com.signalless.app.mesh.PacketRelayManagerDelegate
import com.signalless.app.model.RoutedPacket
import com.signalless.app.protocol.BinaryProtocol
import com.signalless.app.protocol.SignalLessPacket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallTransportRoutingTest {
    private fun id(hex: String) = ByteArray(8) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val a = "1111111111111111"
    private val b = "2222222222222222"
    private val c = "3333333333333333"
    private val d = "4444444444444444"

    private fun packet(vararg hops: String, size: Int = 32) =
        callPacket(id(a), id(d), ByteArray(size) { it.toByte() }, hops.map(::id), 1_000L)

    @Test
    fun directAudioCannotBeRelayed() {
        val direct = packet()
        assertEquals(0, direct.ttl.toInt())
        assertNull(direct.route)
    }

    @Test
    fun routedAudioTtlCoversExactlyTheRoute() {
        val routed = packet(b, c)
        assertEquals(2, routed.ttl.toInt())
        assertEquals(2, routed.version.toInt())
        assertArrayEquals(id(b), routed.route!!.first())
    }

    /** Noise adds a 4 byte nonce, a 16 byte tag and the payload type to the audio packet. */
    @Test
    fun largestAudioPacketIsNeverFragmented() {
        val ciphertext = CallAudioPacket.MAX_SIZE + 21
        val signed = packet(b, c, size = ciphertext).copy(signature = ByteArray(64) { 7 })
        val encoded = BinaryProtocol.encode(signed, padding = true)!!
        assertTrue("encoded ${encoded.size}", encoded.size <= 512)
    }

    @Test
    fun relaysForwardAlongTheRouteOnly() {
        val first = Relay(b)
        first.relay(packet(b, c))
        assertEquals(listOf(c), first.unicasts.map { it.first })
        val second = Relay(c)
        second.relay(first.unicasts.single().second)
        assertEquals(listOf(d), second.unicasts.map { it.first })
        assertEquals(0, second.unicasts.single().second.ttl.toInt())
        assertEquals(0, first.broadcasts + second.broadcasts)
    }

    @Test
    fun relayThatLostTheNextHopDropsCallAudio() {
        val relay = Relay(b, connected = false)
        relay.relay(packet(b, c))
        assertEquals(0, relay.broadcasts)
    }

    @Test
    fun otherRoutedTrafficStillFallsBackToFlooding() {
        val relay = Relay(b, connected = false)
        relay.relay(packet(b, c).copy(ttl = 7u))
        assertEquals(1, relay.broadcasts)
    }

    private class Relay(private val me: String, private val connected: Boolean = true) {
        val unicasts = mutableListOf<Pair<String, SignalLessPacket>>()
        var broadcasts = 0

        fun relay(packet: SignalLessPacket) = runBlocking {
            val manager = PacketRelayManager(me)
            manager.delegate = object : PacketRelayManagerDelegate {
                override fun getNetworkSize() = 3
                override fun getBroadcastRecipient() = ByteArray(8) { -1 }
                override fun broadcastPacket(routed: RoutedPacket) {
                    broadcasts++
                }

                override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
                    if (connected) unicasts += peerID to routed.packet
                    return connected
                }
            }
            manager.handlePacketRelay(RoutedPacket(packet, "1111111111111111"))
        }
    }
}
