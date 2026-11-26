package com.signalless.app.features.call

/**
 * One local mesh call. Signaling is explicit: every transition that the peer
 * must observe returns a [CallEffect]. Malformed or mismatched signals leave
 * the current call unchanged.
 */
class CallSession(
    private val ringTimeoutMs: Long = RING_TIMEOUT_MS,
    private val localCodecMask: Int = CallCodec.AAC_LC.mask
) {
    class Snapshot(
        val phase: CallPhase,
        val peerID: String?,
        val callId: ByteArray?,
        val connectedAtMs: Long?,
        val endReason: CallEndReason?,
        /** Negotiated codec, known once the peer's invite or accept arrived. */
        val codec: CallCodec? = null,
        /** True while either side has the call on hold. */
        val onHold: Boolean = false
    )

    data class Effect(val peerID: String, val signal: CallSignal)

    private var phase = CallPhase.IDLE
    private var peerID: String? = null
    private var callId: ByteArray? = null
    private var phaseEnteredAtMs = 0L
    private var connectedAtMs: Long? = null
    private var endReason: CallEndReason? = null
    /** Version to answer in: a version 1 caller cannot decode version 2. */
    private var replyVersion = CallSignal.VERSION_2
    private var remoteCodecMask = CallCodec.AAC_LC.mask
    private var localHold = false
    private var remoteHold = false

    fun snapshot(): Snapshot = Snapshot(
        phase = phase,
        peerID = peerID,
        callId = callId?.copyOf(),
        connectedAtMs = connectedAtMs,
        endReason = endReason,
        codec = if (phase == CallPhase.ACTIVE) {
            VoiceCodecs.negotiate(localCodecMask, remoteCodecMask) ?: CallCodec.AAC_LC
        } else null,
        onHold = localHold || remoteHold
    )

    /** Holds or resumes this side, for example while a cellular call is answered. */
    fun hold(held: Boolean): List<Effect> {
        if (phase != CallPhase.ACTIVE || localHold == held) return emptyList()
        val peer = peerID ?: return emptyList()
        val id = callId ?: return emptyList()
        localHold = held
        // A version 1 peer has no hold signal; its audio just goes quiet.
        if (replyVersion == CallSignal.VERSION_1) return emptyList()
        val action = if (held) CallAction.HOLD else CallAction.RESUME
        return listOf(Effect(peer, signal(action, id)))
    }

    private fun signal(action: CallAction, id: ByteArray) =
        CallSignal(action, id.copyOf(), replyVersion, codecMask = localCodecMask)

    fun place(peerID: String, newCallId: ByteArray, nowMs: Long): List<Effect> {
        if (phase != CallPhase.IDLE) return emptyList()
        if (peerID.isEmpty() || newCallId.size != CallSignal.CALL_ID_SIZE) return emptyList()
        this.peerID = peerID
        callId = newCallId.copyOf()
        phase = CallPhase.OUTGOING
        phaseEnteredAtMs = nowMs
        connectedAtMs = null
        endReason = null
        replyVersion = CallSignal.VERSION_2
        remoteCodecMask = CallCodec.AAC_LC.mask
        clearHold()
        return listOf(Effect(peerID, signal(CallAction.INVITE, newCallId)))
    }

    fun accept(nowMs: Long): List<Effect> {
        if (phase != CallPhase.INCOMING) return emptyList()
        val peer = peerID ?: return emptyList()
        val id = callId ?: return emptyList()
        phase = CallPhase.ACTIVE
        connectedAtMs = nowMs
        phaseEnteredAtMs = nowMs
        endReason = null
        return listOf(Effect(peer, signal(CallAction.ACCEPT, id)))
    }

    fun decline(nowMs: Long): List<Effect> {
        if (phase != CallPhase.INCOMING) return emptyList()
        return finish(CallAction.DECLINE, CallEndReason.DECLINED, nowMs)
    }

    fun hangup(nowMs: Long): List<Effect> {
        if (phase == CallPhase.INCOMING) return decline(nowMs)
        if (phase != CallPhase.OUTGOING && phase != CallPhase.ACTIVE) return emptyList()
        return finish(CallAction.HANGUP, CallEndReason.HUNG_UP, nowMs)
    }

    fun onRemote(fromPeerID: String, signal: CallSignal, nowMs: Long): List<Effect> {
        if (fromPeerID.isEmpty() || signal.callId.size != CallSignal.CALL_ID_SIZE) return emptyList()
        return when (signal.action) {
            CallAction.INVITE -> onInvite(fromPeerID, signal, nowMs)
            CallAction.ACCEPT -> {
                if (matches(fromPeerID, signal) && phase == CallPhase.OUTGOING) {
                    remoteCodecMask = signal.codecMask
                    phase = CallPhase.ACTIVE
                    connectedAtMs = nowMs
                    phaseEnteredAtMs = nowMs
                    endReason = null
                }
                emptyList()
            }
            CallAction.DECLINE -> {
                if (matches(fromPeerID, signal) && phase == CallPhase.OUTGOING) {
                    end(CallEndReason.DECLINED)
                }
                emptyList()
            }
            CallAction.HANGUP -> {
                if (matches(fromPeerID, signal) && phase != CallPhase.IDLE) {
                    end(CallEndReason.HUNG_UP)
                }
                emptyList()
            }
            CallAction.BUSY -> {
                if (matches(fromPeerID, signal) && phase == CallPhase.OUTGOING) {
                    end(CallEndReason.BUSY)
                }
                emptyList()
            }
            CallAction.HOLD, CallAction.RESUME -> {
                if (matches(fromPeerID, signal) && phase == CallPhase.ACTIVE) {
                    remoteHold = signal.action == CallAction.HOLD
                }
                emptyList()
            }
            CallAction.RINGING, CallAction.PATH_PROBE,
            CallAction.PATH_SWITCH, CallAction.KEEPALIVE -> emptyList()
        }
    }

    fun tick(nowMs: Long): List<Effect> {
        if (phase != CallPhase.OUTGOING && phase != CallPhase.INCOMING) return emptyList()
        if (nowMs - phaseEnteredAtMs < ringTimeoutMs) return emptyList()
        val action = if (phase == CallPhase.INCOMING) CallAction.DECLINE else CallAction.HANGUP
        return finish(action, CallEndReason.TIMEOUT, nowMs)
    }

    /** Local failure before a signal exists, such as a missing session or microphone. */
    fun markEnded(peerID: String, reason: CallEndReason) {
        if (phase != CallPhase.IDLE || peerID.isEmpty()) return
        this.peerID = peerID
        callId = null
        connectedAtMs = null
        endReason = reason
    }

    fun acknowledgeEnd() {
        if (phase != CallPhase.IDLE) return
        endReason = null
        peerID = null
        callId = null
        connectedAtMs = null
    }

    private fun onInvite(fromPeerID: String, signal: CallSignal, nowMs: Long): List<Effect> {
        if (phase == CallPhase.IDLE) {
            peerID = fromPeerID
            callId = signal.callId.copyOf()
            phase = CallPhase.INCOMING
            replyVersion = signal.version
            remoteCodecMask = signal.codecMask
            clearHold()
            phaseEnteredAtMs = nowMs
            connectedAtMs = null
            endReason = null
            return emptyList()
        }
        if (fromPeerID == peerID && phase == CallPhase.OUTGOING) {
            callId = lesserCallId(callId, signal.callId)
            remoteCodecMask = signal.codecMask
            phase = CallPhase.ACTIVE
            connectedAtMs = nowMs
            phaseEnteredAtMs = nowMs
            endReason = null
            return emptyList()
        }
        if (fromPeerID == peerID) return emptyList()
        return listOf(Effect(fromPeerID, CallSignal(CallAction.BUSY, signal.callId.copyOf(), signal.version)))
    }

    private fun matches(fromPeerID: String, signal: CallSignal): Boolean {
        val id = callId ?: return false
        return fromPeerID == peerID && id.contentEquals(signal.callId)
    }

    private fun finish(action: CallAction, reason: CallEndReason, nowMs: Long): List<Effect> {
        val peer = peerID ?: return emptyList()
        val id = callId ?: return emptyList()
        end(reason)
        phaseEnteredAtMs = nowMs
        return listOf(Effect(peer, signal(action, id)))
    }

    private fun clearHold() {
        localHold = false
        remoteHold = false
    }

    private fun end(reason: CallEndReason) {
        clearHold()
        phase = CallPhase.IDLE
        endReason = reason
        callId = null
        connectedAtMs = null
    }

    companion object {
        const val RING_TIMEOUT_MS = 45_000L

        fun lesserCallId(left: ByteArray?, right: ByteArray): ByteArray {
            val current = left ?: return right.copyOf()
            for (index in 0 until CallSignal.CALL_ID_SIZE) {
                val leftByte = current[index].toInt() and 0xFF
                val rightByte = right[index].toInt() and 0xFF
                if (leftByte < rightByte) return current.copyOf()
                if (rightByte < leftByte) return right.copyOf()
            }
            return current.copyOf()
        }
    }
}
