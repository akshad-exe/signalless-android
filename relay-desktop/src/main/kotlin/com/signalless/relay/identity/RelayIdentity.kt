package com.signalless.relay.identity

import com.signalless.app.model.IdentityAnnouncement
import com.signalless.app.model.PeerCapabilities
import com.signalless.app.noise.NoisePeerIdentity
import com.signalless.app.protocol.MessageType
import com.signalless.app.protocol.SignalLessPacket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.EdECPrivateKey
import java.security.interfaces.XECPrivateKey
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.XECPrivateKeySpec

/**
 * Persistent relay identity.
 *
 * The mesh peer ID is not chosen by the relay: it is the first eight bytes of
 * SHA-256(noise static public key), exactly as every phone derives it. The
 * Ed25519 key is separate and signs the ANNOUNCE packet.
 */
class RelayIdentity(
    val noisePrivateKey: ByteArray,
    val noisePublicKey: ByteArray,
    val signingPrivateKey: ByteArray,
    val signingPublicKey: ByteArray,
) {
    val peerId: String = requireNotNull(NoisePeerIdentity.derivePeerID(noisePublicKey)) {
        "noisePublicKey must be ${NoisePeerIdentity.STATIC_PUBLIC_KEY_SIZE} bytes"
    }

    init {
        require(noisePrivateKey.size == KEY_SIZE) { "noise private key must be $KEY_SIZE bytes" }
        require(noisePublicKey.size == KEY_SIZE) { "noise public key must be $KEY_SIZE bytes" }
        require(signingPrivateKey.size == KEY_SIZE) { "signing private key must be $KEY_SIZE bytes" }
        require(signingPublicKey.size == KEY_SIZE) { "signing public key must be $KEY_SIZE bytes" }
    }

    fun sign(data: ByteArray): ByteArray {
        val privateKey = KeyFactory.getInstance(ED25519)
            .generatePrivate(EdECPrivateKeySpec(NamedParameterSpec.ED25519, signingPrivateKey))
        val signer = Signature.getInstance(ED25519)
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    fun verify(
        signature: ByteArray,
        data: ByteArray,
        publicKey: ByteArray = signingPublicKey,
    ): Boolean {
        if (publicKey.size != KEY_SIZE || signature.isEmpty()) return false
        return try {
            val key = KeyFactory.getInstance(ED25519)
                .generatePublic(X509EncodedKeySpec(ed25519X509Prefix() + publicKey))
            val verifier = Signature.getInstance(ED25519)
            verifier.initVerify(key)
            verifier.update(data)
            verifier.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Builds a self-signed announcement packet. The signature covers the
     * canonical packet bytes with the fixed signing TTL used by all clients.
     */
    fun signedAnnouncement(
        nickname: String,
        ttl: UByte,
        timestampMs: Long = System.currentTimeMillis(),
    ): SignalLessPacket {
        val announcement = IdentityAnnouncement(
            nickname = nickname,
            noisePublicKey = noisePublicKey.copyOf(),
            signingPublicKey = signingPublicKey.copyOf(),
            capabilities = PeerCapabilities.NONE,
        )
        val payload = requireNotNull(announcement.encode()) {
            "could not encode relay announcement"
        }
        val packet = SignalLessPacket(
            version = 1u,
            type = MessageType.ANNOUNCE.value,
            ttl = ttl,
            senderID = peerIdBytes(),
            timestamp = timestampMs.coerceAtLeast(0L).toULong(),
            payload = payload,
        )
        val signingBytes = requireNotNull(packet.toBinaryDataForSigning()) {
            "could not encode relay announcement for signing"
        }
        return packet.copy(signature = sign(signingBytes))
    }

    fun peerIdBytes(): ByteArray = ByteArray(8) { index ->
        peerId.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    companion object {
        const val KEY_SIZE: Int = 32
        private const val ED25519 = "Ed25519"
        private const val X25519 = "X25519"
        private val ED25519_X509_PREFIX = byteArrayOf(
            0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        private val X25519_X509_PREFIX = byteArrayOf(
            0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x6E, 0x03, 0x21, 0x00,
        )

        fun generate(random: SecureRandom = SecureRandom()): RelayIdentity {
            val noise = KeyPairGenerator.getInstance(X25519)
                .apply { initialize(NamedParameterSpec.X25519, random) }
                .generateKeyPair()
            val signing = KeyPairGenerator.getInstance(ED25519)
                .apply { initialize(NamedParameterSpec.ED25519, random) }
                .generateKeyPair()

            val noisePrivate = (noise.private as XECPrivateKey).scalar.orElseThrow {
                IllegalStateException("X25519 private key has no scalar")
            }
            val noisePublic = noise.public.encoded.rawPublicKey(X25519_X509_PREFIX)
            val signingPrivate = (signing.private as EdECPrivateKey).bytes.orElseThrow {
                IllegalStateException("Ed25519 private key has no seed")
            }
            val signingPublic = signing.public.encoded.rawPublicKey(ED25519_X509_PREFIX)
            return RelayIdentity(noisePrivate, noisePublic, signingPrivate, signingPublic)
        }

        private fun ed25519X509Prefix(): ByteArray = ED25519_X509_PREFIX

        private fun ByteArray.rawPublicKey(prefix: ByteArray): ByteArray {
            require(size == prefix.size + KEY_SIZE) { "unexpected public key encoding length $size" }
            require(take(prefix.size).toByteArray().contentEquals(prefix)) {
                "unexpected public key algorithm"
            }
            return takeLast(KEY_SIZE).toByteArray()
        }
    }
}
