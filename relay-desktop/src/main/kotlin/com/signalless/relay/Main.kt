package com.signalless.relay

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
        println("relay-desktop $VERSION")
        println("max peers: ${config.maxPeers}")
        println("ttl: ${config.ttl}")
        println("interface: ${config.iface ?: "auto"}")
        println("log file: ${config.logFile ?: "stdout only"}")
        0
    } catch (error: CliException) {
        System.err.println("relay-desktop: ${error.message}")
        System.err.println(USAGE)
        2
    }
}
