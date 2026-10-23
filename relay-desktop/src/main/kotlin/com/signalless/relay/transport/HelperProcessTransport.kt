package com.signalless.relay.transport

import com.signalless.app.util.SLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs a BLE helper process and speaks [HelperProtocol] over stdin/stdout.
 *
 * The helper is restarted with bounded backoff if it dies after a successful
 * start. Pending transmissions fail instead of hanging. Link IDs are opaque
 * integers supplied by the helper; no MAC address or device name crosses this
 * boundary.
 */
class HelperProcessTransport(
    private val command: List<String>,
    private val scope: CoroutineScope,
    private val maxRestarts: Int = 3,
    private val restartDelayMs: Long = 1_000L,
    private val startTimeoutMs: Long = 10_000L,
    private val txTimeoutMs: Long = 5_000L,
    private val processLauncher: (List<String>) -> Process = { ProcessBuilder(it).start() },
) : RelayTransport {
    private data class Outbound(
        val line: String,
        val sequence: Long,
        val completion: CompletableDeferred<Boolean>,
    )

    private val eventChannel = Channel<RelayTransportEvent>(Channel.UNLIMITED)
    override val events: Flow<RelayTransportEvent> = eventChannel.receiveAsFlow()

    private val stateLock = Any()
    private val knownLinks = mutableSetOf<String>()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val sequence = AtomicLong()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var outbound: Channel<Outbound>? = null

    @Volatile
    private var ready: CompletableDeferred<Result<Unit>> = CompletableDeferred()

    @Volatile
    private var stopping: Boolean = false

    @Volatile
    private var started: Boolean = false

    private var restartAttempts: Int = 0
    private var restartJob: Job? = null
    private var readerJob: Job? = null
    private var stderrJob: Job? = null
    private var writerJob: Job? = null
    private var waitJob: Job? = null

    override suspend fun start(): Result<Unit> {
        synchronized(stateLock) {
            check(!started) { "helper transport is already started" }
            started = true
            stopping = false
        }

        val first = launchProcess()
        if (first.isFailure) {
            started = false
            return first
        }

        val result = withTimeoutOrNull(startTimeoutMs) { ready.await() }
            ?: Result.failure(IllegalStateException("helper did not become ready within ${startTimeoutMs}ms"))
        if (result.isFailure) {
            stopProcess()
            started = false
        }
        return result
    }

    override suspend fun stop() {
        synchronized(stateLock) {
            if (!started && process == null) return
            stopping = true
            started = false
        }

        restartJob?.cancelAndJoin()
        restartJob = null

        try {
            outbound?.trySend(
                Outbound(
                    line = HelperProtocol.shutdownCommand(),
                    sequence = 0L,
                    completion = CompletableDeferred(true),
                ),
            )
        } catch (_: Exception) {
            // Best effort; closing stdin is the real shutdown signal.
        }
        outbound?.close()
        process?.outputStream?.let { stream ->
            try {
                stream.close()
            } catch (_: Exception) {
                // The helper may already have exited.
            }
        }
        stopProcess()
        readerJob?.cancelAndJoin()
        stderrJob?.cancelAndJoin()
        writerJob?.cancelAndJoin()
        waitJob?.cancelAndJoin()
        readerJob = null
        stderrJob = null
        writerJob = null
        waitJob = null
        failPending("helper stopped")
        eventChannel.close()
    }

    override suspend fun send(linkId: String, data: ByteArray): Result<Unit> {
        if (!started || stopping) {
            return Result.failure(IllegalStateException("helper transport is not started"))
        }
        val channel = outbound
            ?: return Result.failure(IllegalStateException("helper transport has no process"))
        val seq = sequence.incrementAndGet()
        val completion = CompletableDeferred<Boolean>()
        pending[seq] = completion
        val sent = channel.trySend(
            Outbound(
                line = HelperProtocol.txCommand(linkId, seq, data),
                sequence = seq,
                completion = completion,
            ),
        )
        if (sent.isFailure) {
            pending.remove(seq)
            return Result.failure(IllegalStateException("helper transmit queue is full"))
        }

        val delivered = withTimeoutOrNull(txTimeoutMs) { completion.await() } ?: false
        pending.remove(seq)
        return if (delivered) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("helper did not acknowledge transmission"))
        }
    }

    private fun launchProcess(): Result<Unit> {
        val created = try {
            processLauncher(command)
        } catch (error: Exception) {
            return Result.failure(
                IllegalStateException(
                    "could not start the Windows BLE helper: ${error.javaClass.simpleName}",
                    error,
                ),
            )
        }

        val freshReady = CompletableDeferred<Result<Unit>>()
        val freshOutbound = Channel<Outbound>(OUTBOUND_CAPACITY)
        synchronized(stateLock) {
            process = created
            ready = freshReady
            outbound = freshOutbound
        }

        writerJob = scope.launch(Dispatchers.IO) {
            writerLoop(created, freshOutbound, freshReady)
        }
        readerJob = scope.launch(Dispatchers.IO) {
            readEvents(created, freshReady)
        }
        stderrJob = scope.launch(Dispatchers.IO) {
            readDiagnostics(created)
        }
        waitJob = scope.launch(Dispatchers.IO) {
            val exit = created.waitFor()
            if (!stopping) {
                handleProcessExit(exit)
            }
        }
        return Result.success(Unit)
    }

    private suspend fun writerLoop(
        process: Process,
        output: Channel<Outbound>,
        processReady: CompletableDeferred<Result<Unit>>,
    ) {
        val writer = BufferedWriter(
            OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8),
        )
        try {
            output.trySend(
                Outbound(
                    line = HelperProtocol.startCommand(),
                    sequence = 0L,
                    completion = CompletableDeferred(true),
                ),
            )
            for (message in output) {
                writer.write(message.line)
                writer.newLine()
                writer.flush()
                if (message.sequence != 0L) {
                    // The reader completes the deferred when tx_result arrives.
                }
            }
        } catch (error: Exception) {
            if (!stopping) {
                SLog.w(TAG, "helper stdin failed: ${error.javaClass.simpleName}")
            }
            processReady.complete(
                Result.failure(IllegalStateException("helper stdin closed", error)),
            )
            failPending("helper stdin closed")
        } finally {
            try {
                writer.close()
            } catch (_: Exception) {
                // Already closed.
            }
        }
    }

    private suspend fun readEvents(
        process: Process,
        processReady: CompletableDeferred<Result<Unit>>,
    ) {
        try {
            val reader = BufferedReader(
                InputStreamReader(process.inputStream, StandardCharsets.UTF_8),
            )
            reader.useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val event = try {
                        HelperProtocol.parse(line)
                    } catch (error: Exception) {
                        SLog.w(TAG, "helper sent an invalid line: ${error.message}")
                        if (!processReady.isCompleted) {
                            processReady.complete(
                                Result.failure(IllegalStateException("helper protocol error", error)),
                            )
                        }
                        continue
                    }
                    dispatch(event, processReady)
                }
            }
        } catch (error: Exception) {
            if (!stopping) {
                SLog.w(TAG, "helper stdout failed: ${error.javaClass.simpleName}")
            }
            if (!processReady.isCompleted) {
                processReady.complete(
                    Result.failure(IllegalStateException("helper stdout closed", error)),
                )
            }
        }
    }

    private fun readDiagnostics(process: Process) {
        try {
            val reader = BufferedReader(
                InputStreamReader(process.errorStream, StandardCharsets.UTF_8),
            )
            reader.useLines { lines ->
                for (line in lines) {
                    if (line.isNotBlank()) {
                        SLog.d(TAG, "helper: $line")
                    }
                }
            }
        } catch (_: Exception) {
            // The process is gone; the exit path reports the failure.
        }
    }

    private fun dispatch(
        event: HelperEvent,
        processReady: CompletableDeferred<Result<Unit>>,
    ) {
        when (event) {
            is HelperEvent.Ready -> {
                synchronized(stateLock) { restartAttempts = 0 }
                processReady.complete(Result.success(Unit))
                emit(
                    RelayTransportEvent.Ready(
                        platform = event.platform,
                        maxLinks = event.maxLinks,
                        serviceDataSupported = event.serviceDataSupported,
                    ),
                )
            }
            is HelperEvent.Advertising -> {
                when (event.state.lowercase()) {
                    "degraded" -> emit(
                        RelayTransportEvent.Error(
                            code = "ADVERTISING_DEGRADED",
                            message = event.detail ?: "advertising data was partially dropped",
                        ),
                    )
                    "aborted", "failed" -> emit(
                        RelayTransportEvent.Error(
                            code = "ADVERTISING_FAILED",
                            message = event.detail ?: "advertising failed",
                        ),
                    )
                    else -> Unit
                }
            }
            is HelperEvent.LinkUp -> {
                synchronized(stateLock) { knownLinks += event.linkId }
                emit(
                    RelayTransportEvent.LinkUp(
                        linkId = event.linkId,
                        mtu = event.mtu,
                        maxNotificationSize = event.maxNotificationSize,
                    ),
                )
            }
            is HelperEvent.LinkDown -> {
                synchronized(stateLock) { knownLinks -= event.linkId }
                emit(
                    RelayTransportEvent.LinkDown(
                        linkId = event.linkId,
                        reason = event.reason,
                    ),
                )
            }
            is HelperEvent.Rx -> emit(RelayTransportEvent.Bytes(event.linkId, event.data))
            is HelperEvent.TxResult -> {
                event.sequence?.let { seq ->
                    pending.remove(seq)?.complete(event.ok)
                }
                if (!event.ok) {
                    emit(
                        RelayTransportEvent.Error(
                            code = "TX_FAILED",
                            message = event.error ?: "helper rejected a notification",
                        ),
                    )
                }
            }
            is HelperEvent.Error -> {
                if (!processReady.isCompleted) {
                    processReady.complete(
                        Result.failure(
                            IllegalStateException("helper error ${event.code}: ${event.message}"),
                        ),
                    )
                }
                emit(RelayTransportEvent.Error(event.code, event.message))
            }
            is HelperEvent.Unknown -> SLog.w(TAG, "ignoring unknown helper event: ${event.type}")
        }
    }

    private fun handleProcessExit(exitCode: Int) {
        if (stopping) return
        val currentReady = ready
        if (!currentReady.isCompleted) {
            currentReady.complete(
                Result.failure(
                    IllegalStateException("helper exited before ready with code $exitCode"),
                ),
            )
        }
        val links = synchronized(stateLock) {
            val snapshot = knownLinks.toList()
            knownLinks.clear()
            snapshot
        }
        links.forEach { linkId ->
            emit(RelayTransportEvent.LinkDown(linkId, "helper_exit"))
        }
        failPending("helper exited")
        emit(
            RelayTransportEvent.Error(
                code = "HELPER_EXIT",
                message = "helper exited with code $exitCode",
            ),
        )

        val attempt = synchronized(stateLock) {
            if (restartAttempts >= maxRestarts) {
                false
            } else {
                restartAttempts += 1
                true
            }
        }
        if (!attempt) {
            SLog.e(TAG, "helper restart budget exhausted")
            return
        }

        restartJob = scope.launch {
            delay(restartDelayMs * restartAttempts)
            if (!stopping && started) {
                launchProcess()
            }
        }
    }

    private fun stopProcess() {
        val current = process
        if (current != null) {
            try {
                current.destroy()
                if (!current.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    current.destroyForcibly()
                }
            } catch (_: Exception) {
                // Best effort.
            }
        }
        process = null
        outbound = null
    }

    private fun failPending(reason: String) {
        val entries = pending.entries.toList()
        pending.clear()
        entries.forEach { (_, deferred) ->
            deferred.complete(false)
        }
        if (entries.isNotEmpty()) {
            SLog.w(TAG, "failed ${entries.size} pending transmissions: $reason")
        }
    }

    private fun emit(event: RelayTransportEvent) {
        eventChannel.trySend(event)
    }

    companion object {
        private const val TAG = "HelperProcessTransport"
        private const val OUTBOUND_CAPACITY = 256
    }
}
