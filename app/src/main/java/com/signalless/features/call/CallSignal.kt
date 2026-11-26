package com.signalless.app.features.call

/**
 * Call setup carried inside an encrypted Noise payload.
 *
 * Version 1, exactly 18 bytes: `[version: u8][action: u8][callId: 16]`.
 * Version 2, exactly 21 bytes: version 1's fields then
 * `[flags: u8][codecMask: u8][pathMask: u8]`.
 * Unknown versions, actions, and lengths are rejected; version 1 carries only
 * the first five actions. Unknown mask bits are kept so they can be ignored.
 * Older clients drop the surrounding Noise payload type, so a call signal never
 * changes existing message, voice, or file encodings.
 */
data class CallSignal(
    val action: CallAction,
    val callId: ByteArray,
    val version: Int = VERSION_2,
    val flags: Int = 0,
    val codecMask: Int = CallCodec.AAC_LC.mask,
    val pathMask: Int = V1_PATHS
) {
    init {
        require(callId.size == CALL_ID_SIZE)
        require(version == VERSION_2 || (version == VERSION_1 && action.minVersion == VERSION_1))
        require(flags in 0..0xFF && codecMask in 0..0xFF && pathMask in 0..0xFF)
    }

    fun encode(): ByteArray {
        val out = ByteArray(if (version == VERSION_1) V1_SIZE else V2_SIZE)
        out[0] = version.toByte()
        out[1] = action.value.toByte()
        callId.copyInto(out, destinationOffset = 2)
        if (version == VERSION_2) {
            out[V1_SIZE] = flags.toByte()
            out[V1_SIZE + 1] = codecMask.toByte()
            out[V1_SIZE + 2] = pathMask.toByte()
        }
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CallSignal) return false
        return action == other.action && callId.contentEquals(other.callId) &&
            version == other.version && flags == other.flags &&
            codecMask == other.codecMask && pathMask == other.pathMask
    }

    override fun hashCode(): Int = 31 * action.hashCode() + callId.contentHashCode()

    companion object {
        const val VERSION_1 = 1
        const val VERSION_2 = 2
        const val CALL_ID_SIZE = 16
        const val V1_SIZE = 2 + CALL_ID_SIZE
        const val V2_SIZE = V1_SIZE + 3

        const val PATH_DIRECT_BLE = 0x01
        const val PATH_ROUTED_BLE = 0x02
        const val PATH_WIFI_AWARE = 0x04
        const val PATH_INTERNET = 0x08

        /** What a version 1 peer can do: AAC over direct or routed BLE. */
        const val V1_PATHS = PATH_DIRECT_BLE or PATH_ROUTED_BLE

        fun decode(bytes: ByteArray): CallSignal? {
            if (bytes.size != V1_SIZE && bytes.size != V2_SIZE) return null
            val version = if (bytes.size == V1_SIZE) VERSION_1 else VERSION_2
            if ((bytes[0].toInt() and 0xFF) != version) return null
            val action = CallAction.fromValue(bytes[1].toInt() and 0xFF) ?: return null
            if (action.minVersion > version) return null
            val callId = bytes.copyOfRange(2, V1_SIZE)
            if (version == VERSION_1) return CallSignal(action, callId, VERSION_1)
            return CallSignal(
                action, callId, VERSION_2,
                flags = bytes[V1_SIZE].toInt() and 0xFF,
                codecMask = bytes[V1_SIZE + 1].toInt() and 0xFF,
                pathMask = bytes[V1_SIZE + 2].toInt() and 0xFF
            )
        }
    }
}

enum class CallAction(val value: Int, val minVersion: Int = CallSignal.VERSION_1) {
    INVITE(1),
    ACCEPT(2),
    DECLINE(3),
    HANGUP(4),
    BUSY(5),
    RINGING(6, CallSignal.VERSION_2),
    HOLD(7, CallSignal.VERSION_2),
    RESUME(8, CallSignal.VERSION_2),
    PATH_PROBE(9, CallSignal.VERSION_2),
    PATH_SWITCH(10, CallSignal.VERSION_2),
    KEEPALIVE(11, CallSignal.VERSION_2);

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
    MICROPHONE,
    UNREACHABLE
}
