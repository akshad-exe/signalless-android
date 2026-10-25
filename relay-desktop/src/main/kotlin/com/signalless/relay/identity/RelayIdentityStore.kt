package com.signalless.relay.identity

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Loads or creates the relay's persistent X25519 and Ed25519 keys.
 *
 * The first run creates all four files. A partial or corrupt identity is an
 * error: silently rotating keys would make the relay appear as a new person on
 * every restart. Rotation requires the explicit `--reset-identity` flag.
 */
class RelayIdentityStore(private val directory: Path) {
    fun loadOrCreate(reset: Boolean = false): RelayIdentity {
        if (reset) {
            deleteIdentityFiles()
        }

        val existing = IDENTITY_FILES.filter { Files.exists(directory.resolve(it)) }
        if (existing.isEmpty()) {
            val generated = RelayIdentity.generate()
            write(generated)
            return generated
        }
        if (existing.size != IDENTITY_FILES.size) {
            throw IllegalStateException(
                "relay identity is incomplete; rerun with --reset-identity to create a new identity",
            )
        }

        return try {
            RelayIdentity(
                noisePrivateKey = read(NOISE_PRIVATE),
                noisePublicKey = read(NOISE_PUBLIC),
                signingPrivateKey = read(ED25519_PRIVATE),
                signingPublicKey = read(ED25519_PUBLIC),
            )
        } catch (error: Exception) {
            throw IllegalStateException(
                "relay identity is corrupt; rerun with --reset-identity to create a new identity",
                error,
            )
        }
    }

    private fun write(identity: RelayIdentity) {
        createPrivateDirectory()
        writeFile(NOISE_PRIVATE, identity.noisePrivateKey)
        writeFile(NOISE_PUBLIC, identity.noisePublicKey)
        writeFile(ED25519_PRIVATE, identity.signingPrivateKey)
        writeFile(ED25519_PUBLIC, identity.signingPublicKey)
    }

    private fun read(name: String): ByteArray {
        val bytes = Files.readAllBytes(directory.resolve(name))
        check(bytes.size == RelayIdentity.KEY_SIZE) {
            "$name must be ${RelayIdentity.KEY_SIZE} bytes"
        }
        return bytes
    }

    private fun writeFile(name: String, bytes: ByteArray) {
        val target = directory.resolve(name)
        val temporary = directory.resolve("$name.tmp")
        Files.write(temporary, bytes)
        setOwnerOnlyFile(temporary)
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
        setOwnerOnlyFile(target)
    }

    private fun deleteIdentityFiles() {
        IDENTITY_FILES.forEach { name ->
            Files.deleteIfExists(directory.resolve(name))
            Files.deleteIfExists(directory.resolve("$name.tmp"))
        }
    }

    private fun createPrivateDirectory() {
        Files.createDirectories(directory)
        try {
            Files.setPosixFilePermissions(
                directory,
                PosixFilePermissions.fromString("rwx------"),
            )
        } catch (_: UnsupportedOperationException) {
            // Windows uses ACLs. The per-user application data directory is the
            // practical boundary for this unpackaged build.
        }
    }

    private fun setOwnerOnlyFile(path: Path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // See createPrivateDirectory.
        }
    }

    companion object {
        private const val NOISE_PRIVATE = "noise_private.bin"
        private const val NOISE_PUBLIC = "noise_public.bin"
        private const val ED25519_PRIVATE = "ed25519_private.bin"
        private const val ED25519_PUBLIC = "ed25519_public.bin"
        private val IDENTITY_FILES = listOf(
            NOISE_PRIVATE,
            NOISE_PUBLIC,
            ED25519_PRIVATE,
            ED25519_PUBLIC,
        )

        fun defaultDirectory(): Path {
            val os = System.getProperty("os.name").lowercase()
            if (os.contains("win")) {
                val local = System.getenv("LOCALAPPDATA")
                    ?: Path.of(System.getProperty("user.home"), "AppData", "Local").toString()
                return Path.of(local, "SignalLess", "relay", "identity")
            }
            val xdg = System.getenv("XDG_DATA_HOME")
            return if (!xdg.isNullOrBlank()) {
                Path.of(xdg, "signalless", "relay", "identity")
            } else {
                Path.of(System.getProperty("user.home"), ".local", "share", "signalless", "relay", "identity")
            }
        }
    }
}
