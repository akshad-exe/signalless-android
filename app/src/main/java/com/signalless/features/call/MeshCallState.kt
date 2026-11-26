package com.signalless.app.features.call

/** What the call screens show, published by the call service. */
data class MeshCallState(
    val phase: CallPhase = CallPhase.IDLE,
    val peerID: String? = null,
    val nickname: String = "",
    val connectedAtMs: Long? = null,
    val endReason: CallEndReason? = null,
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    val connecting: Boolean = false,
    val onHold: Boolean = false,
    val reconnecting: Boolean = false
) {
    val visible: Boolean
        get() = connecting || phase != CallPhase.IDLE || endReason != null
}
