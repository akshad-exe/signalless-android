package com.signalless.relay

import java.nio.file.Path

enum class TransportMode {
    AUTO,
    FAKE,
}

/**
 * Command-line configuration for the desktop relay.
 *
 * Phase 1 parses and validates these values and wires the fake transport. The
 * Windows helper transport is added in the next phase; `auto` therefore fails
 * clearly until that helper exists.
 */
data class RelayConfig(
    val maxPeers: Int = DEFAULT_MAX_PEERS,
    val ttl: Int = DEFAULT_TTL,
    val transport: TransportMode = TransportMode.AUTO,
    val logFile: Path? = null,
) {
    companion object {
        const val DEFAULT_MAX_PEERS: Int = 7
        const val DEFAULT_TTL: Int = 7
        const val MIN_PEERS: Int = 1
        const val MAX_PEERS: Int = 64
        const val MIN_TTL: Int = 1
        const val MAX_TTL: Int = 255

        /**
         * Parses [args]. `--help` and `--version` are handled by [main] before
         * this is called.
         */
        fun parse(args: List<String>): RelayConfig {
            var maxPeers = DEFAULT_MAX_PEERS
            var ttl = DEFAULT_TTL
            var transport = TransportMode.AUTO
            var logFile: Path? = null

            var index = 0
            while (index < args.size) {
                val option = args[index]
                index += 1

                when (option) {
                    "--peers", "--ttl", "--transport", "--log-file" -> {
                        val value = args.getOrNull(index)
                            ?: throw CliException("missing value for $option")

                        when (option) {
                            "--peers" -> maxPeers = parseBoundedInt(option, value, MIN_PEERS, MAX_PEERS)
                            "--ttl" -> ttl = parseBoundedInt(option, value, MIN_TTL, MAX_TTL)
                            "--transport" -> transport = parseTransport(value)
                            "--log-file" -> logFile = Path.of(value.ifBlank {
                                throw CliException("$option must not be blank")
                            })
                        }
                        index += 1
                    }
                    else -> throw CliException("unknown option: $option")
                }
            }

            return RelayConfig(
                maxPeers = maxPeers,
                ttl = ttl,
                transport = transport,
                logFile = logFile,
            )
        }

        private fun parseTransport(value: String): TransportMode = when (value) {
            "auto" -> TransportMode.AUTO
            "fake" -> TransportMode.FAKE
            else -> throw CliException("--transport must be auto or fake, got: $value")
        }

        private fun parseBoundedInt(option: String, value: String, min: Int, max: Int): Int {
            val parsed = value.toIntOrNull()
                ?: throw CliException("$option must be an integer, got: $value")
            if (parsed !in min..max) {
                throw CliException("$option must be between $min and $max, got: $parsed")
            }
            return parsed
        }
    }
}

class CliException(message: String) : IllegalArgumentException(message)
