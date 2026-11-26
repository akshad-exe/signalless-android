package com.signalless.app.features.call

/**
 * Call setup carried inside an encrypted Noise payload.
 *
 * Wire format, exactly 18 bytes: `[version: u8 = 1][action: u8][callId: 16]`.
 * Unknown versions, actions, and lengths are rejected. Older clients drop the
 * surrounding Noise payload type, so a call signal never changes existing
 * message, voice, or file encodings.
 */
data class CallSignal(
    val action: CallAction,
    val callId: ByteArray
) {
    init {
        require(callId.size == CALL_ID_SIZE)
    }

    fun encode(): ByteArray {
        val out = ByteArray(WIRE_SIZE)
        out[0] = VERSION.toByte()
        out[1] = action.value.toByte()
        callId.copyInto(out, destinationOffset = 2)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CallSignal) return false
        return action == other.action && callId.contentEquals(other.callId)
    }

    override fun hashCode(): Int = 31 * action.hashCode() + callId.contentHashCode()

    companion object {
        const val VERSION = 1
        const val CALL_ID_SIZE = 16
        const val WIRE_SIZE = 2 + CALL_ID_SIZE

        fun decode(bytes: ByteArray): CallSignal? {
            if (bytes.size != WIRE_SIZE) return null
            if ((bytes[0].toInt() and 0xFF) != VERSION) return null
            val action = CallAction.fromValue(bytes[1].toInt() and 0xFF) ?: return null
            return CallSignal(action, bytes.copyOfRange(2, WIRE_SIZE))
        }
    }
}

enum class CallAction(val value: Int) {
    INVITE(1),
    ACCEPT(2),
    DECLINE(3),
    HANGUP(4),
    BUSY(5);

    companion object {
        fun fromValue(value: Int): CallAction? = entries.firstOrNull { it.value == value }
    }
}

enum class CallPhase {
    IDLE,
    OUTGOING,
    INCOMING,
    ACTIVE
}

enum class CallEndReason {
    DECLINED,
    BUSY,
    TIMEOUT,
    HUNG_UP,
    UNAVAILABLE,
    MICROPHONE
}
