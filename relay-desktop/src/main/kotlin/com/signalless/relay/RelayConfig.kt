package com.signalless.relay

import com.signalless.app.util.AppConstants
import java.nio.file.Path

enum class TransportMode {
    AUTO,
    HELPER,
    FAKE,
}

/**
 * Command-line configuration for the desktop relay.
 *
 * `auto` selects the BLE helper only when it can be resolved on Windows. It
 * never falls back to the fake transport silently.
 */
data class RelayConfig(
    val maxPeers: Int = DEFAULT_MAX_PEERS,
    val ttl: Int = DEFAULT_TTL,
    val transport: TransportMode = TransportMode.AUTO,
    val logFile: Path? = null,
    val helperPath: Path? = null,
    val identityDirectory: Path? = null,
    val resetIdentity: Boolean = false,
    val nickname: String = DEFAULT_NICKNAME,
    val announceIntervalSeconds: Long = DEFAULT_ANNOUNCE_INTERVAL_SECONDS,
    val advertiseServiceData: Boolean = false,
    val discoverable: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_PEERS: Int = 7
        const val DEFAULT_TTL: Int = 7
        const val MIN_PEERS: Int = 1
        const val MAX_PEERS: Int = 64
        const val MIN_TTL: Int = 1
        const val MAX_TTL: Int = 255
        const val DEFAULT_NICKNAME: String = "SignalLessRelay"
        const val DEFAULT_ANNOUNCE_INTERVAL_SECONDS: Long = 60L
        const val MIN_ANNOUNCE_INTERVAL_SECONDS: Long = 1L
        const val MAX_ANNOUNCE_INTERVAL_SECONDS: Long = 3_600L

        /**
         * Parses [args]. `--help` and `--version` are handled by [main] before
         * this is called.
         */
        fun parse(args: List<String>): RelayConfig {
            var maxPeers = DEFAULT_MAX_PEERS
            var ttl = DEFAULT_TTL
            var transport = TransportMode.AUTO
            var logFile: Path? = null
            var helperPath: Path? = null
            var identityDirectory: Path? = null
            var resetIdentity = false
            var nickname = DEFAULT_NICKNAME
            var announceInterval = DEFAULT_ANNOUNCE_INTERVAL_SECONDS
            var advertiseServiceData = false
            var discoverable = true

            var index = 0
            while (index < args.size) {
                val option = args[index]
                index += 1

                when (option) {
                    "--peers", "--ttl", "--transport", "--log-file", "--helper",
                    "--identity-dir", "--nickname", "--announce-interval" -> {
                        val value = args.getOrNull(index)
                            ?: throw CliException("missing value for $option")

                        when (option) {
                            "--peers" -> maxPeers = parseBoundedInt(option, value, MIN_PEERS, MAX_PEERS)
                            "--ttl" -> ttl = parseBoundedInt(option, value, MIN_TTL, MAX_TTL)
                            "--transport" -> transport = parseTransport(value)
                            "--log-file" -> logFile = pathValue(option, value)
                            "--helper" -> helperPath = pathValue(option, value)
                            "--identity-dir" -> identityDirectory = pathValue(option, value)
                            "--nickname" -> nickname = parseNickname(value)
                            "--announce-interval" -> announceInterval = parseBoundedLong(
                                option,
                                value,
                                MIN_ANNOUNCE_INTERVAL_SECONDS,
                                MAX_ANNOUNCE_INTERVAL_SECONDS,
                            )
                        }
                        index += 1
                    }
                    "--reset-identity" -> resetIdentity = true
                    "--advertise-service-data" -> advertiseServiceData = true
                    "--discoverable" -> discoverable = true
                    "--no-discoverable" -> discoverable = false
                    else -> throw CliException("unknown option: $option")
                }
            }

            return RelayConfig(
                maxPeers = maxPeers,
                ttl = ttl,
                transport = transport,
                logFile = logFile,
                helperPath = helperPath,
                identityDirectory = identityDirectory,
                resetIdentity = resetIdentity,
                nickname = nickname,
                announceIntervalSeconds = announceInterval,
                advertiseServiceData = advertiseServiceData,
                discoverable = discoverable,
            )
        }

        private fun parseTransport(value: String): TransportMode = when (value) {
            "auto" -> TransportMode.AUTO
            "helper" -> TransportMode.HELPER
            "fake" -> TransportMode.FAKE
            else -> throw CliException("--transport must be auto, helper, or fake, got: $value")
        }

        private fun pathValue(option: String, value: String): Path = Path.of(
            value.ifBlank { throw CliException("$option must not be blank") },
        )

        private fun parseNickname(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) throw CliException("--nickname must not be blank")
            if (trimmed.length > AppConstants.UI.MAX_NICKNAME_LENGTH) {
                throw CliException(
                    "--nickname must be at most ${AppConstants.UI.MAX_NICKNAME_LENGTH} characters",
                )
            }
            return trimmed
        }

        private fun parseBoundedInt(option: String, value: String, min: Int, max: Int): Int {
            val parsed = value.toIntOrNull()
                ?: throw CliException("$option must be an integer, got: $value")
            if (parsed !in min..max) {
                throw CliException("$option must be between $min and $max, got: $parsed")
            }
            return parsed
        }

        private fun parseBoundedLong(option: String, value: String, min: Long, max: Long): Long {
            val parsed = value.toLongOrNull()
                ?: throw CliException("$option must be an integer, got: $value")
            if (parsed !in min..max) {
                throw CliException("$option must be between $min and $max, got: $parsed")
            }
            return parsed
        }
    }
}

class CliException(message: String) : IllegalArgumentException(message)
