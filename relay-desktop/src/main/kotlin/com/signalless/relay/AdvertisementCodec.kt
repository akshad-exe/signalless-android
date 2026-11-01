package com.signalless.relay

import com.signalless.app.util.AppConstants
import kotlin.uuid.Uuid

/**
 * Encodes and decodes the 8-byte peer ID carried in the SignalLess service
 * data section of a BLE advertisement.
 *
 * Phones accept the peer ID from either the advertisement or the scan
 * response, so the relay only has to emit one service-data section.
 */
object AdvertisementCodec {
    const val PEER_ID_BYTES: Int = 8
    const val PEER_ID_HEX_CHARS: Int = PEER_ID_BYTES * 2

    val SERVICE_UUID: Uuid = Uuid.parse(AppConstants.Mesh.Gatt.SERVICE_UUID.toString())

    /**
     * Returns the first 8 bytes of [serviceData] as lowercase hex, or `null`
     * when the section is absent or too short.
     *
     * Phones currently read every byte of the service-data section, but the
     * protocol identity is the first 8 bytes; truncating here keeps the relay
     * compatible with both.
     */
    fun peerIdFromServiceData(serviceData: ByteArray?): String? {
        if (serviceData == null || serviceData.size < PEER_ID_BYTES) {
            return null
        }
        return buildString(PEER_ID_HEX_CHARS) {
            for (index in 0 until PEER_ID_BYTES) {
                val value = serviceData[index].toInt() and 0xFF
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0F])
            }
        }
    }

    /**
     * Converts a 16-character hex peer ID into the 8-byte service-data value.
     */
    fun serviceDataForPeerId(peerId: String): ByteArray {
        require(peerId.length == PEER_ID_HEX_CHARS) {
            "peer ID must be $PEER_ID_HEX_CHARS hex characters, got ${peerId.length}"
        }
        return ByteArray(PEER_ID_BYTES) { index ->
            val high = hexValue(peerId[index * 2])
            val low = hexValue(peerId[index * 2 + 1])
            ((high shl 4) or low).toByte()
        }
    }

    private const val HEX_DIGITS = "0123456789abcdef"

    private fun hexValue(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> throw IllegalArgumentException("not a hex digit: '$character'")
    }
}
