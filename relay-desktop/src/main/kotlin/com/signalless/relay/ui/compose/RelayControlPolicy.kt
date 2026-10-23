package com.signalless.relay.ui.compose

import com.signalless.relay.ui.RelayPhase

/**
 * Button enablement from UI-PLAN §7.7.
 *
 * Restart is disabled while `STARTING` even though the plan table lists it:
 * the controller deliberately ignores restart during a transition, and a
 * button that silently does nothing is worse than one that waits for the
 * session to settle.
 */
internal object RelayControlPolicy {
    fun canStart(phase: RelayPhase): Boolean =
        phase == RelayPhase.STOPPED || phase == RelayPhase.FAILED

    fun canStop(phase: RelayPhase): Boolean =
        phase == RelayPhase.STARTING || phase == RelayPhase.ADVERTISING

    fun canRestart(phase: RelayPhase): Boolean =
        phase == RelayPhase.ADVERTISING || phase == RelayPhase.FAILED || phase == RelayPhase.STOPPED
}
