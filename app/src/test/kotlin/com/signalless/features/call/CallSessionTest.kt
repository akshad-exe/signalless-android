package com.signalless.app.features.call

import com.signalless.app.model.NoisePayload
import com.signalless.app.model.NoisePayloadType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSessionTest {
    private val alice = "aaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbb"
    private val carol = "cccccccccccccccc"
    private val lowId = ByteArray(CallSignal.CALL_ID_SIZE) { 0x01 }
    private val highId = ByteArray(CallSignal.CALL_ID_SIZE) { 0x02 }

    @Test
    fun `signal round trip keeps the action and call id`() {
        val signal = CallSignal(CallAction.INVITE, highId)
        val decoded = CallSignal.decode(signal.encode())
        assertEquals(signal, decoded)
    }

    @Test
    fun `call signal golden vector pins version action and call id`() {
        val expected = byteArrayOf(
            0x01, 0x04,
            0x10, 0x32, 0x54, 0x76,
            0x98.toByte(), 0xba.toByte(), 0xdc.toByte(), 0xfe.toByte(),
            0x01, 0x23, 0x45, 0x67,
            0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte()
        )
        val callId = expected.copyOfRange(2, expected.size)
        assertArrayEquals(expected, CallSignal(CallAction.HANGUP, callId, version = 1).encode())
        val decoded = CallSignal.decode(expected)
        assertEquals(CallAction.HANGUP, decoded?.action)
        assertArrayEquals(callId, decoded?.callId)

        val actionBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val actions = arrayOf(
            CallAction.INVITE,
            CallAction.ACCEPT,
            CallAction.DECLINE,
            CallAction.HANGUP,
            CallAction.BUSY
        )
        actions.forEachIndexed { index, action ->
            val vector = expected.copyOf()
            vector[1] = actionBytes[index]
            assertArrayEquals(vector, CallSignal(action, callId, version = 1).encode())
            assertEquals(action, CallSignal.decode(vector)?.action)
        }
    }

    @Test
    fun `call signal uses a dedicated Noise payload type`() {
        val encoded = NoisePayload(
            NoisePayloadType.CALL_SIGNAL,
            CallSignal(CallAction.INVITE, lowId).encode()
        ).encode()
        assertEquals(0x30, encoded[0].toInt() and 0xFF)
        val decoded = NoisePayload.decode(encoded)
        assertEquals(NoisePayloadType.CALL_SIGNAL, decoded?.type)
        assertEquals(CallAction.INVITE, CallSignal.decode(decoded!!.data)?.action)
    }

    @Test
    fun `place rings until the peer accepts`() {
        val session = CallSession()
        val invite = session.place(bob, highId, nowMs = 1_000L)
        assertEquals(CallPhase.OUTGOING, session.snapshot().phase)
        assertEquals(CallAction.INVITE, invite.single().signal.action)
        assertArrayEquals(highId, invite.single().signal.callId)

        assertTrue(session.tick(1_000L + CallSession.RING_TIMEOUT_MS - 1L).isEmpty())
        assertTrue(session.onRemote(bob, CallSignal(CallAction.ACCEPT, lowId), 2_000L).isEmpty())
        assertEquals(CallPhase.OUTGOING, session.snapshot().phase)

        session.onRemote(bob, CallSignal(CallAction.ACCEPT, highId), 3_000L)
        assertEquals(CallPhase.ACTIVE, session.snapshot().phase)
        assertEquals(3_000L, session.snapshot().connectedAtMs)
    }

    @Test
    fun `incoming invite can be accepted or declined`() {
        val session = CallSession()
        assertTrue(
            session.onRemote(alice, CallSignal(CallAction.INVITE, lowId), 500L).isEmpty()
        )
        assertEquals(CallPhase.INCOMING, session.snapshot().phase)
        assertTrue(session.place(bob, highId, 600L).isEmpty())
        assertEquals(CallPhase.INCOMING, session.snapshot().phase)

        val accept = session.accept(800L)
        assertEquals(CallAction.ACCEPT, accept.single().signal.action)
        assertEquals(alice, accept.single().peerID)
        assertEquals(CallPhase.ACTIVE, session.snapshot().phase)
    }

    @Test
    fun `decline and remote hangup end the matching call only`() {
        val session = CallSession()
        session.onRemote(alice, CallSignal(CallAction.INVITE, lowId), 100L)
        val decline = session.decline(200L)
        assertEquals(CallAction.DECLINE, decline.single().signal.action)
        assertEquals(CallEndReason.DECLINED, session.snapshot().endReason)
        assertEquals(CallPhase.IDLE, session.snapshot().phase)
        assertEquals(alice, session.snapshot().peerID)

        session.acknowledgeEnd()
        session.place(bob, highId, 300L)
        session.onRemote(bob, CallSignal(CallAction.ACCEPT, highId), 400L)
        assertTrue(
            session.onRemote(carol, CallSignal(CallAction.HANGUP, highId), 500L).isEmpty()
        )
        assertEquals(CallPhase.ACTIVE, session.snapshot().phase)
        session.onRemote(bob, CallSignal(CallAction.HANGUP, highId), 600L)
        assertEquals(CallEndReason.HUNG_UP, session.snapshot().endReason)
        assertEquals(CallPhase.IDLE, session.snapshot().phase)
    }

    @Test
    fun `a second caller is told this peer is busy`() {
        val session = CallSession()
        session.place(bob, highId, 1_000L)
        val busy = session.onRemote(carol, CallSignal(CallAction.INVITE, lowId), 1_100L)
        assertEquals(carol, busy.single().peerID)
        assertEquals(CallAction.BUSY, busy.single().signal.action)
        assertArrayEquals(lowId, busy.single().signal.callId)
        assertEquals(CallPhase.OUTGOING, session.snapshot().phase)
        assertArrayEquals(highId, session.snapshot().callId)
    }

    @Test
    fun `simultaneous invites activate both sides on the lower call id`() {
        val caller = CallSession()
        val callee = CallSession()
        caller.place(bob, highId, 1_000L)
        callee.place(alice, lowId, 1_000L)

        assertTrue(caller.onRemote(bob, CallSignal(CallAction.INVITE, lowId), 1_100L).isEmpty())
        assertTrue(callee.onRemote(alice, CallSignal(CallAction.INVITE, highId), 1_100L).isEmpty())

        assertEquals(CallPhase.ACTIVE, caller.snapshot().phase)
        assertEquals(CallPhase.ACTIVE, callee.snapshot().phase)
        assertArrayEquals(lowId, caller.snapshot().callId)
        assertArrayEquals(lowId, callee.snapshot().callId)

        val hangup = caller.hangup(2_000L)
        callee.onRemote(alice, hangup.single().signal, 2_100L)
        assertEquals(CallEndReason.HUNG_UP, caller.snapshot().endReason)
        assertEquals(CallEndReason.HUNG_UP, callee.snapshot().endReason)
    }

    @Test
    fun `unanswered rings time out`() {
        val outgoing = CallSession()
        outgoing.place(bob, highId, 10_000L)
        val ended = outgoing.tick(10_000L + CallSession.RING_TIMEOUT_MS)
        assertEquals(CallAction.HANGUP, ended.single().signal.action)
        assertEquals(CallEndReason.TIMEOUT, outgoing.snapshot().endReason)

        val incoming = CallSession()
        incoming.onRemote(alice, CallSignal(CallAction.INVITE, lowId), 20_000L)
        val declined = incoming.tick(20_000L + CallSession.RING_TIMEOUT_MS)
        assertEquals(CallAction.DECLINE, declined.single().signal.action)
        assertEquals(CallEndReason.TIMEOUT, incoming.snapshot().endReason)
    }

    @Test
    fun `remote busy and decline stop an outgoing ring`() {
        val busy = CallSession()
        busy.place(bob, highId, 1L)
        busy.onRemote(bob, CallSignal(CallAction.BUSY, highId), 2L)
        assertEquals(CallEndReason.BUSY, busy.snapshot().endReason)

        val declined = CallSession()
        declined.place(bob, highId, 1L)
        declined.onRemote(bob, CallSignal(CallAction.DECLINE, highId), 2L)
        assertEquals(CallEndReason.DECLINED, declined.snapshot().endReason)
        assertTrue(declined.hangup(3L).isEmpty())
    }
}
