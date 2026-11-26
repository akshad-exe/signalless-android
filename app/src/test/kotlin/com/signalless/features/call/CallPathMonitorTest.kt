package com.signalless.app.features.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPathMonitorTest {
    private val callId = ByteArray(CallSignal.CALL_ID_SIZE) { it.toByte() }
    private val link = FakeCallMeshLink().apply {
        bleDirect = mutableSetOf("peer", "b")
        path = listOf("me", "b", "peer")
    }
    private val transport = CallTransport(link, "peer", CallSignal.V1_PATHS).apply {
        switchTo(CallPath.DirectBle)
    }
    private val monitor = CallPathMonitor(transport, callId, 0L)
    private val routed = CallPath.RoutedBle(listOf("b"))

    private fun signal(action: CallAction, bit: Int, reply: Boolean = false) = CallSignal(
        action, callId, flags = if (reply) CallPathMonitor.FLAG_REPLY else 0, pathMask = bit
    )

    @Test
    fun keepsAliveTheCurrentPathAndProbesTheOthers() {
        monitor.tick(0L)
        assertEquals(
            listOf(
                CallSignal.PATH_DIRECT_BLE to CallAction.KEEPALIVE,
                CallSignal.PATH_ROUTED_BLE to CallAction.PATH_PROBE
            ),
            link.signals.map { it.first to it.second.action }
        )
    }

    @Test
    fun repliesToAProbeOnTheSamePath() {
        monitor.onSignal(signal(CallAction.PATH_PROBE, CallSignal.PATH_ROUTED_BLE), 100L)
        val (bit, reply) = link.signals.single()
        assertEquals(CallSignal.PATH_ROUTED_BLE, bit)
        assertEquals(CallPathMonitor.FLAG_REPLY, reply.flags)
    }

    @Test
    fun startsOnThePathThatAnsweredFastest() {
        monitor.tick(0L)
        monitor.onSignal(signal(CallAction.PATH_PROBE, CallSignal.PATH_ROUTED_BLE, true), 80L)
        monitor.onSignal(signal(CallAction.KEEPALIVE, CallSignal.PATH_DIRECT_BLE, true), 300L)
        monitor.tick(1_000L)
        assertEquals(routed, transport.path)
    }

    @Test
    fun sustainedLossSwitchesPathAndTellsThePeer() {
        var seq = 0
        for (second in 1..3) {
            // Half of the frames go missing.
            monitor.onAudio(seq, 3, second * 1_000L - 500)
            monitor.onAudio(seq + 6, 3, second * 1_000L - 400)
            seq += 12
            assertEquals(second == 3, monitor.tick(second * 1_000L))
        }
        assertEquals(routed, transport.path)
        assertTrue(link.signals.any { it.second.action == CallAction.PATH_SWITCH })

        monitor.onAudio(seq, 3, 3_500L)
        // The first packet after the switch still counts the old gap.
        monitor.tick(4_000L)
        monitor.onAudio(seq + 3, 3, 4_500L)
        assertFalse(monitor.tick(5_000L))
    }

    @Test
    fun lightLossAndLatePacketsKeepThePath() {
        for (second in 1..5) {
            val base = (second - 1) * 30
            // Frames arrive out of order but none are missing.
            for (index in listOf(0, 2, 1, 3, 4, 5, 6, 7, 8, 9)) {
                monitor.onAudio(base + index * 3, 3, second * 1_000L - 100)
            }
            assertFalse(monitor.tick(second * 1_000L))
        }
        assertEquals(CallPath.DirectBle, transport.path)
    }

    @Test
    fun silenceMeansReconnectingUntilThePeerIsHeardAgain() {
        for (second in 1..4) assertFalse(monitor.tick(second * 1_000L))
        assertTrue(monitor.tick(5_000L))
        assertEquals(routed, transport.path)
        assertTrue(monitor.tick(6_000L))
        monitor.onSignal(signal(CallAction.KEEPALIVE, CallSignal.PATH_ROUTED_BLE, true), 6_500L)
        assertFalse(monitor.tick(7_000L))
    }

    @Test
    fun aMutedPeerRecoversEvenAfterALossySecond() {
        for (second in 1..5) monitor.tick(second * 1_000L)
        monitor.onAudio(0, 3, 5_100L)
        monitor.onAudio(30, 3, 5_200L)
        assertTrue(monitor.tick(6_000L))
        // Muted from here: only keepalives arrive.
        monitor.onSignal(signal(CallAction.KEEPALIVE, CallSignal.PATH_ROUTED_BLE, true), 6_500L)
        assertFalse(monitor.tick(7_000L))
    }

    @Test
    fun peerRequestedSwitchIsFollowed() {
        monitor.onSignal(signal(CallAction.PATH_SWITCH, CallSignal.PATH_ROUTED_BLE), 100L)
        assertEquals(routed, transport.path)
    }
}
