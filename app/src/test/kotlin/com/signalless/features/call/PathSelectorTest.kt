package com.signalless.app.features.call

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
    override fun sendWifiAwareCallAudio(peerID: String, payload: ByteArray) = wifi
    override fun sendBleCallAudio(peerID: String, hops: List<String>, payload: ByteArray) =
        (hops.firstOrNull() ?: peerID) in bleDirect
}
