package com.signalless.app.mesh

import com.signalless.app.model.IdentityAnnouncement
import com.signalless.app.noise.NoisePeerIdentity
import com.signalless.app.protocol.SignalLessPacket
import com.signalless.app.protocol.MessageType
import com.signalless.app.util.toHexString

/** Canonical, side-effect-free preflight for a self-signed mesh announcement. */
object AnnouncementIdentityValidator {
    private const val MAX_CLOCK_SKEW_MS = 10 * 60 * 1_000L

    fun verify(
        packet: SignalLessPacket,
        claimedPeerID: String,
        nowMs: Long = System.currentTimeMillis(),
        verifyEd25519: (signature: ByteArray, data: ByteArray, publicKey: ByteArray) -> Boolean
    ): IdentityAnnouncement? {
        if (packet.type != MessageType.ANNOUNCE.value) return null
        val now = nowMs.coerceAtLeast(0).toULong()
        val skew = if (packet.timestamp >= now) packet.timestamp - now else now - packet.timestamp
        if (skew > MAX_CLOCK_SKEW_MS.toULong()) return null
        val announcement = IdentityAnnouncement.decode(packet.payload) ?: return null
        if (announcement.signingPublicKey.size != 32) return null

        val derivedPeerID = NoisePeerIdentity.derivePeerID(announcement.noisePublicKey) ?: return null
        if (packet.senderID.toHexString() != derivedPeerID || claimedPeerID != derivedPeerID) return null

        val signature = packet.signature ?: return null
        val canonicalData = packet.toBinaryDataForSigning() ?: return null
        return if (verifyEd25519(signature, canonicalData, announcement.signingPublicKey)) {
            announcement
        } else {
            null
        }
    }
}
