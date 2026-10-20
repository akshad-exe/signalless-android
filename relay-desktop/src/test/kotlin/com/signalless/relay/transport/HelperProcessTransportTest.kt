package com.signalless.relay.transport

import com.signalless.app.util.SLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class HelperProcessTransportTest {
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        SLog.sink = { _, _, _, _ -> }
    }

    @After
    fun tearDown() {
        scope.cancel()
        SLog.sink = null
    }

    @Test
    fun `starts a helper, receives events, transmits, and shuts down`() = runBlocking {
        val command = helperCommand()
        val transport = HelperProcessTransport(
            command = command,
            scope = scope,
            startTimeoutMs = 10_000,
            txTimeoutMs = 5_000,
        )

        val start = transport.start()
        assertTrue(start.isSuccess)

        val events = CopyOnWriteArrayList<RelayTransportEvent>()
        val collector = scope.launch {
            transport.events.collect { events += it }
        }

        withTimeout(5_000) {
            while (events.none { it is RelayTransportEvent.LinkUp }) {
                kotlinx.coroutines.delay(10)
            }
        }

        assertTrue(events.any { it is RelayTransportEvent.Ready })
        assertTrue(
            events.any {
                it is RelayTransportEvent.Bytes &&
                    it.linkId == "l1" &&
                    it.data.contentEquals(byteArrayOf(1, 2, 3))
            },
        )

        val sent = transport.send("l1", byteArrayOf(9, 8, 7))
        assertTrue(sent.isSuccess)

        transport.stop()
        collector.cancel()
    }

    @Test
    fun `missing helper executable is a start failure`() = runBlocking {
        val transport = HelperProcessTransport(
            command = listOf("definitely-not-a-signalless-helper"),
            scope = scope,
            startTimeoutMs = 1_000,
        )
        val start = transport.start()
        assertTrue(start.isFailure)
        assertEquals(
            true,
            start.exceptionOrNull()?.message.orEmpty().contains("could not start"),
        )
    }

    private fun helperCommand(): List<String> {
        val java = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java",
        ).toString()
        val classpath = System.getProperty("java.class.path")
        return listOf(
            java,
            "-cp",
            classpath,
            "com.signalless.relay.transport.FakeHelperProcessMainKt",
        )
    }
}
