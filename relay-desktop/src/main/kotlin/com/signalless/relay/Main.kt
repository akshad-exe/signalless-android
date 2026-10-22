package com.signalless.relay

import com.signalless.app.util.SLog
import com.signalless.relay.identity.RelayIdentity
import com.signalless.relay.identity.RelayIdentityStore
import com.signalless.relay.transport.FakeRelayTransport
import com.signalless.relay.transport.HelperProcessTransport
import com.signalless.relay.transport.RelayTransport
import com.signalless.relay.ui.RelayController
import com.signalless.relay.ui.compose.runDemoApp
import com.signalless.relay.ui.compose.runRelayApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.awt.GraphicsEnvironment
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

const val VERSION: String = "0.2.0"

private const val USAGE = """Usage: relay-desktop [options]

Options:
  --peers <n>                 Requested simultaneous links (1-64, default 7;
                              the Windows helper clamps to 7)
  --ttl <n>                   Maximum relay TTL (1-255, default 7)
  --transport <mode>          auto, helper, or fake (default: auto)
  --helper <path>             BLE helper executable; required for helper mode
                              unless auto-discovery finds one
  --identity-dir <path>       Persistent identity directory
  --reset-identity            Explicitly create a new relay identity
  --nickname <name>           Announcement nickname (max 15 characters)
  --announce-interval <sec>   Refresh interval while connected (1-3600)
  --advertise-service-data    Experimental 8-byte peer-ID service data
  --discoverable              Include the friendly name in advertising
                              (default; makes Windows use extended advertising)
  --no-discoverable           Omit the friendly name; only works if the
                              adapter can start a legacy advertisement)
  --ui                        Open the desktop window (default)
  --no-ui                     Run headless; required for Task Scheduler/CI
  --log-file <path>           Write logs to this file (default: stdout only)
  -h, --help                  Show this help
  -V, --version               Show the version
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
        if (config.demo) runDemo(config) else runRelay(config)
    } catch (error: CliException) {
        System.err.println("relay-desktop: ${error.message}")
        System.err.println(USAGE)
        2
    }
}

private fun runRelay(config: RelayConfig): Int {
    val identity = try {
        when (config.transport) {
            TransportMode.FAKE -> RelayIdentity.generate()
            TransportMode.AUTO, TransportMode.HELPER -> {
                val directory = config.identityDirectory ?: RelayIdentityStore.defaultDirectory()
                RelayIdentityStore(directory).loadOrCreate(config.resetIdentity)
            }
        }
    } catch (error: Exception) {
        System.err.println("relay-desktop: ${error.message}")
        return 1
    }

    val logSink = installLogSink(config.logFile)

    return try {
        val headless = config.ui && GraphicsEnvironment.isHeadless()
        if (headless) {
            System.err.println("relay-desktop: no display detected, continuing headless")
        }
        if (config.ui && !headless) runUi(config, identity) else runHeadless(config, identity)
    } finally {
        logSink?.close()
    }
}

/**
 * The operator window. The controller starts inside the application scope so
 * the first frame can render `Starting…`; [RelayController.shutdown] is
 * idempotent and runs both here and in the window's close handler.
 */
private fun runUi(config: RelayConfig, identity: RelayIdentity): Int {
    val controller = RelayController(
        config = config,
        identity = identity,
        version = VERSION,
        transportFactory = { scope -> createTransport(config, identity, scope) },
        eventLogger = { line ->
            println(line)
            if (config.logFile != null) {
                SLog.i("RelayUi", line)
            }
        },
    )

    return try {
        runRelayApp(controller)
        0
    } finally {
        controller.shutdown()
    }
}

/** `--demo` is a developer-only layout aid; it never creates an identity or engine. */
private fun runDemo(config: RelayConfig): Int {
    if (!config.ui) {
        System.err.println("relay-desktop: --demo requires the desktop window; remove --no-ui")
        return 2
    }
    if (GraphicsEnvironment.isHeadless()) {
        System.err.println("relay-desktop: no display detected; --demo requires a desktop")
        return 1
    }
    runDemoApp(VERSION)
    return 0
}

private fun runHeadless(config: RelayConfig, identity: RelayIdentity): Int {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val transport = try {
        createTransport(config, identity, scope)
    } catch (error: Exception) {
        scope.cancel()
        System.err.println("relay-desktop: ${error.message}")
        return 1
    }

    println("relay-desktop $VERSION")
    println("max peers: ${config.maxPeers}")
    println("ttl: ${config.ttl}")
    println("transport: ${config.transport.name.lowercase()}")
    println("nickname: ${config.nickname}")
    println("log output: ${if (config.logFile != null) "file" else "stdout only"}")

    val engine = RelayEngine(
        myPeerId = identity.peerId,
        transport = transport,
        scope = scope,
        maxLinks = if (config.transport == TransportMode.FAKE) {
            config.maxPeers
        } else {
            minOf(config.maxPeers, 7)
        },
        maxTtl = config.ttl.toUByte(),
        identity = identity,
        announcementNickname = config.nickname,
        announceIntervalMs = config.announceIntervalSeconds * 1_000L,
        notice = { line -> println(line) },
    )

    return try {
        runBlocking {
            engine.start()
            println("relay core started")
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
    }
}

private fun createTransport(
    config: RelayConfig,
    identity: RelayIdentity,
    scope: CoroutineScope,
): RelayTransport {
    return when (config.transport) {
        TransportMode.FAKE -> FakeRelayTransport()
        TransportMode.HELPER, TransportMode.AUTO -> {
            val externalHelper =
                config.helperPath != null || !System.getenv("SIGNALLESS_RELAY_HELPER").isNullOrBlank()
            if (config.transport == TransportMode.AUTO && !isWindows() && !externalHelper) {
                throw IllegalStateException(
                    "no BLE helper is auto-detected on this platform; " +
                        "use --transport fake for protocol tests or --transport helper " +
                        "with a Linux BlueZ helper",
                )
            }
            val helper = resolveHelper(config.helperPath)
                ?: throw IllegalStateException(
                    "could not find the BLE helper; pass --helper <path>",
                )
            val command = mutableListOf(
                helper.toString(),
                "--ipc",
                "--no-echo",
                "--max-links",
                minOf(config.maxPeers, 7).toString(),
            )
            if (config.advertiseServiceData) {
                command += listOf("--service-data", identity.peerId)
            }
            if (isWindows()) {
                command += if (config.discoverable) "--discoverable" else "--no-discoverable"
            }
            HelperProcessTransport(
                command = command,
                scope = scope,
            )
        }
    }
}

private fun resolveHelper(explicit: Path?): Path? {
    if (explicit != null) return explicit
    val fromEnvironment = System.getenv("SIGNALLESS_RELAY_HELPER")
    if (!fromEnvironment.isNullOrBlank()) return Path.of(fromEnvironment)

    if (!isWindows()) return null

    val names = listOf(
        "SignalLessRelayBleHelper.exe",
        "relay-ble-helper/SignalLessRelayBleHelper.exe",
        "helper/SignalLessRelayBleHelper.exe",
    )
    val candidates = mutableListOf<Path>()
    System.getProperty("jpackage.app-path")?.let { appPath ->
        Path.of(appPath).parent?.let {
            candidates.add(it.resolve("SignalLessRelayBleHelper.exe"))
        }
    }
    System.getProperty("java.home")?.let { javaHome ->
        Path.of(javaHome).parent?.let { appDirectory ->
            candidates.add(appDirectory.resolve("SignalLessRelayBleHelper.exe"))
        }
    }
    val workingDirectory = Path.of(System.getProperty("user.dir"))
    candidates.addAll(names.map { workingDirectory.resolve(it) })
    return candidates.firstOrNull { Files.isRegularFile(it) }
}

private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

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
