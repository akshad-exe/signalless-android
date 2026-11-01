package com.signalless.relay.ui.compose

import com.signalless.relay.ui.RelayPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayControlPolicyTest {
    @Test
    fun `start is available only when stopped or failed`() {
        assertTrue(RelayControlPolicy.canStart(RelayPhase.STOPPED))
        assertTrue(RelayControlPolicy.canStart(RelayPhase.FAILED))
        assertFalse(RelayControlPolicy.canStart(RelayPhase.STARTING))
        assertFalse(RelayControlPolicy.canStart(RelayPhase.ADVERTISING))
        assertFalse(RelayControlPolicy.canStart(RelayPhase.STOPPING))
    }

    @Test
    fun `stop is available while starting or advertising`() {
        assertTrue(RelayControlPolicy.canStop(RelayPhase.STARTING))
        assertTrue(RelayControlPolicy.canStop(RelayPhase.ADVERTISING))
        assertFalse(RelayControlPolicy.canStop(RelayPhase.STOPPED))
        assertFalse(RelayControlPolicy.canStop(RelayPhase.FAILED))
        assertFalse(RelayControlPolicy.canStop(RelayPhase.STOPPING))
    }

    @Test
    fun `restart waits for a settled session`() {
        assertTrue(RelayControlPolicy.canRestart(RelayPhase.ADVERTISING))
        assertTrue(RelayControlPolicy.canRestart(RelayPhase.FAILED))
        assertTrue(RelayControlPolicy.canRestart(RelayPhase.STOPPED))
        assertFalse(RelayControlPolicy.canRestart(RelayPhase.STARTING))
        assertFalse(RelayControlPolicy.canRestart(RelayPhase.STOPPING))
    }
}
