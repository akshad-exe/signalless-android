package com.signalless.app.features.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallSignalV2Test {
    private val callId = ByteArray(CallSignal.CALL_ID_SIZE) { (it + 0x10).toByte() }

    private fun vector(action: Int) =
        byteArrayOf(0x02, action.toByte()) + callId + byteArrayOf(0x00, 0x03, 0x07)

    @Test
    fun `golden vector for every version 2 action`() {
        val ids = mapOf(
            CallAction.INVITE to 1, CallAction.ACCEPT to 2, CallAction.DECLINE to 3,
            CallAction.HANGUP to 4, CallAction.BUSY to 5, CallAction.RINGING to 6,
            CallAction.HOLD to 7, CallAction.RESUME to 8, CallAction.PATH_PROBE to 9,
            CallAction.PATH_SWITCH to 10, CallAction.KEEPALIVE to 11
        )
        assertEquals(CallAction.entries.toSet(), ids.keys)
        ids.forEach { (action, id) ->
            val signal = CallSignal(action, callId, codecMask = 0x03, pathMask = 0x07)
            assertArrayEquals(vector(id), signal.encode())
            assertEquals(signal, CallSignal.decode(vector(id)))
        }
    }

    @Test
    fun `version 1 decodes as AAC over BLE and encodes unchanged`() {
        val bytes = byteArrayOf(0x01, 0x01) + callId
        val decoded = CallSignal.decode(bytes)!!
        assertEquals(CallSignal.VERSION_1, decoded.version)
        assertEquals(CallCodec.AAC_LC.mask, decoded.codecMask)
        assertEquals(CallSignal.V1_PATHS, decoded.pathMask)
        assertArrayEquals(bytes, decoded.encode())
    }

    @Test
    fun `a version 1 invite is answered in version 1`() {
        val session = CallSession()
        session.onRemote("aaaaaaaaaaaaaaaa", CallSignal(CallAction.INVITE, callId, CallSignal.VERSION_1), 0L)
        assertEquals(CallSignal.VERSION_1, session.accept(1L).single().signal.version)
        assertEquals(CallSignal.VERSION_1, session.hangup(2L).single().signal.version)
    }

    @Test
    fun `mismatched length version and action are rejected`() {
        assertNull(CallSignal.decode(vector(1).copyOf(CallSignal.V1_SIZE)))
        assertNull(CallSignal.decode(byteArrayOf(0x01, 0x01) + callId + ByteArray(3)))
        assertNull(CallSignal.decode(vector(1).copyOf(CallSignal.V2_SIZE + 1)))
        assertNull(CallSignal.decode(vector(1).also { it[0] = 3 }))
        assertNull(CallSignal.decode(vector(12)))
        assertNull(CallSignal.decode(byteArrayOf(0x01, 0x06) + callId))
    }
}
