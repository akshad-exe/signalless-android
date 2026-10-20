package com.signalless.relay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

const val VERSION: String = "0.1.0"

private const val USAGE = """Usage: relay-desktop [options]

Options:
  --peers <n>       Maximum simultaneous peer connections (1-64, default 8)
  --ttl <n>         Maximum relay TTL (1-255, default 7)
  --iface <name>    Bluetooth adapter/interface name (default: auto)
  --log-file <path> Write logs to this file (default: stdout only)
  -h, --help        Show this help
  -V, --version     Show the version
"""

fun main(args: Array<String>) {
    val exitCode = run(args)
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}

internal fun run(args: Array<String>): Int {
    if (args.any { it == "--help" || it == "-h" }) {
        println(USAGE)
        return 0
    }
    if (args.any { it == "--version" || it == "-V" }) {
        println("relay-desktop $VERSION")
        return 0
    }

    return try {
        val config = RelayConfig.parse(args.toList())
        runRelay(config)
    } catch (error: CliException) {
        System.err.println("relay-desktop: ${error.message}")
        System.err.println(USAGE)
        2
    }
}

private fun runRelay(config: RelayConfig): Int {
    println("relay-desktop $VERSION")
    println("max peers: ${config.maxPeers}")
    println("ttl: ${config.ttl}")
    println("interface: ${config.iface ?: "auto"}")
    println("log file: ${config.logFile ?: "stdout only"}")
    println("scanning for SignalLess phones...")

    val scanner = KableBleScanner()
    val seen = mutableSetOf<String>()
    return try {
        runBlocking {
            scanner.scan().collect { peer ->
                if (seen.add(peer.peerId)) {
                    // Keep MAC addresses and nicknames out of the log; the peer
                    // ID is the only field the relay needs to name a peer.
                    println("discovered peer=${peer.peerId} rssi=${peer.rssi}")
                }
            }
        }
        0
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        System.err.println("relay-desktop: Bluetooth scan failed: ${error.message}")
        1
    }
}
