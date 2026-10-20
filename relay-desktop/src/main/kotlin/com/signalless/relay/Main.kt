package com.signalless.relay

import com.signalless.app.util.SLog
import com.signalless.relay.transport.FakeRelayTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

const val VERSION: String = "0.1.0"

private const val RELAY_PLACEHOLDER_PEER_ID = "0000000000000000"

private const val USAGE = """Usage: relay-desktop [options]

Options:
  --peers <n>       Maximum simultaneous peer connections (1-64, default 7)
  --ttl <n>         Maximum relay TTL (1-255, default 7)
  --transport <m>   Transport: auto or fake (default: auto)
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
    val transport = when (config.transport) {
        TransportMode.FAKE -> FakeRelayTransport()
        TransportMode.AUTO -> {
            System.err.println(
                "relay-desktop: the Windows BLE helper transport is not wired yet; " +
                    "use --transport fake for protocol tests",
            )
            return 1
        }
    }

    println("relay-desktop $VERSION")
    println("max peers: ${config.maxPeers}")
    println("ttl: ${config.ttl}")
    println("transport: ${config.transport.name.lowercase()}")
    println("log file: ${config.logFile ?: "stdout only"}")

    val logSink = installLogSink(config.logFile)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = RelayEngine(
        myPeerId = RELAY_PLACEHOLDER_PEER_ID,
        transport = transport,
        scope = scope,
        maxLinks = config.maxPeers,
    )

    return try {
        runBlocking {
            engine.start()
            println(
                "relay core started; identity and ANNOUNCE are added in the next phase",
            )
            awaitCancellation()
        }
        0
    } catch (error: CancellationException) {
        0
    } catch (error: Exception) {
        System.err.println("relay-desktop: ${error.message}")
        1
    } finally {
        runBlocking { engine.stop() }
        scope.cancel()
        logSink?.close()
    }
}

private fun installLogSink(path: Path?): AutoCloseable? {
    if (path == null) return null

    path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    val writer = PrintWriter(
        Files.newBufferedWriter(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        ),
        true,
    )
    SLog.sink = { level, tag, message, throwable ->
        synchronized(writer) {
            writer.println("$level/$tag: $message")
            throwable?.printStackTrace(writer)
        }
    }
    return AutoCloseable {
        SLog.sink = null
        writer.close()
    }
}
