package com.signalless.relay

import java.nio.file.Path

/**
 * Command-line configuration for the desktop relay.
 *
 * Phase 1 only parses and validates these values; later phases consume them.
 */
data class RelayConfig(
    val maxPeers: Int = DEFAULT_MAX_PEERS,
    val ttl: Int = DEFAULT_TTL,
    val iface: String? = null,
    val logFile: Path? = null,
) {
    companion object {
        const val DEFAULT_MAX_PEERS: Int = 8
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
            var iface: String? = null
            var logFile: Path? = null

            var index = 0
            while (index < args.size) {
                val option = args[index]
                index += 1

                when (option) {
                    "--peers", "--ttl", "--iface", "--log-file" -> {
                        val value = args.getOrNull(index)
                            ?: throw CliException("missing value for $option")

                        when (option) {
                            "--peers" -> maxPeers = parseBoundedInt(option, value, MIN_PEERS, MAX_PEERS)
                            "--ttl" -> ttl = parseBoundedInt(option, value, MIN_TTL, MAX_TTL)
                            "--iface" -> iface = value.ifBlank {
                                throw CliException("$option must not be blank")
                            }
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
                iface = iface,
                logFile = logFile,
            )
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
