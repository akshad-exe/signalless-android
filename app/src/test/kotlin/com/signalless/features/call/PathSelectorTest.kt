package com.signalless.app.features.call

import com.signalless.app.model.NoisePayloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathSelectorTest {
    private val link = FakeCallMeshLink()
    private val all = CallSignal.PATH_WIFI_AWARE or CallSignal.V1_PATHS

    private fun paths(mask: Int = all) = PathSelector(link).available("peer", mask)

    @Test
    fun ordersWifiAwareThenDirectThenRouted() {
        link.wifi = true
        link.bleDirect = mutableSetOf("peer", "b")
        link.path = listOf("me", "b", "peer")
        assertEquals(
            listOf(CallPath.WifiAware, CallPath.DirectBle, CallPath.RoutedBle(listOf("b"))),
            paths()
        )
    }

    @Test
    fun skipsPathsThePeerDoesNotSupport() {
        link.wifi = true
        link.bleDirect = mutableSetOf("peer")
        assertEquals(listOf(CallPath.DirectBle), paths(CallSignal.V1_PATHS))
    }

    @Test
    fun allowsThreeHopsButNotFour() {
        link.bleDirect = mutableSetOf("b")
        link.path = listOf("me", "b", "c", "peer")
        assertEquals(listOf(CallPath.RoutedBle(listOf("b", "c"))), paths())
        link.path = listOf("me", "b", "c", "d", "peer")
        assertTrue(paths().isEmpty())
    }

    @Test
    fun routeNeedsAConnectedFirstHopAndASession() {
        link.path = listOf("me", "b", "peer")
        assertTrue(paths().isEmpty())
        link.bleDirect = mutableSetOf("b")
        link.session = false
        assertTrue(paths().isEmpty())
    }

    @Test
    fun transportStaysOnItsPathUntilItFails() {
        link.bleDirect = mutableSetOf("peer", "b")
        link.path = listOf("me", "b", "peer")
        val transport = CallTransport(link, "peer", all)
        assertTrue(transport.send(byteArrayOf(1)))
        link.wifi = true
        assertTrue(transport.send(byteArrayOf(2)))
        assertEquals(CallPath.DirectBle, transport.path)

        link.bleDirect.remove("peer")
        link.wifi = false
        assertTrue(transport.send(byteArrayOf(3)))
        assertEquals(CallPath.RoutedBle(listOf("b")), transport.path)

        link.bleDirect.clear()
        assertFalse(transport.send(byteArrayOf(4)))
        assertEquals(null, transport.path)
    }
}

internal class FakeCallMeshLink : CallMeshLink {
    var wifi = false
    var session = true
    var bleDirect = mutableSetOf<String>()
    var path: List<String>? = null

    override fun isWifiAwareDirect(peerID: String) = wifi
    override fun isBleDirect(peerID: String) = peerID in bleDirect
    override fun hasBleSession(peerID: String) = session
    override fun meshPath(peerID: String) = path
    /** Path signals sent, with the path they left on. */
    val signals = mutableListOf<Pair<Int, CallSignal>>()

    override fun sendWifiAwareCall(peerID: String, type: NoisePayloadType, payload: ByteArray) =
        wifi.also { if (it) record(CallSignal.PATH_WIFI_AWARE, type, payload) }

    override fun sendBleCall(
        peerID: String,
        hops: List<String>,
        type: NoisePayloadType,
        payload: ByteArray
    ) = ((hops.firstOrNull() ?: peerID) in bleDirect).also {
        val bit = if (hops.isEmpty()) CallSignal.PATH_DIRECT_BLE else CallSignal.PATH_ROUTED_BLE
        if (it) record(bit, type, payload)
    }

    private fun record(bit: Int, type: NoisePayloadType, payload: ByteArray) {
        if (type == NoisePayloadType.CALL_SIGNAL) signals += bit to CallSignal.decode(payload)!!
    }
}
