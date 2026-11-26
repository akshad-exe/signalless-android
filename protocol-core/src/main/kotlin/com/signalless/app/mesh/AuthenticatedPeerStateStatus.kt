package com.signalless.app.mesh

import com.signalless.app.model.AuthenticatedPeerState

/**
 * Freshness of a peer-state proof.
 *
 * Lives in the portable core because [PrivateMediaSecurity] needs it without
 * pulling in the Android-bound [AuthenticatedPeerStateCoordinator].
 */
sealed interface AuthenticatedPeerStateStatus {
    data object Missing : AuthenticatedPeerStateStatus
    data object Awaiting : AuthenticatedPeerStateStatus
    data object TimedOut : AuthenticatedPeerStateStatus
    data class Proven(val state: AuthenticatedPeerState) : AuthenticatedPeerStateStatus
}
